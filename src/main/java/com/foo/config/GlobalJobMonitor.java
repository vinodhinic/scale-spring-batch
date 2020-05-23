package com.foo.config;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GlobalJobMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalJobMonitor.class);

    @Autowired
    private AmazonDynamoDBLockClient client;

    public void run() {
        LOGGER.info("monitoring job is running");
        /**
         * I played around with the dynamo client to see if there is any way to read the locks from dynamo table.
         * Yes there is. But that does not tell you whether they are held by someother instance.
         * The only way you can achieve this, is by created a "monitoringClient" which runs with "ownerName_monitor" and no heart beats.
         */
    }
}
