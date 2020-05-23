package com.foo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.foo.config.DynamoDbConfiguration.DYNAMO_DB_LEASE_DURATION_IN_SECONDS;

/**
 * Runnable that starts the next instance of the job that is assigned for.
 */
public class JobSyncRunnable implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobSyncRunnable.class);

    private final String jobName;
    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final DynamoDBJobCoordinator dynamoDBJobCoordinator;

    public JobSyncRunnable(String jobName,
                           JobExplorer jobExplorer,
                           JobOperator jobOperator,
                           JobRepository jobRepository,
                           DynamoDBJobCoordinator dynamoDBJobCoordinator) {
        this.jobName = jobName;
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.dynamoDBJobCoordinator = dynamoDBJobCoordinator;
        checkForAbandonedJobs();
    }

    // TODO: How about 2 kinds of locks? one to assign jobs to instances and another to acquire before each run for that job within that instance?
    //  Then we need to ensure trade-job-owner-lock and trade-run-lock are possessed by the same owner. Nah.
    // TODO: How about tracking the owner at the job execution? that way, when you are checking isLastJobExecutionStillRunning, if the last job execution is triggered by this.owner, it is okay to wait.
    //  else assume that it is abandoned and cleanUpAbandonedJobs() -> I am leaning towards this approach since it is cleaner than the one implemented here.
    @Override
    public void run() {
        try {
            boolean canContinue = dynamoDBJobCoordinator.canContinue(jobName);
            if (canContinue) {
                LOGGER.info("Lock for {} is still alive. Proceeding with run..", jobName);
                if (isLastJobExecutionStillRunning(jobName)) {
                    LOGGER.info("Last execution is still not done. Quitting");
                    return;
                }
                Long jobExecutionId = this.jobOperator.startNextInstance(jobName);
                LOGGER.info("Triggered {} for Job : {}", jobExecutionId, jobName);
            } else {
                LOGGER.error("Job: {} cannot continue since lock is expired. This runnable is triggered only after acquiring the lock. " +
                        "With the heart beat for lock renewal at the background, this scenario ideally shouldn't occur. Trying to register {} again..", jobName, jobName);

                boolean registerJob = dynamoDBJobCoordinator.registerJob(jobName);
                if (!registerJob) {
                    LOGGER.info("Couldn't register the job {} again", jobName);
                } else {
                    checkForAbandonedJobs();
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private JobInstance getTheLastJobInstance(String job) {
        // this returns the job instances in decreasing order of created time. this way, we are asking
        // for the last instance triggered. that's why we don't retry any job.
        List<JobInstance> previousInstances = jobExplorer.findJobInstancesByJobName(job, 0, 1);
        if (previousInstances.isEmpty()) {
            return null;
        }
        return previousInstances.get(0);
    }

    private boolean isLastJobExecutionStillRunning(String jobName) {
        JobInstance jobInstance = getTheLastJobInstance(jobName);
        if (jobInstance == null) {
            return false;
        }
        List<JobExecution> jobExecutions = jobExplorer.getJobExecutions(jobInstance);
        return jobExecutions.stream().anyMatch(JobExecution::isRunning);
    }

    private void checkForAbandonedJobs() {
        long secondsWaited = 0;
        long gracePeriodInSeconds = DYNAMO_DB_LEASE_DURATION_IN_SECONDS * 2;
        while (isLastJobExecutionStillRunning(jobName) && secondsWaited < gracePeriodInSeconds) {
            LOGGER.error(
                    "Some instance is still running the job even while not possessing the lock for {}. Will give grace period {} seconds",
                    jobName,
                    gracePeriodInSeconds);
            secondsWaited += 2;
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (isLastJobExecutionStillRunning(jobName)) {
            LOGGER.error("Grace period exceeded. Abandoning the current runs");
            cleanUpAbandonedJobs();
        }
    }

    private void cleanUpAbandonedJobs() {
        LOGGER.debug("Cleaning up abandoned job for {}", jobName);

        Set<JobExecution> jobExecutions = jobExplorer.findRunningJobExecutions(this.jobName);
        LOGGER.info("Found {} running executions ", jobExecutions.size());
        for (JobExecution jobExecution : jobExecutions) {
            Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
            for (StepExecution stepExecution : stepExecutions) {
                BatchStatus status = stepExecution.getStatus();
                if (status.isRunning() || status == BatchStatus.STOPPING) {
                    stepExecution.setStatus(BatchStatus.STOPPED);
                    stepExecution.setExitStatus(ExitStatus.STOPPED);
                    stepExecution.setEndTime(new Date());
                    jobRepository.update(stepExecution);
                }
            }

            jobExecution.setStatus(BatchStatus.STOPPED);
            jobExecution.setExitStatus(ExitStatus.STOPPED);
            jobExecution.setEndTime(new Date());
            jobRepository.update(jobExecution);
            Long jobExecutionId = jobExecution.getId();
            LOGGER.info(
                    "Marking ExitCode and BatchStatus as STOPPED for JobExecutionId : {}", jobExecutionId);
        }
    }
}
