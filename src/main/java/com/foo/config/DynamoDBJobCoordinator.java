package com.foo.config;

import com.amazonaws.services.dynamodbv2.AcquireLockOptions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient;
import com.amazonaws.services.dynamodbv2.LockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamoDBJobCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDBJobCoordinator.class);

    @Autowired
    private AmazonDynamoDBLockClient client;

    private final ConcurrentHashMap<String, LockItem> jobNameToHeldLockMap =
            new ConcurrentHashMap<>();

    public boolean registerJob(String jobName) {
        Optional<LockItem> lockItemOptional;
        try {
            lockItemOptional = client.tryAcquireLock(AcquireLockOptions.builder(jobName).build());
        } catch (InterruptedException e) {
            LOGGER.error("Error while trying to acquire lock {} ", jobName, e);
            return false;
        }

        if (lockItemOptional.isPresent()) {
            LockItem lockItem = lockItemOptional.get();
            LOGGER.info(
                    "Lock acquired for {} with RecordVersionNumber {}",
                    jobName,
                    lockItem.getRecordVersionNumber());
            jobNameToHeldLockMap.put(jobName, lockItem);
            return true;
        }
        LOGGER.info("Unable to acquire lock for {}", jobName);
        return false;
    }

    public boolean canContinue(String jobName) {

        if (jobNameToHeldLockMap.containsKey(jobName)) {
            LockItem lockItem = jobNameToHeldLockMap.get(jobName);
            if (lockItem.isExpired()) {
                // Intentionally not removing the expired lock. They will be overridden when new lock is
                // acquired for the same jobName.
                // that way you are avoiding concurrent access.
                LOGGER.error(
                        "Lock with lockName {} expired for {} Lock's RecordVersionNumber : {}",
                        lockItem.getPartitionKey(),
                        jobName,
                        lockItem.getRecordVersionNumber());
                return false;
            } else {
                LOGGER.debug(
                        "Lock with lockName {} still alive for {}. Lock's RecordVersionNumber : {}. Can continue",
                        lockItem.getPartitionKey(),
                        jobName,
                        lockItem.getRecordVersionNumber());
                return true;
            }
        } else {
            LOGGER.error("Lock is not present for {}", jobName);
            return false;
        }

    }

    public void unregisterJob(String jobName) {
        LockItem lockItem = jobNameToHeldLockMap.remove(jobName);
        if (lockItem == null) {
            LOGGER.warn(
                    "LockItem was null for {}. Someone unregistered the Job before this call, which should never happen",
                    jobName);
        } else {
            boolean isReleased = client.releaseLock(lockItem);
            if (!isReleased) {
                LOGGER.error(
                        "Unable to release lock {} for Job : {} with recordVersion {}",
                        lockItem.getPartitionKey(),
                        jobName,
                        lockItem.getRecordVersionNumber());
            } else {
                LOGGER.info(
                        "Released lock {} for Job :{} with recordVersion {}",
                        lockItem.getPartitionKey(),
                        jobName,
                        lockItem.getRecordVersionNumber());
            }
        }
    }

    @PreDestroy
    public void cleanup() throws IOException {
        LOGGER.info("Cleaning up locks held");
        this.client.close();
    }
}
