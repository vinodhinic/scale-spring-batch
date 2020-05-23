package com.foo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.DefaultBatchConfigurer;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.task.TaskSchedulerCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Function;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

@Configuration("batchConfiguration")
public class BatchConfiguration extends DefaultBatchConfigurer implements ApplicationContextAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchConfiguration.class);
    private static final String MONITORING_JOB = "monitoring-job";
    private static final String PUBLISHER_JOB = "publisher-job";
    private static final String TRADE_JOB = "trade-job";
    private static final String PRICE_JOB = "price-job";
    public static final List<String> jobs = List.of(MONITORING_JOB, PUBLISHER_JOB, TRADE_JOB, PRICE_JOB);

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private DynamoDBJobCoordinator dynamoDBJobCoordinator;

    private ApplicationContext applicationContext;

    @Autowired
    private ChunkListener chunkListener;

    @Bean // to register the job into the registry
    public JobRegistryBeanPostProcessor jobRegistryBeanPostProcessor() throws Exception {
        JobRegistryBeanPostProcessor jobRegistryBeanPostProcessor = new JobRegistryBeanPostProcessor();
        jobRegistryBeanPostProcessor.setJobRegistry(this.jobRegistry);
        jobRegistryBeanPostProcessor.setBeanFactory(this.applicationContext.getAutowireCapableBeanFactory());
        jobRegistryBeanPostProcessor.afterPropertiesSet();
        System.out.println("job registry initialized");
        return jobRegistryBeanPostProcessor;
    }

    @Bean // this job operator is needed in order to handle restarts and all
    public JobOperator jobOperator() throws Exception {
        SimpleJobOperator simpleJobOperator = new SimpleJobOperator();

        simpleJobOperator.setJobLauncher(this.jobLauncher);
        simpleJobOperator.setJobParametersConverter(new DefaultJobParametersConverter());
        simpleJobOperator.setJobRepository(this.jobRepository);
        simpleJobOperator.setJobExplorer(this.jobExplorer);
        simpleJobOperator.setJobRegistry(this.jobRegistry);

        simpleJobOperator.afterPropertiesSet();
        return simpleJobOperator;
    }

    @Override
    public JobLauncher getJobLauncher() {
        SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
        jobLauncher.setJobRepository(this.jobRepository);
        jobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("EXEC-JL-"));
        try {
            jobLauncher.afterPropertiesSet();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return jobLauncher;
    }

    @Autowired
    private DataSource dataSource;

    @Override
    protected JobRepository createJobRepository() throws Exception {
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(getTransactionManager());
        // https://github.com/spring-projects/spring-batch/issues/1127
        factory.setIsolationLevelForCreate("ISOLATION_READ_UNCOMMITTED");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Bean
    public TaskSchedulerCustomizer taskSchedulerCustomizer() {
        return taskScheduler -> {
            taskScheduler.setThreadNamePrefix("SYNC_SCHEDULER");
            taskScheduler.setPoolSize(20);
            taskScheduler.setAwaitTerminationSeconds(210);
            taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        };
    }

    @Bean("jobSyncRunnableBeanFactory")
    public Function<String, JobSyncRunnable> jobSyncRunnableBeanFactory() {
        return this::jobSyncRunnable;
    }

    @Bean
    @Scope(SCOPE_PROTOTYPE)
    public JobSyncRunnable jobSyncRunnable(String name) {
        return new JobSyncRunnable(name, jobExplorer, jobOperator, jobRepository, dynamoDBJobCoordinator);
    }

    @Bean("tradeJob")
    public Job tradeJob() {
        return jobBuilderFactory.get(TRADE_JOB)
                .incrementer(new RunIdIncrementer())
                .start(stepBuilderFactory.get("trade-ETL")
                        .tasklet((contribution, chunkContext) -> {
                            LOGGER.info("Trades are pulled from source and staged in postgres");
                            return RepeatStatus.FINISHED;
                        }).listener(chunkListener)
                        .build()
                ).build();
    }

    @Bean("priceJob")
    public Job priceJob() {
        return jobBuilderFactory.get(PRICE_JOB)
                .incrementer(new RunIdIncrementer())
                .start(stepBuilderFactory.get("price-ETL")
                        .tasklet((contribution, chunkContext) -> {
                            LOGGER.info("Prices are pulled from source and staged in postgres");
                            return RepeatStatus.FINISHED;
                        }).listener(chunkListener)
                        .build()
                ).build();
    }

    @Bean("publisherJob")
    public Job publisherJob() {
        return jobBuilderFactory.get(PUBLISHER_JOB)
                .incrementer(new RunIdIncrementer())
                .start(stepBuilderFactory.get("publisher-step")
                        .tasklet((contribution, chunkContext) -> {
                            LOGGER.info("Publisher is loading from postgres and publishing the messages to the sink");
                            return RepeatStatus.FINISHED;
                        }).listener(chunkListener)
                        .build()
                ).build();
    }

    @Autowired
    private GlobalJobMonitor globalJobMonitor;

    @Bean("monitoringJob")
    public Job monitoringJob() {
        return jobBuilderFactory.get(MONITORING_JOB)
                .incrementer(new RunIdIncrementer())
                .start(stepBuilderFactory.get("monitoring-ETL")
                        .tasklet((contribution, chunkContext) -> {
                            globalJobMonitor.run();
                            return RepeatStatus.FINISHED;
                        }).listener(chunkListener)
                        .build()
                ).build();
    }
}
