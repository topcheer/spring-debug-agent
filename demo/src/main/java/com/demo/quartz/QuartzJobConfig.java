package com.demo.quartz;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzJobConfig {
    private static final Logger log = LoggerFactory.getLogger(QuartzJobConfig.class);

    @Bean
    public JobDetail cleanupJobDetail() {
        return JobBuilder.newJob(CleanupJob.class)
                .withIdentity("cleanupJob", "maintenance")
                .storeDurably()
                .requestRecovery()
                .build();
    }

    @Bean
    public Trigger cleanupTrigger(JobDetail cleanupJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(cleanupJobDetail)
                .withIdentity("cleanupTrigger", "maintenance")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 */5 * * * ?"))
                .build();
    }

    @Bean
    public JobDetail reportJobDetail() {
        return JobBuilder.newJob(ReportJob.class)
                .withIdentity("dailyReportJob", "reports")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger reportTrigger(JobDetail reportJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(reportJobDetail)
                .withIdentity("dailyReportTrigger", "reports")
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(2, 0))
                .build();
    }

    public static class CleanupJob implements Job {
        @Override
        public void execute(org.quartz.JobExecutionContext context) {
            log.info("Quartz: running cleanup job");
        }
    }

    public static class ReportJob implements Job {
        @Override
        public void execute(org.quartz.JobExecutionContext context) {
            log.info("Quartz: generating daily report");
        }
    }
}
