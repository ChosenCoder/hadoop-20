/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.raid;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.tools.DFSck;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobInProgress;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.raid.BlockReconstructor.CorruptBlockReconstructor;
import org.apache.hadoop.raid.DistBlockIntegrityMonitor.Worker.LostFileInfo;
import org.apache.hadoop.raid.LogUtils.LOGRESULTS;
import org.apache.hadoop.raid.LogUtils.LOGTYPES;
import org.apache.hadoop.raid.RaidUtils.RaidInfo;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ToolRunner;

/**
 * distributed block integrity monitor, uses parity to reconstruct lost files
 *
 * configuration options
 * raid.blockfix.filespertask       - number of files to reconstruct in a single
 *                                    map reduce task (i.e., at one mapper node)
 *
 * raid.blockfix.fairscheduler.pool - the pool to use for MR jobs
 *
 * raid.blockfix.maxpendingjobs    - maximum number of MR jobs
 *                                    running simultaneously
 */
public class DistBlockIntegrityMonitor extends BlockIntegrityMonitor {

  private static final String IN_FILE_SUFFIX = ".in";
  private static final String PART_PREFIX = "part-";
  static final Pattern LIST_CORRUPT_FILE_PATTERN =
      Pattern.compile("blk_-*\\d+\\s+(.*)");
  static final Pattern LIST_DECOMMISSION_FILE_PATTERN = 
      Pattern.compile("blk_-*\\d+\\s+(.*)"); // For now this is the same because of how dfsck generates output 

  
  private static final String FILES_PER_TASK = 
    "raid.blockfix.filespertask";
  private static final String MAX_PENDING_JOBS =
    "raid.blockfix.maxpendingjobs";
  private static final String HIGH_PRI_SCHEDULER_OPTION =     
    "raid.blockfix.highpri.scheduleroption";        
  private static final String LOW_PRI_SCHEDULER_OPTION =        
    "raid.blockfix.lowpri.scheduleroption";     
  private static final String LOWEST_PRI_SCHEDULER_OPTION =     
    "raid.blockfix.lowestpri.scheduleroption";
  private static final String MAX_FIX_TIME_FOR_FILE =
    "raid.blockfix.max.fix.time.for.file";
  private static final String LOST_FILES_LIMIT =
    "raid.blockfix.corruptfiles.limit";
  // The directories checked by the corrupt file monitor, seperate by comma
  private static final String RAIDNODE_CORRUPT_FILE_COUNTER_DIRECTORIES_KEY = 
      "raid.corruptfile.counter.dirs";
  private static final String[] DEFAULT_CORRUPT_FILE_COUNTER_DIRECTORIES = 
      new String[]{"/"};

  // default number of files to reconstruct in a task
  private static final long DEFAULT_FILES_PER_TASK = 10L;

  private static final int TASKS_PER_JOB = 50;

  // default number of files to reconstruct simultaneously
  private static final long DEFAULT_MAX_PENDING_JOBS = 100L;

  private static final long DEFAULT_MAX_FIX_TIME_FOR_FILE =
    4 * 60 * 60 * 1000;  // 4 hrs.

  private static final int DEFAULT_LOST_FILES_LIMIT = 200000;
  public static final String FAILED_FILE = "failed";
  public static final String SIMULATION_FAILED_FILE = "simulation_failed";
 
  protected static final Log LOG = LogFactory.getLog(DistBlockIntegrityMonitor.class);
  
  private static final String CORRUPT_FILE_DETECT_TIME = "corrupt_detect_time";

  // number of files to reconstruct in a task
  private long filesPerTask;

  // number of files to reconstruct simultaneously
  final private long maxPendingJobs;

  final private long maxFixTimeForFile;

  final private int lostFilesLimit;

  private final SimpleDateFormat dateFormat =
    new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
  
  private Worker corruptionWorker = new CorruptionWorker();
  private Worker decommissioningWorker = new DecommissioningWorker();
  private Runnable corruptFileCounterWorker = new CorruptFileCounter();

  static enum RaidCounter {
    FILES_SUCCEEDED, FILES_FAILED, FILES_NOACTION,
    BLOCK_FIX_SIMULATION_FAILED, BLOCK_FIX_SIMULATION_SUCCEEDED, 
    FILE_FIX_NUM_READBYTES_REMOTERACK
  }
  
  static enum Priority {
    HIGH  (HIGH_PRI_SCHEDULER_OPTION,   2),
    LOW   (LOW_PRI_SCHEDULER_OPTION,    1),
    LOWEST(LOWEST_PRI_SCHEDULER_OPTION, 0);
    
    public final String configOption;
    private final int underlyingValue;
    
    private Priority(String s, int value) {
      configOption = s;
      underlyingValue = value;
    }
    
    public boolean higherThan (Priority other) {
      return (underlyingValue > other.underlyingValue);
    }
  }
  
  /**
   * Hold information about a failed file with task id
   */
  static public class FailedFileInfo {
    String taskId;
    LostFileInfo fileInfo;
    public FailedFileInfo(String newTaskId, LostFileInfo newFileInfo) {
      this.taskId = newTaskId;
      this.fileInfo = newFileInfo;
    }
  }

  public DistBlockIntegrityMonitor(Configuration conf) {
    super(conf);
    filesPerTask = DistBlockIntegrityMonitor.getFilesPerTask(getConf());
    maxPendingJobs = DistBlockIntegrityMonitor.getMaxPendingJobs(getConf());
    maxFixTimeForFile = DistBlockIntegrityMonitor.getMaxFixTimeForFile(getConf());
    lostFilesLimit = DistBlockIntegrityMonitor.getLostFilesLimit(getConf());
  }

  /**
   * determines how many files to reconstruct in a single task
   */ 
  protected static long getFilesPerTask(Configuration conf) {
    return conf.getLong(FILES_PER_TASK, 
                        DEFAULT_FILES_PER_TASK);

  }
  /**
   * determines how many files to reconstruct simultaneously
   */ 
  protected static long getMaxPendingJobs(Configuration conf) {
    return conf.getLong(MAX_PENDING_JOBS,
                        DEFAULT_MAX_PENDING_JOBS);
  }

  protected static long getMaxFixTimeForFile(Configuration conf) {
    return conf.getLong(MAX_FIX_TIME_FOR_FILE,
                        DEFAULT_MAX_FIX_TIME_FOR_FILE);
  }

  protected static int getLostFilesLimit(Configuration conf) {
    return conf.getInt(LOST_FILES_LIMIT, DEFAULT_LOST_FILES_LIMIT);
  }
  
  public abstract class Worker implements Runnable {

    protected Map<String, LostFileInfo> fileIndex = Collections.synchronizedMap(
      new HashMap<String, LostFileInfo>());
    protected Map<Job, List<LostFileInfo>> jobIndex =
      new HashMap<Job, List<LostFileInfo>>();
    protected Map<Job, List<FailedFileInfo>> failJobIndex =
        new HashMap<Job, List<FailedFileInfo>>();
    protected Map<Job, List<FailedFileInfo>> simFailJobIndex =
      new HashMap<Job, List<FailedFileInfo>>();

    private long jobCounter = 0;
    private volatile int numJobsRunning = 0;
    
    protected long numFilesDropped = 0;
    
    volatile BlockIntegrityMonitor.Status lastStatus = null;
    AtomicLong recentNumFilesSucceeded = new AtomicLong();
    AtomicLong recentNumFilesFailed = new AtomicLong();
    AtomicLong recentSlotSeconds = new AtomicLong();
    AtomicLong recentNumBlockFixSimulationSucceeded = new AtomicLong();
    AtomicLong recentNumBlockFixSimulationFailed = new AtomicLong();
    AtomicLong recentNumReadBytesRemoteRack = new AtomicLong();
    Map<String, Long> recentLogMetrics = 
        Collections.synchronizedMap(new HashMap<String, Long>());
    
    private static final int POOL_SIZE = 2;
    private final ExecutorService executor = 
        Executors.newFixedThreadPool(POOL_SIZE);
    private static final int DEFAULT_CHECK_JOB_TIMEOUT_SEC = 600; //10 mins

    protected final Log LOG;
    protected final Class<? extends BlockReconstructor> RECONSTRUCTOR_CLASS;
    protected final String JOB_NAME_PREFIX;


    protected Worker(Log log, 
        Class<? extends BlockReconstructor> rClass, 
        String prefix) {

      this.LOG = log;
      this.RECONSTRUCTOR_CLASS = rClass;
      this.JOB_NAME_PREFIX = prefix;
      Path workingDir = new Path(prefix);
      try {
        FileSystem fs = workingDir.getFileSystem(getConf());
        // Clean existing working dir
        fs.delete(workingDir, true);
      } catch (IOException ioe) {
        LOG.warn("Get exception when cleaning " + workingDir, ioe);
      }
    }


    /**
     * runs the worker periodically
     */
    public void run() {
      while (running) {
        try {
          updateStatus();
          checkAndReconstructBlocks();
        } catch (InterruptedException ignore) {
          LOG.info("interrupted");
        } catch (Exception e) {
          // log exceptions and keep running
          LOG.error(StringUtils.stringifyException(e));
        } catch (Error e) {
          LOG.error(StringUtils.stringifyException(e));
          throw e;
        }

        try {
          Thread.sleep(blockCheckInterval);
        } catch (InterruptedException ignore) {
          LOG.info("interrupted");
        }
      }
    }

    /**
     * checks for lost blocks and reconstructs them (if any)
     */
    void checkAndReconstructBlocks() throws Exception {
      checkJobsWithTimeOut(DEFAULT_CHECK_JOB_TIMEOUT_SEC);
      if (jobIndex.size() >= maxPendingJobs) {
        LOG.info("Waiting for " + jobIndex.size() + " pending jobs");
        return;
      }

      Map<String, Integer> lostFiles = getLostFiles();
      long detectTime = System.currentTimeMillis();
      FileSystem fs = new Path("/").getFileSystem(getConf());
      Map<String, Priority> filePriorities =
        computePriorities(fs, lostFiles);

      LOG.info("Found " + filePriorities.size() + " new lost files");

      startJobs(filePriorities, detectTime);
    }

    /**
     * Handle a failed job.
     */
    private void failJob(Job job) {
      // assume no files have been reconstructed
      LOG.error("Job " + job.getID() + "(" + job.getJobName() +
      ") finished (failed)");
      // We do not change metrics here since we do not know for sure if file
      // reconstructing failed.
      for (LostFileInfo fileInfo: jobIndex.get(job)) {
        boolean failed = true;
        addToMap(job, job.getID().toString(), fileInfo, failJobIndex);
        fileInfo.finishJob(job.getJobName(), failed);
      }
      numJobsRunning--;
    }
    
    private void addToMap(Job job, String taskId, LostFileInfo fileInfo, 
        Map<Job, List<FailedFileInfo>> index) {
      List<FailedFileInfo> failFiles = null;
      if (!index.containsKey(job)) {
        failFiles = new ArrayList<FailedFileInfo>();
        index.put(job, failFiles);
      } else {
        failFiles = index.get(job);
      }
      failFiles.add(new FailedFileInfo(taskId, fileInfo));
    }

    /**
     * Handle a successful job.
     */
    private void succeedJob(Job job, long filesSucceeded, long filesFailed)
    throws IOException {
      String jobName = job.getJobName();
      LOG.info("Job " + job.getID() + "(" + jobName +
      ") finished (succeeded)");
      // we have to look at the output to check which files have failed
      HashMap<String, String> failedFiles = getFailedFiles(job);
      for (LostFileInfo fileInfo: jobIndex.get(job)) {
        String filePath = fileInfo.getFile().toString();
        String failedFilePath = 
            DistBlockIntegrityMonitor.FAILED_FILE + "," +
            filePath;
        String simulatedFailedFilePath =
            DistBlockIntegrityMonitor.SIMULATION_FAILED_FILE + "," + 
            filePath;
        if (failedFiles.containsKey(simulatedFailedFilePath)) {
          String taskId = failedFiles.get(simulatedFailedFilePath);
          addToMap(job, taskId, fileInfo, simFailJobIndex);
          LOG.error("Simulation failed file: " + fileInfo.getFile());
        }
        if (failedFiles.containsKey(failedFilePath)) {
          String taskId = failedFiles.get(failedFilePath);
          addToMap(job, taskId, fileInfo, failJobIndex);
          boolean failed = true;
          fileInfo.finishJob(jobName, failed);
        } else {
          // call succeed for files that have succeeded or for which no action
          // was taken
          boolean failed = false;
          fileInfo.finishJob(jobName, failed);
        }
      }
      // report succeeded files to metrics
      this.recentNumFilesSucceeded.addAndGet(filesSucceeded);
      this.recentNumFilesFailed.addAndGet(filesFailed);
      numJobsRunning--;
    }
    
    /**
     * Check the jobs with timeout
     */
    void checkJobsWithTimeOut(int timeoutSec) 
        throws ExecutionException {
      Future<Boolean> future = executor.submit(new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          checkJobs();
          return true;
        }
      });
      try {
        future.get(timeoutSec, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        // ignore this.
        LOG.warn("Timeout when checking jobs' status.");
      } catch (InterruptedException e) {
        // ignore this.
        LOG.warn("checkJobs function is interrupted.");
      }
      if (!future.isDone()) {
        future.cancel(true);
      }
    }

    /**
     * checks if jobs have completed and updates job and file index
     * returns a list of failed files for restarting
     */
    void checkJobs() throws IOException {
      Iterator<Job> jobIter = jobIndex.keySet().iterator();
      List<Job> nonRunningJobs = new ArrayList<Job>();
      while(jobIter.hasNext()) {
        Job job = jobIter.next();

        try {
          if (job.isComplete()) {
            Counters ctrs = job.getCounters();
            if (ctrs != null) {
              // If we got counters, perform extra validation.
              this.recentSlotSeconds.addAndGet(ctrs.findCounter(
                  JobInProgress.Counter.SLOTS_MILLIS_MAPS).getValue() / 1000);
              
              long filesSucceeded =
                  ctrs.findCounter(RaidCounter.FILES_SUCCEEDED) != null ?
                    ctrs.findCounter(RaidCounter.FILES_SUCCEEDED).getValue() : 0;
              long filesFailed =
                  ctrs.findCounter(RaidCounter.FILES_FAILED) != null ?
                    ctrs.findCounter(RaidCounter.FILES_FAILED).getValue() : 0;
              long filesNoAction =
                  ctrs.findCounter(RaidCounter.FILES_NOACTION) != null ?
                    ctrs.findCounter(RaidCounter.FILES_NOACTION).getValue() : 0;
              long blockFixSimulationFailed = 
                  ctrs.findCounter(RaidCounter.BLOCK_FIX_SIMULATION_FAILED) != null?
                    ctrs.findCounter(RaidCounter.BLOCK_FIX_SIMULATION_FAILED).getValue() : 0;
              long blockFixSimulationSucceeded = 
                  ctrs.findCounter(RaidCounter.BLOCK_FIX_SIMULATION_SUCCEEDED) != null?
                    ctrs.findCounter(RaidCounter.BLOCK_FIX_SIMULATION_SUCCEEDED).getValue() : 0;
              this.recentNumBlockFixSimulationFailed.addAndGet(blockFixSimulationFailed);
              this.recentNumBlockFixSimulationSucceeded.addAndGet(blockFixSimulationSucceeded);
              long fileFixNumReadBytesRemoteRack = 
                  ctrs.findCounter(RaidCounter.FILE_FIX_NUM_READBYTES_REMOTERACK) != null ?
                    ctrs.findCounter(RaidCounter.FILE_FIX_NUM_READBYTES_REMOTERACK).getValue() : 0;
              this.recentNumReadBytesRemoteRack.addAndGet(fileFixNumReadBytesRemoteRack);
              CounterGroup counterGroup = ctrs.getGroup(LogUtils.LOG_COUNTER_GROUP_NAME);
              for (Counter ctr: counterGroup) {
                Long curVal = ctr.getValue();
                if (this.recentLogMetrics.containsKey(ctr.getName())) {
                  curVal += this.recentLogMetrics.get(ctr.getName());
                }
                this.recentLogMetrics.put(ctr.getName(), curVal);
              }
              
              int files = jobIndex.get(job).size();
              
              if (job.isSuccessful() &&
                  (filesSucceeded + filesFailed + filesNoAction ==
                    ((long) files))) {
                // job has processed all files
                succeedJob(job, filesSucceeded, filesFailed);
              } else {
                failJob(job);
              }
            } else {
              long filesSucceeded = jobIndex.get(job).size();
              long filesFailed = 0;
              if (job.isSuccessful()) {
                succeedJob(job, filesSucceeded, filesFailed);
              } else {
                failJob(job);
              }
            }
            jobIter.remove();
            nonRunningJobs.add(job);
          } else {
            LOG.info("Job " + job.getID() + "(" + job.getJobName()
                + " still running");
          }
        } catch (Exception e) {
          LOG.error(StringUtils.stringifyException(e));
          failJob(job);
          jobIter.remove();
          nonRunningJobs.add(job);
          try {
            job.killJob();
          } catch (Exception ee) {
            LOG.error(StringUtils.stringifyException(ee));
          }
        }
      }
      purgeFileIndex();
      cleanupNonRunningJobs(nonRunningJobs);
    }

    /**
     * Delete (best-effort) the input and output directories of jobs.
     * @param nonRunningJobs
     */
    private void cleanupNonRunningJobs(List<Job> nonRunningJobs) {
      for (Job job: nonRunningJobs) {
        Path outDir = null;
        try {
          outDir = SequenceFileOutputFormat.getOutputPath(job);
          outDir.getFileSystem(getConf()).delete(outDir, true);
        } catch (IOException e) {
          LOG.warn("Could not delete output dir " + outDir, e);
        }
        Path[] inDir = null;
        try {
          // We only create one input directory.
          inDir = ReconstructionInputFormat.getInputPaths(job);
          inDir[0].getFileSystem(getConf()).delete(inDir[0], true);
        } catch (IOException e) {
          LOG.warn("Could not delete input dir " + inDir[0], e);
        }
      }
    }


    /**
     * determines which files have failed for a given job
     */
    private HashMap<String, String> getFailedFiles(Job job) throws IOException {
      HashMap<String, String> failedFiles = new HashMap<String, String>();

      Path outDir = SequenceFileOutputFormat.getOutputPath(job);
      FileSystem fs  = outDir.getFileSystem(getConf());
      if (!fs.getFileStatus(outDir).isDir()) {
        throw new IOException(outDir.toString() + " is not a directory");
      }

      FileStatus[] files = fs.listStatus(outDir);

      for (FileStatus f: files) {
        Path fPath = f.getPath();
        if ((!f.isDir()) && (fPath.getName().startsWith(PART_PREFIX))) {
          LOG.info("opening " + fPath.toString());
          SequenceFile.Reader reader = 
            new SequenceFile.Reader(fs, fPath, getConf());

          Text key = new Text();
          Text value = new Text();
          while (reader.next(key, value)) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("key: " + key.toString() + " , value: " + value.toString());
            }
            failedFiles.put(key.toString(), value.toString());
          }
          reader.close();
        }
      }
      return failedFiles;
    }


    /**
     * purge expired jobs from the file index
     */
    private void purgeFileIndex() {
      Iterator<String> fileIter = fileIndex.keySet().iterator();
      long now = System.currentTimeMillis();
      while(fileIter.hasNext()) {
        String file = fileIter.next();
        if (fileIndex.get(file).isTooOld(now)) {
          fileIter.remove();
        }
      }
    }

    // Start jobs for all the lost files.
    private void startJobs(Map<String, Priority> filePriorities, long detectTime)
    throws IOException, InterruptedException, ClassNotFoundException {
      String startTimeStr = dateFormat.format(new Date());
      long numFilesSubmitted = 0;
      for (Priority pri : Priority.values()) {
        Set<String> jobFiles = new HashSet<String>();
        for (Map.Entry<String, Priority> entry: filePriorities.entrySet()) {
          // Check if file priority matches the current round.
          if (entry.getValue() != pri) {
            continue;
          }
          jobFiles.add(entry.getKey());
          // Check if we have hit the threshold for number of files in a job.
          if (jobFiles.size() == filesPerTask * TASKS_PER_JOB) {
            String jobName = JOB_NAME_PREFIX + "." + jobCounter +
            "." + pri + "-pri" + "." + startTimeStr;
            jobCounter++;
            startJob(jobName, jobFiles, pri, detectTime);
            numFilesSubmitted += jobFiles.size();
            jobFiles.clear();
            if (jobIndex.size() >= maxPendingJobs) {
              this.numFilesDropped = filePriorities.size() - numFilesSubmitted;
              LOG.debug("Submitted a job with max number of files allowed. Num files dropped is " + this.numFilesDropped);
              return;
            }
          }
        }
        if (jobFiles.size() > 0) {
          String jobName = JOB_NAME_PREFIX + "." + jobCounter +
          "." + pri + "-pri" + "." + startTimeStr;
          jobCounter++;
          startJob(jobName, jobFiles, pri, detectTime);
          numFilesSubmitted += jobFiles.size();
          jobFiles.clear();
          if (jobIndex.size() >= maxPendingJobs) {
            this.numFilesDropped = filePriorities.size() - numFilesSubmitted;
            LOG.debug("Submitted a job with less than max allowed files. Num files dropped is " + this.numFilesDropped);
            return;
          }
        }
      }
      this.numFilesDropped = filePriorities.size() - numFilesSubmitted;
    }

    /**
     * creates and submits a job, updates file index and job index
     */
    private void startJob(String jobName, Set<String> lostFiles, Priority priority, long detectTime)
    throws IOException, InterruptedException, ClassNotFoundException {
      Path inDir = new Path(JOB_NAME_PREFIX + "/in/" + jobName);
      Path outDir = new Path(JOB_NAME_PREFIX + "/out/" + jobName);
      List<String> filesInJob = createInputFile(
          jobName, inDir, lostFiles);
      if (filesInJob.isEmpty()) return;

      Configuration jobConf = new Configuration(getConf());
      RaidUtils.parseAndSetOptions(jobConf, priority.configOption);
      Job job = new Job(jobConf, jobName);
      job.getConfiguration().set(CORRUPT_FILE_DETECT_TIME, Long.toString(detectTime));
      configureJob(job, this.RECONSTRUCTOR_CLASS);
      job.setJarByClass(getClass());
      job.setMapperClass(ReconstructionMapper.class);
      job.setNumReduceTasks(0);
      job.setInputFormatClass(ReconstructionInputFormat.class);
      job.setOutputFormatClass(SequenceFileOutputFormat.class);
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(Text.class);

      ReconstructionInputFormat.setInputPaths(job, inDir);
      SequenceFileOutputFormat.setOutputPath(job, outDir);
      

      submitJob(job, filesInJob, priority);
      List<LostFileInfo> fileInfos =
        updateFileIndex(jobName, filesInJob, priority);
      // The implementation of submitJob() need not update jobIndex.
      // So check if the job exists in jobIndex before updating jobInfos.
      if (jobIndex.containsKey(job)) {
        jobIndex.put(job, fileInfos);
      }
      numJobsRunning++;
    }

    void submitJob(Job job, List<String> filesInJob, Priority priority)
    throws IOException, InterruptedException, ClassNotFoundException {
      LOG.info("Submitting job");
      DistBlockIntegrityMonitor.this.submitJob(job, filesInJob, priority, this.jobIndex);
    }

    /**
     * inserts new job into file index and job index
     */
    private List<LostFileInfo> updateFileIndex(
        String jobName, List<String> lostFiles, Priority priority) {
      List<LostFileInfo> fileInfos = new ArrayList<LostFileInfo>();

      for (String file: lostFiles) {
        LostFileInfo fileInfo = fileIndex.get(file);
        if (fileInfo != null) {
          fileInfo.addJob(jobName, priority);
        } else {
          fileInfo = new LostFileInfo(file, jobName, priority);
          fileIndex.put(file, fileInfo);
        }
        fileInfos.add(fileInfo);
      }
      return fileInfos;
    }

    /**
     * creates the input file (containing the names of the files to be 
     * reconstructed)
     */
    private List<String> createInputFile(String jobName, Path inDir,
        Set<String> lostFiles) throws IOException {
      Path file = new Path(inDir, jobName + IN_FILE_SUFFIX);
      FileSystem fs = file.getFileSystem(getConf());
      SequenceFile.Writer fileOut = SequenceFile.createWriter(fs, getConf(), file,
          LongWritable.class,
          Text.class);
      long index = 0L;

      List<String> filesAdded = new ArrayList<String>();
      int count = 0;
      for (String lostFileName: lostFiles) {
        fileOut.append(new LongWritable(index++), new Text(lostFileName));
        filesAdded.add(lostFileName);
        count++;

        if (index % filesPerTask == 0) {
          fileOut.sync(); // create sync point to make sure we can split here
        }
      }

      fileOut.close();
      return filesAdded;
    }
  
    /**
     * Update {@link lastStatus} so that it can be viewed from outside
     */
    protected void updateStatus() {
      int highPriorityFiles = 0;
      int lowPriorityFiles = 0;
      int lowestPriorityFiles = 0;
      List<JobStatus> jobs = new ArrayList<JobStatus>();
      List<JobStatus> failJobs = new ArrayList<JobStatus>();
      List<JobStatus> simFailJobs = new ArrayList<JobStatus>();
      List<String> highPriorityFileNames = new ArrayList<String>();
      for (Map.Entry<String, LostFileInfo> e : fileIndex.entrySet()) {
        String fileName = e.getKey();
        LostFileInfo fileInfo = e.getValue();
        Priority pri = fileInfo.getHighestPriority();
        if (pri == Priority.HIGH) {
          highPriorityFileNames.add(fileName);
          highPriorityFiles++;
        } else if (pri == Priority.LOW){
          lowPriorityFiles++;
        } else if (pri == Priority.LOWEST) {
          lowestPriorityFiles++;
        }
      }
      for (Job job : jobIndex.keySet()) {
        String url = job.getTrackingURL();
        String name = job.getJobName();
        JobID jobId = job.getID();
        jobs.add(new BlockIntegrityMonitor.JobStatus(jobId, name, url,
            jobIndex.get(job), null));
      }
      for (Job job : failJobIndex.keySet()) {
        String url = job.getTrackingURL();
        String name = job.getJobName();
        JobID jobId = job.getID();
        failJobs.add(new BlockIntegrityMonitor.JobStatus(jobId, name, url,
            null, failJobIndex.get(job)));
      }
      for (Job simJob : simFailJobIndex.keySet()) {
        String url = simJob.getTrackingURL();
        String name = simJob.getJobName();
        JobID jobId = simJob.getID();
        simFailJobs.add(new BlockIntegrityMonitor.JobStatus(jobId, name, url,
            null, simFailJobIndex.get(simJob)));
      }
      lastStatus = new BlockIntegrityMonitor.Status(highPriorityFiles, lowPriorityFiles,
          lowestPriorityFiles, jobs, highPriorityFileNames, failJobs, simFailJobs);
      updateRaidNodeMetrics();
    }
    
    public Status getStatus() {
      return lastStatus;
    }

    abstract Map<String, Priority> computePriorities(
        FileSystem fs, Map<String, Integer> lostFiles) throws IOException;

    protected abstract Map<String, Integer> getLostFiles() throws IOException;

    protected abstract void updateRaidNodeMetrics();
    
    /**
     * hold information about a lost file that is being reconstructed
     */
    class LostFileInfo {

      private String file;
      private List<String> jobNames;  // Jobs reconstructing this file.
      private boolean done;
      private List<Priority> priorities;
      private long insertTime;

      public LostFileInfo(String file, String jobName, Priority priority) {
        this.file = file;
        this.jobNames = new ArrayList<String>();
        this.priorities = new ArrayList<Priority>();
        this.done = false;
        this.insertTime = System.currentTimeMillis();
        addJob(jobName, priority);
      }

      public boolean isTooOld(long now) {
        return now - insertTime > maxFixTimeForFile;
      }

      public boolean isDone() {
        return done;
      }

      public void addJob(String jobName, Priority priority) {
        this.jobNames.add(jobName);
        this.priorities.add(priority);
      }

      public Priority getHighestPriority() {
        Priority max = Priority.LOWEST;
        for (Priority p: priorities) {
          if (p.higherThan(max)) max = p;
        }
        return max;
      }

      public String getFile() {
        return file;
      }

      /**
       * Updates state with the completion of a job. If all jobs for this file
       * are done, the file index is updated.
       */
      public void finishJob(String jobName, boolean failed) {
        int idx = jobNames.indexOf(jobName);
        if (idx == -1) return;
        jobNames.remove(idx);
        priorities.remove(idx);
        LOG.info("reconstructing " + file +
            (failed ? " failed in " : " succeeded in ") +
            jobName);
        if (jobNames.isEmpty()) {
          // All jobs dealing with this file are done,
          // remove this file from the index
          LostFileInfo removed = fileIndex.remove(file);
          if (removed == null) {
            LOG.error("trying to remove file not in file index: " + file);
          }
          done = true;
        }
      }
    }

  }
  
  static public String[] getCorruptMonitorDirs(Configuration conf) {
    return conf.getStrings(
        RAIDNODE_CORRUPT_FILE_COUNTER_DIRECTORIES_KEY,
        DEFAULT_CORRUPT_FILE_COUNTER_DIRECTORIES);
  }
  
  /**
   * CorruptFileCounter is a periodical running daemon that keeps running raidfsck 
   * to get the number of the corrupt files under the give directories defined by 
   * RAIDNODE_CORRUPT_FILE_COUNTER_DIRECTORIES_KEY
   * @author weiyan
   *
   */
  public class CorruptFileCounter implements Runnable {
    public String[] corruptMonitorDirs = null;
    private TreeMap<String, Long> unRecoverableCounterMap = new TreeMap<String, Long>();
    private Map<String, Long> recoverableCounterMap = new HashMap<String, Long>();
    private long filesWithMissingBlksCnt = 0;
    private Map<String, long[]> numStrpWithMissingBlksMap = new HashMap<String, long[]>();
    private Object counterMapLock = new Object();
    private long numNonRaidedMissingBlocks = 0;

    public CorruptFileCounter() {
      this.corruptMonitorDirs = getCorruptMonitorDirs(getConf());
      for (Codec codec : Codec.getCodecs()) {
        this.numStrpWithMissingBlksMap.put(codec.id,
            new long[codec.stripeLength + codec.parityLength]);
      }
    }

    public void run() {
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID)
        .initCorruptFilesMetrics(getConf());
      while (running) {
        TreeMap<String, Long> newUnRecoverableCounterMap = new TreeMap<String, Long>();
        Map<String, Long> newRecoverableCounterMap = new HashMap<String, Long>();
        long newfilesWithMissingBlksCnt = 0;

        for (String srcDir : corruptMonitorDirs) {
          try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bout, true);
            RaidShell shell = new RaidShell(getConf(), ps);
            int res = ToolRunner.run(shell, new String[] { "-fsck", srcDir,
                "-count", "-retNumStrpsMissingBlks" });
            shell.close();
            ByteArrayInputStream bin = new ByteArrayInputStream(
                bout.toByteArray());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                  bin));
            String line = reader.readLine();
            if (line == null) {
              throw new IOException("Raidfsck fails without output");
            }
            Long corruptCount = Long.parseLong(line);
            LOG.info("The number of corrupt files under " + srcDir + " is "
                + corruptCount);
            newUnRecoverableCounterMap.put(srcDir, corruptCount);
            line = reader.readLine();
            if (line == null) {
              throw new IOException("Raidfsck did not print number "
                  + "of files with missing blocks");
            }

            // get files with Missing Blks
            // fsck with '-count' prints this number in line2
            long incfilesWithMissingBlks = Long.parseLong(line);
            LOG.info("The number of files with missing blocks under " + srcDir
                + " is " + incfilesWithMissingBlks);

            newRecoverableCounterMap.put(srcDir,
                incfilesWithMissingBlks - corruptCount);

            // Add filesWithMissingBlks and numStrpWithMissingBlks only for "/"
            // dir to avoid duplicates
            if (srcDir.equals("/")) {
              Map<String, long[]> newNumStrpWithMissingBlksMap = new HashMap<String, long[]>();
              newfilesWithMissingBlksCnt += incfilesWithMissingBlks;
              // read the array for num stripes with missing blocks

              line = reader.readLine();
              if (line == null) {
                throw new IOException("Raidfsck did not print the number of "
                    + "missing blocks in non raided files");
              }
              long numNonRaided = Long.parseLong(line);

              for (int i = 0; i < Codec.getCodecs().size(); i++) {
                line = reader.readLine();
                if (line == null) {
                  throw new IOException("Raidfsck did not print the missing "
                      + "block info for codec at index " + i);
                }

                Codec codec = Codec.getCodec(line);
                long[] incNumStrpWithMissingBlks = new long[codec.stripeLength
                  + codec.parityLength];
                for (int j = 0; j < incNumStrpWithMissingBlks.length; j++) {
                  line = reader.readLine();
                  if (line == null) {
                    throw new IOException("Raidfsck did not print the array "
                        + "for number stripes with missing blocks for index "
                        + j);
                  }
                  incNumStrpWithMissingBlks[j] = Long.parseLong(line);
                  LOG.info("The number of stripes with missing blocks at index"
                      + j + "under" + srcDir + " is "
                      + incNumStrpWithMissingBlks[j]);
                }
                newNumStrpWithMissingBlksMap.put(codec.id,
                    incNumStrpWithMissingBlks);
              }
              synchronized (counterMapLock) {
                this.numNonRaidedMissingBlocks = numNonRaided;
                for (String codeId : newNumStrpWithMissingBlksMap.keySet()) {
                  numStrpWithMissingBlksMap.put(codeId,
                      newNumStrpWithMissingBlksMap.get(codeId));
                }
              }
            }
            reader.close();
            bin.close();
          } catch (Exception e) {
            LOG.error("Fail to count the corrupt files under " + srcDir, e);
          }
        }
        synchronized (counterMapLock) {
          this.unRecoverableCounterMap = newUnRecoverableCounterMap;
          this.recoverableCounterMap = newRecoverableCounterMap;
          this.filesWithMissingBlksCnt = newfilesWithMissingBlksCnt;
        }
        updateRaidNodeMetrics();
        if (!running) {
          break;
        }
        try {
          Thread.sleep(corruptFileCountInterval);
        } catch (InterruptedException ignore) {
          LOG.info("interrupted");
        }
      }
    }

    public long getNumNonRaidedMissingBlks() {
      synchronized (counterMapLock) {
        return this.numNonRaidedMissingBlocks;
      }
    }

    public Map<String, Long> getUnRecoverableCounterMap() {
      synchronized (counterMapLock) {
        return unRecoverableCounterMap;
      }
    }

    public Map<String, Long> getRecoverableCounterMap() {
      synchronized (counterMapLock) {
        return recoverableCounterMap;
      }
    }

    public long getFilesWithMissingBlksCnt() {
      synchronized (counterMapLock) {
        return filesWithMissingBlksCnt;
      }
    }

    public long[] getNumStrpWithMissingBlksRS() {
      synchronized (counterMapLock) {
        return numStrpWithMissingBlksMap.get("rs");
      }
    }

    protected void updateRaidNodeMetrics() {
      RaidNodeMetrics rnm = RaidNodeMetrics
        .getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID);

      synchronized (counterMapLock) {
        for (String dir : corruptMonitorDirs) {
          if (this.unRecoverableCounterMap.containsKey(dir)) {
            rnm.corruptFiles.get(dir)
              .set(this.unRecoverableCounterMap.get(dir));
          } else {
            rnm.corruptFiles.get(dir).set(-1L);
          }
        }
        rnm.numFilesWithMissingBlks.set(this.filesWithMissingBlksCnt);
        long[] numStrpWithMissingBlksRS = this.numStrpWithMissingBlksMap
          .get("rs");

        if (numStrpWithMissingBlksRS != null) {
          rnm.numStrpsOneMissingBlk.set(numStrpWithMissingBlksRS[0]);
          rnm.numStrpsTwoMissingBlk.set(numStrpWithMissingBlksRS[1]);
          rnm.numStrpsThreeMissingBlk.set(numStrpWithMissingBlksRS[2]);
          rnm.numStrpsFourMissingBlk.set(numStrpWithMissingBlksRS[3]);

          long tmp_sum = 0;
          for (int idx = 4; idx < numStrpWithMissingBlksRS.length; idx++) {
            tmp_sum += numStrpWithMissingBlksRS[idx];
          }
          rnm.numStrpsFiveMoreMissingBlk.set(tmp_sum);
        }
      }
    }

    public String getMissingBlksHtmlTable() {
      synchronized (counterMapLock) {
        return RaidUtils.getMissingBlksHtmlTable(
            this.numNonRaidedMissingBlocks, this.numStrpWithMissingBlksMap);
      }
    }
  }

  /**
   * Get the lost blocks numbers per stripe in the source file.
   */
  private Map<Integer, Integer> getLostStripes(
              Configuration conf, FileStatus stat, FileSystem fs) 
                  throws IOException {
    Map<Integer, Integer> lostStripes = new HashMap<Integer, Integer>();
    RaidInfo raidInfo = RaidUtils.getFileRaidInfo(stat, conf);
    if (raidInfo.codec == null) {
      // Can not find the parity file, the file is not raided.
      return lostStripes;
    }
    Codec codec = raidInfo.codec;
    
    if (codec.isDirRaid) {
      RaidUtils.collectDirectoryCorruptBlocksInStripe(conf, 
          (DistributedFileSystem)fs, raidInfo, 
          stat.getPath(), lostStripes);
    } else {
      RaidUtils.collectFileCorruptBlocksInStripe((DistributedFileSystem)fs, 
          raidInfo, stat.getPath(), lostStripes);
    }
    return lostStripes;
  }

  public class CorruptionWorker extends Worker {

    public CorruptionWorker() {
      super(LogFactory.getLog(CorruptionWorker.class), 
          CorruptBlockReconstructor.class, 
          "blockfixer");
    }

    @Override
    protected Map<String, Integer> getLostFiles() throws IOException {
      return DistBlockIntegrityMonitor.this.getLostFiles(LIST_CORRUPT_FILE_PATTERN, 
          new String[]{"-list-corruptfileblocks", "-limit", 
          new Integer(lostFilesLimit).toString()});
    }

    @Override
    // Compute integer priority. Urgency is indicated by higher numbers.
    Map<String, Priority> computePriorities(
        FileSystem fs, Map<String, Integer> corruptFiles) throws IOException {

      Map<String, Priority> fileToPriority = new HashMap<String, Priority>();
      Set<String> srcDirsToWatchOutFor = new HashSet<String>();
      // Loop over parity files once.
      for (Iterator<String> it = corruptFiles.keySet().iterator(); it.hasNext(); ) {
        String p = it.next();
        if (BlockIntegrityMonitor.isSourceFile(p)) {
          continue;
        }
        // Find the parent of the parity file.
        Path parent = new Path(p).getParent();
        // If the file was a HAR part file, the parent will end with _raid.har. In
        // that case, the parity directory is the parent of the parent.
        if (parent.toUri().getPath().endsWith(RaidNode.HAR_SUFFIX)) {
          parent = parent.getParent();
        }
        String parentUriPath = parent.toUri().getPath();
        // Remove the RAID prefix to get the source dir.
        srcDirsToWatchOutFor.add(
            parentUriPath.substring(parentUriPath.indexOf(Path.SEPARATOR, 1)));
        // Dikang: for parity files, we use the total numbers for now.
        int numCorrupt = corruptFiles.get(p);
        Priority priority = (numCorrupt > 1) ? Priority.HIGH : Priority.LOW;
        LostFileInfo fileInfo = fileIndex.get(p);
        if (fileInfo == null || priority.higherThan(fileInfo.getHighestPriority())) {
          fileToPriority.put(p, priority);
        }
      }
      // Loop over src files now.
      for (Iterator<String> it = corruptFiles.keySet().iterator(); it.hasNext(); ) {
        String p = it.next();
        if (BlockIntegrityMonitor.isSourceFile(p)) {
          FileStatus stat = null;
          try {
            stat = fs.getFileStatus(new Path(p));
          } catch (FileNotFoundException fnfe) {
            continue;
          }
          if (stat.getReplication() >= notRaidedReplication) {
            continue;
          }
          if (BlockIntegrityMonitor.doesParityDirExist(fs, p)) {
            Map<Integer, Integer> lostStripes = getLostStripes(getConf(), stat,
                fs);
            int numCorrupt = 0;
            for (int num : lostStripes.values()) {
              numCorrupt += num;
            }
            Priority priority = Priority.LOW;
            if (stat.getReplication() > 1) {
              // If we have a missing block when replication > 1, it is high pri.
              priority = Priority.HIGH;
            } else {
              // Replication == 1. Assume Reed Solomon parity exists.
              // If we have more than one missing block when replication == 1, then
              // high pri.
              priority = (numCorrupt > 1) ? Priority.HIGH : Priority.LOW;
            }
            // If priority is low, check if the scan of corrupt parity files found
            // the src dir to be risky.
            if (priority == Priority.LOW) {
              Path parent = new Path(p).getParent();
              String parentUriPath = parent.toUri().getPath();
              if (srcDirsToWatchOutFor.contains(parentUriPath)) {
                priority = Priority.HIGH;
              }
            }
            LostFileInfo fileInfo = fileIndex.get(p);
            if (fileInfo == null || priority.higherThan(fileInfo.getHighestPriority())) {
              fileToPriority.put(p, priority);
            }
          }
        }
      }
      return fileToPriority;
    }

    @Override
    protected void updateRaidNodeMetrics() {
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID)
          .corruptFilesHighPri.set(lastStatus.highPriorityFiles);
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID)
          .corruptFilesLowPri.set(lastStatus.lowPriorityFiles);
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID)
          .numFilesToFix.set(this.fileIndex.size());
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID)
          .numFilesToFixDropped.set(this.numFilesDropped);
      
      // Flush statistics out to the RaidNode
      incrFilesFixed(this.recentNumFilesSucceeded.get());
      incrFileFixFailures(this.recentNumFilesFailed.get());
      incrNumBlockFixSimulationFailures(this.recentNumBlockFixSimulationFailed.get());
      incrNumBlockFixSimulationSuccess(this.recentNumBlockFixSimulationSucceeded.get());
      incrFileFixReadBytesRemoteRack(this.recentNumReadBytesRemoteRack.get());
      LogUtils.incrLogMetrics(this.recentLogMetrics);

      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID).blockFixSlotSeconds.inc(this.recentSlotSeconds.get());
      this.recentNumFilesSucceeded.set(0);
      this.recentNumFilesFailed.set(0);
      this.recentSlotSeconds.set(0);
      this.recentNumBlockFixSimulationFailed.set(0);
      this.recentNumBlockFixSimulationSucceeded.set(0);
      this.recentNumReadBytesRemoteRack.set(0);
      this.recentLogMetrics.clear();
    }
  }

  public class DecommissioningWorker extends Worker {

    DecommissioningWorker() {
      super(LogFactory.getLog(DecommissioningWorker.class), 
          BlockReconstructor.DecommissioningBlockReconstructor.class, 
          "blockcopier");
    }


    /**
     * gets a list of decommissioning files from the namenode
     * and filters out files that are currently being regenerated or
     * that were recently regenerated
     */
    @Override
    protected Map<String, Integer> getLostFiles() throws IOException {
      return DistBlockIntegrityMonitor.this.getLostFiles(LIST_DECOMMISSION_FILE_PATTERN,
          new String[]{"-list-corruptfileblocks",
          "-list-decommissioningblocks",
          "-limit",
          new Integer(lostFilesLimit).toString()});
    }

    Map<String, Priority> computePriorities(
        FileSystem fs, Map<String, Integer> decommissioningFiles)
            throws IOException {

      Map<String, Priority> fileToPriority =
          new HashMap<String, Priority>(decommissioningFiles.size());

      for (String file : decommissioningFiles.keySet()) {

        // Replication == 1. Assume Reed Solomon parity exists.
        // Files with more than 4 blocks being decommissioned get a bump.
        // Otherwise, copying jobs have the lowest priority. 
        Priority priority = ((decommissioningFiles.get(file)
            > Codec.getCodec("rs").parityLength) ? 
            Priority.LOW : Priority.LOWEST);

        LostFileInfo fileInfo = fileIndex.get(file);
        if (fileInfo == null || priority.higherThan(fileInfo.getHighestPriority())) {
          fileToPriority.put(file, priority);
        }
      }
      return fileToPriority;
    }

    @Override
    protected void updateRaidNodeMetrics() {
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID).decomFilesLowPri.set(lastStatus.highPriorityFiles);
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID).decomFilesLowestPri.set(lastStatus.lowPriorityFiles);
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID).numFilesToCopy.set(fileIndex.size());

      incrFilesCopied(recentNumFilesSucceeded.get());
      incrFileCopyFailures(recentNumFilesFailed.get());
      incrNumBlockFixSimulationFailures(this.recentNumBlockFixSimulationFailed.get());
      incrNumBlockFixSimulationSuccess(this.recentNumBlockFixSimulationSucceeded.get());
      LogUtils.incrLogMetrics(this.recentLogMetrics);
      
      RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID).blockCopySlotSeconds.inc(recentSlotSeconds.get());

      // Reset temporary values now that they've been flushed
      recentNumFilesSucceeded.set(0);
      recentNumFilesFailed.set(0);
      recentSlotSeconds.set(0);
      recentNumBlockFixSimulationFailed.set(0);
      recentNumBlockFixSimulationSucceeded.set(0);
      recentLogMetrics.clear();
    }

  }


  // ---- Methods which can be overridden by tests ----

  /**
   * Gets a list of lost files from the name node via DFSck
   * 
   * @param pattern A pattern matching a single file in DFSck's output
   * @param dfsckArgs Arguments to pass to DFSck
   * @return A map of lost files' filenames to num lost blocks for that file 
   */
  protected Map<String, Integer> getLostFiles(
      Pattern pattern, String[] dfsckArgs) throws IOException {

    Map<String, Integer> lostFiles = new HashMap<String, Integer>();
    BufferedReader reader = getLostFileReader(dfsckArgs);
    String line = reader.readLine(); // remove the header line
    while ((line = reader.readLine()) != null) {
      Matcher m = pattern.matcher(line);
      if (!m.find()) {
        continue;
      }
      
      String fileName = m.group(1).trim();
      Integer numLost = lostFiles.get(fileName);
      numLost = numLost == null ? 0 : numLost;
      numLost += 1;
      lostFiles.put(fileName, numLost);
    } 
    LOG.info("FSCK returned " + lostFiles.size() + " files with args " +
        Arrays.toString(dfsckArgs));
    RaidUtils.filterTrash(getConf(), lostFiles.keySet().iterator());
    LOG.info("getLostFiles returning " + lostFiles.size() + " files with args " +
        Arrays.toString(dfsckArgs));
    return lostFiles;
  }

  private BufferedReader getLostFileReader(String[] dfsckArgs) 
      throws IOException {

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(bout, true);
    DFSck dfsck = new DFSck(getConf(), ps);
    try {
      dfsck.run(dfsckArgs);
    } catch (Exception e) {
      throw new IOException(e);
    }
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    return new BufferedReader(new InputStreamReader(bin));
  }

  public void configureJob(Job job, 
      Class<? extends BlockReconstructor> reconstructorClass) {

    ((JobConf)job.getConfiguration()).setUser(RaidNode.JOBUSER);
    ((JobConf)job.getConfiguration()).setClass(
        ReconstructionMapper.RECONSTRUCTOR_CLASS_TAG, 
        reconstructorClass,
        BlockReconstructor.class);
  }

  void submitJob(Job job, List<String> filesInJob, Priority priority, 
      Map<Job, List<LostFileInfo>> jobIndex)
          throws IOException, InterruptedException, ClassNotFoundException {
    job.submit();
    LOG.info("Job " + job.getID() + "(" + job.getJobName() +
        ") started");
    jobIndex.put(job, null);
  }

  /**
   * returns the number of map reduce jobs running
   */
  public int jobsRunning() {
    return (corruptionWorker.numJobsRunning 
        + decommissioningWorker.numJobsRunning);
  }

  static class ReconstructionInputFormat
  extends SequenceFileInputFormat<LongWritable, Text> {

    protected static final Log LOG = 
        LogFactory.getLog(ReconstructionMapper.class);

    /**
     * splits the input files into tasks handled by a single node
     * we have to read the input files to do this based on a number of 
     * items in a sequence
     */
    @Override
    public List <InputSplit> getSplits(JobContext job) 
        throws IOException {
      long filesPerTask = DistBlockIntegrityMonitor.getFilesPerTask(job.getConfiguration());

      Path[] inPaths = getInputPaths(job);

      List<InputSplit> splits = new ArrayList<InputSplit>();

      long fileCounter = 0;

      for (Path inPath: inPaths) {

        FileSystem fs = inPath.getFileSystem(job.getConfiguration());      

        if (!fs.getFileStatus(inPath).isDir()) {
          throw new IOException(inPath.toString() + " is not a directory");
        }

        FileStatus[] inFiles = fs.listStatus(inPath);

        for (FileStatus inFileStatus: inFiles) {
          Path inFile = inFileStatus.getPath();

          if (!inFileStatus.isDir() &&
              (inFile.getName().equals(job.getJobName() + IN_FILE_SUFFIX))) {

            fileCounter++;
            SequenceFile.Reader inFileReader = 
                new SequenceFile.Reader(fs, inFile, job.getConfiguration());

            long startPos = inFileReader.getPosition();
            long counter = 0;

            // create an input split every filesPerTask items in the sequence
            LongWritable key = new LongWritable();
            Text value = new Text();
            try {
              while (inFileReader.next(key, value)) {
                if (counter % filesPerTask == filesPerTask - 1L) {
                  splits.add(new FileSplit(inFile, startPos, 
                      inFileReader.getPosition() - 
                      startPos,
                      null));
                  startPos = inFileReader.getPosition();
                }
                counter++;
              }

              // create input split for remaining items if necessary
              // this includes the case where no splits were created by the loop
              if (startPos != inFileReader.getPosition()) {
                splits.add(new FileSplit(inFile, startPos,
                    inFileReader.getPosition() - startPos,
                    null));
              }
            } finally {
              inFileReader.close();
            }
          }
        }
      }

      LOG.info("created " + splits.size() + " input splits from " +
          fileCounter + " files");

      return splits;
    }

    /**
     * indicates that input file can be split
     */
    @Override
    public boolean isSplitable (JobContext job, Path file) {
      return true;
    }
  }

  /**
   * Mapper for reconstructing stripes with lost blocks
   */
  static class ReconstructionMapper
  extends Mapper<LongWritable, Text, Text, Text> {

    protected static final Log LOG = 
        LogFactory.getLog(ReconstructionMapper.class);

    public static final String RECONSTRUCTOR_CLASS_TAG =
        "hdfs.blockintegrity.reconstructor";

    private BlockReconstructor reconstructor;
    private long detectTimeInput;


    @Override
    protected void setup(Context context) 
        throws IOException, InterruptedException {

      super.setup(context);

      Configuration conf = context.getConfiguration();

      Codec.initializeCodecs(conf);

      Class<? extends BlockReconstructor> reconstructorClass = 
          context.getConfiguration().getClass(RECONSTRUCTOR_CLASS_TAG, 
                                            null, 
                                            BlockReconstructor.class);
      
      if (reconstructorClass == null) {
        LOG.error("No class supplied for reconstructor " +
                "(prop " + RECONSTRUCTOR_CLASS_TAG + ")");
        context.progress();
        return;
      }

      // We dynamically instantiate the helper based on the helperClass member
      try {
        Constructor<? extends BlockReconstructor> ctor =
            reconstructorClass.getConstructor(new Class[]{Configuration.class});

        reconstructor = ctor.newInstance(conf);

      } catch (Exception ex) {
        throw new IOException("Could not instantiate a block reconstructor " +
                          "based on class " + reconstructorClass, ex);
      }
      
      detectTimeInput = Long.parseLong(conf.get("corrupt_detect_time"));
    }

    /**
     * Reconstruct a stripe
     */
    @Override
    public void map(LongWritable key, Text fileText, Context context)
      throws IOException, InterruptedException {

      String fileStr = fileText.toString();
      LOG.info("reconstructing " + fileStr);
      Path file = new Path(fileStr);
      long waitTime = System.currentTimeMillis() - detectTimeInput;
      FileSystem fs = file.getFileSystem(context.getConfiguration());
      LogUtils.logWaitTimeMetrics(waitTime, getMaxPendingJobs(
          context.getConfiguration()), 
          getFilesPerTask(context.getConfiguration()),
          LOGTYPES.FILE_FIX_WAITTIME,
          fs,
          context);

      try {
        boolean reconstructed = reconstructor.reconstructFile(file, context);
        if (reconstructed) {
          context.getCounter(RaidCounter.FILES_SUCCEEDED).increment(1L);
          LogUtils.logRaidReconstructionMetrics(LOGRESULTS.SUCCESS, 0, null,
              file, -1, LOGTYPES.OFFLINE_RECONSTRUCTION_FILE, 
              fs, null, context);
        } else {
          context.getCounter(RaidCounter.FILES_NOACTION).increment(1L);
        }
      } catch (Exception e) {
        LOG.error("Reconstructing file " + file + " failed", e);
        LogUtils.logRaidReconstructionMetrics(LOGRESULTS.FAILURE, 0, null,
            file, -1, LOGTYPES.OFFLINE_RECONSTRUCTION_FILE, 
            fs, e, context);
        // report file as failed
        context.getCounter(RaidCounter.FILES_FAILED).increment(1L);
        String outkey = DistBlockIntegrityMonitor.FAILED_FILE + "," + fileStr;
        String outval = context.getConfiguration().get("mapred.task.id");
        context.write(new Text(outkey), new Text(outval));
      }
      context.progress();
    }
  }

  /**
   * Get the status of the entire block integrity monitor.
   * The status returned represents the aggregation of the statuses of all the 
   * integrity monitor's components.
   * 
   * @return The status of the block integrity monitor 
   */
  @Override
  public BlockIntegrityMonitor.Status getAggregateStatus() {
    Status fixer = corruptionWorker.getStatus();
    Status copier = decommissioningWorker.getStatus();

    List<JobStatus> jobs = new ArrayList<JobStatus>();
    List<JobStatus> simFailedJobs = new ArrayList<JobStatus>();
    List<JobStatus> failedJobs = new ArrayList<JobStatus>();
    List<String> highPriFileNames = new ArrayList<String>();
    int numHighPriFiles = 0;
    int numLowPriFiles = 0;
    int numLowestPriFiles = 0;
    if (fixer != null) {
      jobs.addAll(fixer.jobs);
      simFailedJobs.addAll(fixer.simFailJobs);
      failedJobs.addAll(fixer.failJobs);
      if (fixer.highPriorityFileNames != null) {
        highPriFileNames.addAll(fixer.highPriorityFileNames);
      }
      numHighPriFiles += fixer.highPriorityFiles;
      numLowPriFiles += fixer.lowPriorityFiles;
      numLowestPriFiles += fixer.lowestPriorityFiles;
    }
    if (copier != null) {
      jobs.addAll(copier.jobs);
      simFailedJobs.addAll(copier.simFailJobs);
      failedJobs.addAll(copier.failJobs);
      if (copier.highPriorityFileNames != null) {
        highPriFileNames.addAll(copier.highPriorityFileNames);
      }
      numHighPriFiles += copier.highPriorityFiles;
      numLowPriFiles += copier.lowPriorityFiles;
      numLowestPriFiles += copier.lowestPriorityFiles;
    }

    return new Status(numHighPriFiles, numLowPriFiles, numLowestPriFiles,
                      jobs, highPriFileNames,failedJobs, simFailedJobs);
  }
  
  public Worker getCorruptionMonitor() {
    return this.corruptionWorker;
  }

  @Override
  public Worker getDecommissioningMonitor() {
    return this.decommissioningWorker;
  }

  @Override
  public Runnable getCorruptFileCounter() {
    return this.corruptFileCounterWorker;
  }
}
