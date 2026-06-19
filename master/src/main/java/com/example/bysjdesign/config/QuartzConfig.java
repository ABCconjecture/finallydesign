package com.example.bysjdesign.config;

import com.example.bysjdesign.job.AnalysisUpdateTask;
import com.example.bysjdesign.job.ProfileUpdateTask;
import com.example.bysjdesign.job.WarningCheckTask;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

    public static final String ANALYSIS_JOB_NAME = "analysisUpdateJob";
    public static final String ANALYSIS_TRIGGER_NAME = "analysisUpdateTrigger";
    public static final String PROFILE_JOB_NAME = "profileUpdateJob";
    public static final String PROFILE_TRIGGER_NAME = "profileUpdateTrigger";
    public static final String WARNING_JOB_NAME = "warningCheckJob";
    public static final String WARNING_TRIGGER_NAME = "warningCheckTrigger";

    @Value("${app.quartz.analysis.cron:0 0 * * * ?}")
    private String analysisCron;

    @Value("${app.quartz.profile.cron:0 5 * * * ?}")
    private String profileCron;

    @Value("${app.quartz.warning.cron:0 10 * * * ?}")
    private String warningCron;

    @Bean
    public JobDetail analysisUpdateJobDetail() {
        return JobBuilder.newJob(AnalysisUpdateTask.class)
                .withIdentity(ANALYSIS_JOB_NAME)
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger analysisUpdateTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(analysisUpdateJobDetail())
                .withIdentity(ANALYSIS_TRIGGER_NAME)
                .withSchedule(CronScheduleBuilder.cronSchedule(analysisCron)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    @Bean
    public JobDetail profileUpdateJobDetail() {
        return JobBuilder.newJob(ProfileUpdateTask.class)
                .withIdentity(PROFILE_JOB_NAME)
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger profileUpdateTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(profileUpdateJobDetail())
                .withIdentity(PROFILE_TRIGGER_NAME)
                .withSchedule(CronScheduleBuilder.cronSchedule(profileCron)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    @Bean
    public JobDetail warningCheckJobDetail() {
        return JobBuilder.newJob(WarningCheckTask.class)
                .withIdentity(WARNING_JOB_NAME)
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger warningCheckTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(warningCheckJobDetail())
                .withIdentity(WARNING_TRIGGER_NAME)
                .withSchedule(CronScheduleBuilder.cronSchedule(warningCron)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}