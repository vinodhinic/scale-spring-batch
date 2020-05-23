package com.foo;

import com.foo.config.SyncScheduler;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableBatchProcessing
@EnableScheduling
public class ScalePocApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = SpringApplication.run(ScalePocApplication.class, args);
        SyncScheduler syncScheduler = applicationContext.getBean(SyncScheduler.class);
        syncScheduler.initialize();
    }
}
