package com.example.bysjdesign.job;

import com.example.bysjdesign.service.IncrementalSyncService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class ProfileUpdateTask implements Job {

    private static final Logger logger = LoggerFactory.getLogger(ProfileUpdateTask.class);

    private final IncrementalSyncService incrementalSyncService;

    public ProfileUpdateTask(IncrementalSyncService incrementalSyncService) {
        this.incrementalSyncService = incrementalSyncService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        logger.info("========== 开始执行画像增量任务 ==========");
        try {
            incrementalSyncService.runProfileIncremental();
            logger.info("========== 画像增量任务执行完成 ==========");
        } catch (Exception ex) {
            logger.error("画像增量任务执行失败", ex);
            throw new JobExecutionException("画像增量任务执行失败", ex);
        }
    }
}
