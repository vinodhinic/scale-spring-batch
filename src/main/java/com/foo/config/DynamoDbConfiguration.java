package com.foo.config;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
public class DynamoDbConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDbConfiguration.class);

    @Bean
    public AmazonDynamoDB dynamoDB(
            @Value("${dynamodb.accessKey}") String accessKey,
            @Value("${dynamodb.secretKey}") String secretKey,
            @Value("${dynamodb.region}") String region) {

        AWSCredentials basicAWSCredentials =
                new BasicAWSCredentials(accessKey, secretKey);

        return AmazonDynamoDBClientBuilder.standard()
                .withRegion(region)
                .withCredentials(new AWSStaticCredentialsProvider(basicAWSCredentials))
                .build();
    }

    public static final Long DYNAMO_DB_LEASE_DURATION_IN_SECONDS = 10L;

    @Bean
    public AmazonDynamoDBLockClient amazonDynamoDBLockClient(
            AmazonDynamoDB dynamoDB, @Value("${dynamodb.tablename}") String tableName,
            @Value("${dynamodb.partitionKey}") String partitionKey) {

        String ownerName;
        try {
            ownerName = Inet4Address.getLocalHost().getHostName() + UUID.randomUUID().toString();
        } catch (final UnknownHostException e) {
            ownerName = UUID.randomUUID().toString();
        }
        LOGGER.info("OwnerName : {}", ownerName);
        AmazonDynamoDBLockClientOptions.AmazonDynamoDBLockClientOptionsBuilder builder =
                AmazonDynamoDBLockClientOptions.builder(dynamoDB, tableName)
                        .withPartitionKeyName(partitionKey)
                        .withTimeUnit(TimeUnit.SECONDS)
                        .withLeaseDuration(DYNAMO_DB_LEASE_DURATION_IN_SECONDS)
                        .withHeartbeatPeriod(3L)
                        .withCreateHeartbeatBackgroundThread(true)
                        .withOwnerName(ownerName);

        return new AmazonDynamoDBLockClient(builder.build());
    }
}