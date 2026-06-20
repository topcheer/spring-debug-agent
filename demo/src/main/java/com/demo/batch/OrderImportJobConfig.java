package com.demo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class OrderImportJobConfig {
    private static final Logger log = LoggerFactory.getLogger(OrderImportJobConfig.class);

    @Bean
    public Job importOrderJob(JobRepository jobRepository, Step importStep) {
        return new JobBuilder("importOrderJob", jobRepository)
                .start(importStep)
                .build();
    }

    @Bean
    public Step importStep(JobRepository jobRepository, Tasklet importTasklet,
                           PlatformTransactionManager transactionManager) {
        return new StepBuilder("importStep", jobRepository)
                .tasklet(importTasklet, transactionManager)
                .build();
    }

    @Bean
    public Tasklet importTasklet() {
        return (contribution, chunkContext) -> {
            log.info("Executing import order tasklet: simulating data import...");
            chunkContext.getStepContext().getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .put("importedCount", 42);
            return RepeatStatus.FINISHED;
        };
    }
}
