package com.example.bysjdesign.job;

import com.example.bysjdesign.service.IncrementalSyncService;
import com.example.bysjdesign.service.WarningService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class WarningCheckTask implements Job {

    private static final Logger logger = LoggerFactory.getLogger(WarningCheckTask.class);

    private final IncrementalSyncService incrementalSyncService;
    private final WarningService warningService;

    public WarningCheckTask(IncrementalSyncService incrementalSyncService,
                            WarningService warningService) {
        this.incrementalSyncService = incrementalSyncService;
        this.warningService = warningService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        logger.info("========== 开始执行预警增量任务 ==========");
        long startTime = System.currentTimeMillis();

        try {
            incrementalSyncService.runWarningIncremental();
            warningService.validateDataConsistency();
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.info("========== 预警增量任务执行完成，耗时: {}ms ==========", totalDuration);
        } catch (Exception ex) {
            logger.error("预警增量任务执行失败", ex);
            throw new JobExecutionException("预警增量任务执行失败", ex);
        }
    }
}
