package com.foo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
public class SyncScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncScheduler.class);

    @Value("${tokens}")
    private Integer tokens;

    @Autowired
    private BatchConfiguration batchConfiguration;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private DynamoDBJobCoordinator dynamoDBJobCoordinator;

    @Autowired
    @Qualifier("jobSyncRunnableBeanFactory")
    private Function<String, JobSyncRunnable> jobSyncRunnableBeanFactory;

    public void initialize() {
        List<String> allJobs = new ArrayList<>(BatchConfiguration.jobs);
        List<String> acquiredLocks = acquireLocks(allJobs);
        int count = 1;
        LOGGER.info("With {} try, acquired {} locks for {} ", count, acquiredLocks.size(), acquiredLocks);

        // We need to have rolling updates, else this whole concept of acquiring locks at start-up will never work. That too, maxSurge should be always set to 0
        if(acquiredLocks.size() != tokens) {
            while (acquiredLocks.size() < tokens && count < 3) {
                allJobs.removeAll(acquiredLocks);
                if(allJobs.isEmpty()) {
                    break;
                }
                LOGGER.info("{} tokens free. Trying to acquire more. Try : {}", tokens - acquiredLocks.size(), count);
                List<String> acquiredLocksThisTry = acquireLocks(allJobs);
                tokens+= acquiredLocksThisTry.size();
                acquiredLocks.addAll(acquiredLocksThisTry);
                LOGGER.info("With {} try, acquired {} more locks for {}. Total : {}", count,
                        acquiredLocksThisTry.size(), acquiredLocksThisTry, acquiredLocks);
                count++;
            }
        }

        // At this point, we would have more free tokens but that's okay.
        for(String job : acquiredLocks) {
            taskScheduler.scheduleWithFixedDelay(jobSyncRunnableBeanFactory.apply(job),
                    // the schedule can be configured per job too
                    Duration.ofSeconds(30L));
        }
    }

    private List<String> acquireLocks(List<String> jobsToAcquireLockFor){
        List<String> locksAcquired = new ArrayList<>();
        int usedTokens = 0;
        for (String job : jobsToAcquireLockFor) {
            if(usedTokens == tokens) {
                break;
            }
            boolean isLockAcquired = dynamoDBJobCoordinator.registerJob(job);
            if(isLockAcquired) {
                locksAcquired.add(job);
                usedTokens++;
            }
        }
        return locksAcquired;
    }

}
