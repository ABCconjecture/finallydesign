package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.TaskExecutionState;
import com.example.bysjdesign.repository.AccessLogRepository;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.BorrowLogRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.NetworkLogRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IncrementalSyncService {

    public static final String TASK_ANALYSIS = "analysis_incremental";
    public static final String TASK_PROFILE = "profile_incremental";
    public static final String TASK_WARNING = "warning_incremental";

    private static final Logger logger = LoggerFactory.getLogger(IncrementalSyncService.class);

    private final TaskExecutionStateService taskExecutionStateService;
    private final CampusUserRepository campusUserRepository;
    private final AccessLogRepository accessLogRepository;
    private final BorrowLogRepository borrowLogRepository;
    private final NetworkLogRepository networkLogRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final UserProfileRepository userProfileRepository;
    private final MultiDimensionalAnalysisService multiDimensionalAnalysisService;
    private final UserProfileService userProfileService;
    private final WarningService warningService;

    public IncrementalSyncService(TaskExecutionStateService taskExecutionStateService,
                                  CampusUserRepository campusUserRepository,
                                  AccessLogRepository accessLogRepository,
                                  BorrowLogRepository borrowLogRepository,
                                  NetworkLogRepository networkLogRepository,
                                  AnalysisDataRepository analysisDataRepository,
                                  UserProfileRepository userProfileRepository,
                                  MultiDimensionalAnalysisService multiDimensionalAnalysisService,
                                  UserProfileService userProfileService,
                                  WarningService warningService) {
        this.taskExecutionStateService = taskExecutionStateService;
        this.campusUserRepository = campusUserRepository;
        this.accessLogRepository = accessLogRepository;
        this.borrowLogRepository = borrowLogRepository;
        this.networkLogRepository = networkLogRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.userProfileRepository = userProfileRepository;
        this.multiDimensionalAnalysisService = multiDimensionalAnalysisService;
        this.userProfileService = userProfileService;
        this.warningService = warningService;
    }

    public TaskExecutionState runAnalysisIncremental() {
        return executeTask(TASK_ANALYSIS, "Analysis Incremental Sync", () -> {
            LocalDateTime lastCompletedTime = taskExecutionStateService.getLastCompletedTime(TASK_ANALYSIS);
            Set<Integer> impactedUserIds = resolveImpactedUserIds(lastCompletedTime);
            if (impactedUserIds.isEmpty()) {
                return TaskResult.skipped("No new access, borrowing, or network logs were detected.");
            }

            int processedCount = multiDimensionalAnalysisService.analyzeUsers(impactedUserIds, false).size();
            return TaskResult.success(processedCount,
                    String.format("Incremental analysis refreshed %d users.", processedCount));
        });
    }

    public TaskExecutionState runProfileIncremental() {
        return executeTask(TASK_PROFILE, "Profile Incremental Sync", () -> {
            LocalDateTime lastCompletedTime = taskExecutionStateService.getLastCompletedTime(TASK_PROFILE);
            if (lastCompletedTime == null) {
                userProfileService.generateUserProfiles(true);
                int processedCount = Math.toIntExact(campusUserRepository.count());
                return TaskResult.success(processedCount,
                        String.format("Initial clustering and profile generation completed for %d users.", processedCount));
            }

            List<Integer> impactedUserIds = analysisDataRepository.findDistinctUserIdsByUpdateTimeAfter(lastCompletedTime);
            if (impactedUserIds.isEmpty()) {
                return TaskResult.skipped("No analysis snapshots changed after the last profile sync.");
            }

            userProfileService.generateUserProfiles(true);
            return TaskResult.success(impactedUserIds.size(),
                    String.format("Profile clustering recalculated after %d users changed.", impactedUserIds.size()));
        });
    }

    public TaskExecutionState runWarningIncremental() {
        return executeTask(TASK_WARNING, "Warning Incremental Sync", () -> {
            LocalDateTime lastCompletedTime = taskExecutionStateService.getLastCompletedTime(TASK_WARNING);
            if (lastCompletedTime == null) {
                int processedCount = warningService.checkAll();
                return TaskResult.success(processedCount,
                        String.format("Initial warning synchronization completed for %d users.", processedCount));
            }

            Set<Integer> impactedUserIds = new LinkedHashSet<>();
            impactedUserIds.addAll(analysisDataRepository.findDistinctUserIdsByUpdateTimeAfter(lastCompletedTime));
            impactedUserIds.addAll(userProfileRepository.findDistinctUserIdsByUpdateTimeAfter(toDate(lastCompletedTime)));
            impactedUserIds.remove(null);

            if (impactedUserIds.isEmpty()) {
                return TaskResult.skipped("No analysis or profile changes require warning recalculation.");
            }

            int processedCount = warningService.syncWarningsForUsers(impactedUserIds);
            return TaskResult.success(processedCount,
                    String.format("Warning states synchronized for %d users.", processedCount));
        });
    }

    private Set<Integer> resolveImpactedUserIds(LocalDateTime lastCompletedTime) {
        if (lastCompletedTime == null) {
            return campusUserRepository.findAll().stream()
                    .map(user -> user.getUserId())
                    .filter(userId -> userId != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<Integer> impactedUserIds = new LinkedHashSet<>();
        Date since = toDate(lastCompletedTime);
        impactedUserIds.addAll(accessLogRepository.findDistinctUserIdsByEntryTimeAfter(since));
        impactedUserIds.addAll(networkLogRepository.findDistinctUserIdsBySessionStartAfter(since));
        impactedUserIds.addAll(borrowLogRepository.findDistinctUserIdsByBorrowDateAfter(since));
        impactedUserIds.remove(null);
        return impactedUserIds;
    }

    private TaskExecutionState executeTask(String taskKey, String taskName, TaskAction action) {
        taskExecutionStateService.markRunning(taskKey, taskName);
        try {
            TaskResult result = action.run();
            if (result.skipped()) {
                logger.info("{}: {}", taskName, result.message());
                return taskExecutionStateService.markSkipped(taskKey, taskName, result.message());
            }

            logger.info("{}: {}", taskName, result.message());
            return taskExecutionStateService.markSuccess(taskKey, taskName, result.processedCount(), result.message());
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? taskName + " failed."
                    : ex.getMessage();
            taskExecutionStateService.markFailed(taskKey, taskName, message, ex);
            throw ex;
        }
    }

    private Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }

    @FunctionalInterface
    private interface TaskAction {
        TaskResult run();
    }

    private record TaskResult(boolean skipped, int processedCount, String message) {
        private static TaskResult success(int processedCount, String message) {
            return new TaskResult(false, processedCount, message);
        }

        private static TaskResult skipped(String message) {
            return new TaskResult(true, 0, message);
        }
    }
}
