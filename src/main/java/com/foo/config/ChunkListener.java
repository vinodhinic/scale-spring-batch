package com.foo.config;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ChunkListener implements org.springframework.batch.core.ChunkListener {

    @Autowired
    private DynamoDBJobCoordinator dynamoDBJobCoordinator;

    @Override
    public void beforeChunk(ChunkContext context) {
        String jobName = context.getStepContext().getJobName();
        boolean canContinue = dynamoDBJobCoordinator.canContinue(jobName);
        if (!canContinue) {
            throw new IllegalStateException("Lock expired for " + jobName);
        }
    }

    @Override
    public void afterChunk(ChunkContext context) {

    }

    @Override
    public void afterChunkError(ChunkContext context) {

    }
}
