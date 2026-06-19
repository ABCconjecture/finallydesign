package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.TaskExecutionState;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.TaskExecutionStateRepository;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class ManualFullTaskService {

    public static final String TASK_ANALYSIS_FULL = "analysis_full_manual";
    public static final String TASK_PROFILE_FULL = "profile_full_manual";
    public static final String TASK_WARNING_FULL = "warning_full_manual";

    private final MultiDimensionalAnalysisService multiDimensionalAnalysisService;
    private final UserProfileService userProfileService;
    private final WarningService warningService;
    private final CampusUserRepository campusUserRepository;
    private final TaskExecutionStateRepository taskExecutionStateRepository;
    private final TaskExecutionStateService taskExecutionStateService;
    private final TaskOperationAuditService taskOperationAuditService;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "manual-full-task-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<String> activeTaskKey = new AtomicReference<>();
    private final Map<String, RuntimeTaskState> runtimeStateMap = new ConcurrentHashMap<>();

    public ManualFullTaskService(MultiDimensionalAnalysisService multiDimensionalAnalysisService,
                                 UserProfileService userProfileService,
                                 WarningService warningService,
                                 CampusUserRepository campusUserRepository,
                                 TaskExecutionStateRepository taskExecutionStateRepository,
                                 TaskExecutionStateService taskExecutionStateService,
                                 TaskOperationAuditService taskOperationAuditService) {
        this.multiDimensionalAnalysisService = multiDimensionalAnalysisService;
        this.userProfileService = userProfileService;
        this.warningService = warningService;
        this.campusUserRepository = campusUserRepository;
        this.taskExecutionStateRepository = taskExecutionStateRepository;
        this.taskExecutionStateService = taskExecutionStateService;
        this.taskOperationAuditService = taskOperationAuditService;
    }

    public Map<String, Object> submitAnalysisTask(SessionUser operator) {
        TaskDefinition definition = definitionMap().get(TASK_ANALYSIS_FULL);
        return submitTask(definition, operator, progress -> {
            int totalUsers = safeTotalUsers();
            progress.update(0, totalUsers, "正在加载全量用户并准备多维分析");
            int processedCount = multiDimensionalAnalysisService.analyzeAllUsers(progress::update);
            progress.update(totalUsers, totalUsers, "全量分析完成，已同步用户画像与预警");
            return processedCount;
        });
    }

    public Map<String, Object> submitProfileTask(SessionUser operator) {
        TaskDefinition definition = definitionMap().get(TASK_PROFILE_FULL);
        return submitTask(definition, operator, progress -> {
            int totalUsers = safeTotalUsers();
            progress.update(0, totalUsers, "正在准备聚类特征并加载最新分析结果");
            userProfileService.generateUserProfiles(true);
            progress.update(totalUsers, totalUsers, "全量画像重建完成");
            return totalUsers;
        });
    }

    public Map<String, Object> submitWarningTask(SessionUser operator) {
        TaskDefinition definition = definitionMap().get(TASK_WARNING_FULL);
        return submitTask(definition, operator, progress -> {
            int totalUsers = safeTotalUsers();
            progress.update(0, totalUsers, "正在读取最新分析结果并同步预警");
            int processedCount = warningService.syncWarningsForAllUsers((processed, total) ->
                    progress.update(processed, total, "已完成 " + processed + " / " + total + " 名用户的预警同步"));
            progress.update(processedCount, Math.max(processedCount, totalUsers), "全量预警同步完成");
            return processedCount;
        });
    }

    public Map<String, Object> getTaskStatusSummary() {
        Map<String, TaskExecutionState> persistedStateMap = taskExecutionStateRepository.findAllByOrderByTaskKeyAsc().stream()
                .collect(Collectors.toMap(TaskExecutionState::getTaskKey, state -> state, (left, right) -> left, LinkedHashMap::new));

        List<Map<String, Object>> tasks = definitions().stream()
                .map(definition -> buildTaskStatus(
                        definition,
                        persistedStateMap.get(definition.taskKey()),
                        runtimeStateMap.get(definition.taskKey()),
                        activeTaskKey.get()
                ))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeTaskKey", activeTaskKey.get());
        result.put("tasks", tasks);
        return result;
    }

    @PreDestroy
    public void shutdownExecutor() {
        executorService.shutdownNow();
    }

    private Map<String, Object> submitTask(TaskDefinition definition,
                                           SessionUser operator,
                                           TaskRunner taskRunner) {
        Objects.requireNonNull(definition, "Task definition must not be null");

        String currentActiveTask = activeTaskKey.get();
        if (currentActiveTask != null) {
            if (currentActiveTask.equals(definition.taskKey())) {
                throw new IllegalStateException(definition.taskName() + "正在执行中，请稍候刷新进度");
            }
            throw new IllegalStateException("当前已有“" + findTaskName(currentActiveTask) + "”在执行，请等待其完成后再触发新的全量任务");
        }

        if (!activeTaskKey.compareAndSet(null, definition.taskKey())) {
            return submitTask(definition, operator, taskRunner);
        }

        RuntimeTaskState runtimeState = new RuntimeTaskState();
        runtimeState.setRunning(true);
        runtimeState.setTaskKey(definition.taskKey());
        runtimeState.setTaskName(definition.taskName());
        runtimeState.setDescription(definition.description());
        runtimeState.setTotalCount(safeTotalUsers());
        runtimeState.setOperatorLabel(resolveOperatorLabel(operator));
        runtimeState.setStartedTime(new Date());
        runtimeState.setUpdatedTime(runtimeState.getStartedTime());
        runtimeState.setMessage("任务已提交，等待后台执行");
        runtimeState.setProgressPercent(0.0);
        runtimeStateMap.put(definition.taskKey(), runtimeState);

        taskExecutionStateService.markRunning(definition.taskKey(), definition.taskName());
        taskOperationAuditService.recordSuccess(
                definition.taskKey(),
                definition.taskName(),
                "TRIGGER_FULL",
                operator,
                "已提交全量任务"
        );

        CompletableFuture.runAsync(() -> executeTask(definition, operator, taskRunner), executorService);
        return buildSubmissionResult(definition, runtimeState);
    }

    private void executeTask(TaskDefinition definition,
                             SessionUser operator,
                             TaskRunner taskRunner) {
        RuntimeTaskState runtimeState = runtimeStateMap.get(definition.taskKey());
        try {
            ProgressReporter reporter = (processedCount, totalCount, message) ->
                    updateProgress(definition, processedCount, totalCount, message);
            int processedCount = taskRunner.run(reporter);

            RuntimeTaskState finishedState = runtimeStateMap.get(definition.taskKey());
            int finalProcessedCount = finishedState == null ? processedCount : finishedState.getProcessedCount();
            String finalMessage = finishedState == null || finishedState.getMessage() == null
                    ? "任务执行完成"
                    : finishedState.getMessage();

            taskExecutionStateService.markSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    finalProcessedCount,
                    finalMessage
            );
            if (finishedState != null) {
                finishedState.setRunning(false);
                finishedState.setCompletedTime(new Date());
                finishedState.setUpdatedTime(finishedState.getCompletedTime());
                finishedState.setLastStatus(TaskExecutionStateService.STATUS_SUCCESS);
                finishedState.setLastMessage(finalMessage);
                finishedState.setProgressPercent(100.0);
            }
        } catch (Exception ex) {
            taskExecutionStateService.markFailed(
                    definition.taskKey(),
                    definition.taskName(),
                    "任务执行失败: " + (ex.getMessage() == null ? "内部服务异常" : ex.getMessage()),
                    ex
            );
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "EXECUTE_FULL",
                    operator,
                    "全量任务执行失败",
                    ex
            );
            if (runtimeState != null) {
                runtimeState.setRunning(false);
                runtimeState.setCompletedTime(new Date());
                runtimeState.setUpdatedTime(runtimeState.getCompletedTime());
                runtimeState.setLastStatus(TaskExecutionStateService.STATUS_FAILED);
                runtimeState.setLastMessage("任务执行失败: " + (ex.getMessage() == null ? "内部服务异常" : ex.getMessage()));
                runtimeState.setMessage(runtimeState.getLastMessage());
            }
        } finally {
            activeTaskKey.compareAndSet(definition.taskKey(), null);
        }
    }

    private void updateProgress(TaskDefinition definition,
                                int processedCount,
                                int totalCount,
                                String message) {
        RuntimeTaskState runtimeState = runtimeStateMap.computeIfAbsent(definition.taskKey(), key -> new RuntimeTaskState());
        runtimeState.setTaskKey(definition.taskKey());
        runtimeState.setTaskName(definition.taskName());
        runtimeState.setDescription(definition.description());
        runtimeState.setRunning(true);
        runtimeState.setProcessedCount(Math.max(processedCount, 0));
        runtimeState.setTotalCount(Math.max(totalCount, 0));
        runtimeState.setMessage(message);
        runtimeState.setUpdatedTime(new Date());
        runtimeState.setProgressPercent(calculateProgress(runtimeState.getProcessedCount(), runtimeState.getTotalCount()));
        runtimeState.setLastStatus(TaskExecutionStateService.STATUS_RUNNING);
        runtimeState.setLastMessage(message);

        taskExecutionStateService.updateRunningProgress(
                definition.taskKey(),
                definition.taskName(),
                runtimeState.getProcessedCount(),
                message
        );
    }

    private Map<String, Object> buildSubmissionResult(TaskDefinition definition, RuntimeTaskState runtimeState) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskKey", definition.taskKey());
        result.put("taskName", definition.taskName());
        result.put("message", definition.taskName() + "已提交，系统会在后台持续执行并更新进度");
        result.put("running", true);
        result.put("processedCount", runtimeState.getProcessedCount());
        result.put("totalCount", runtimeState.getTotalCount());
        result.put("progressPercent", runtimeState.getProgressPercent());
        return result;
    }

    private Map<String, Object> buildTaskStatus(TaskDefinition definition,
                                                TaskExecutionState persistedState,
                                                RuntimeTaskState runtimeState,
                                                String currentActiveTaskKey) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskKey", definition.taskKey());
        item.put("taskName", definition.taskName());
        item.put("description", definition.description());

        boolean running = runtimeState != null && runtimeState.isRunning();
        int totalCount = runtimeState != null && runtimeState.getTotalCount() > 0
                ? runtimeState.getTotalCount()
                : safeTotalUsers();
        int processedCount = runtimeState != null
                ? runtimeState.getProcessedCount()
                : (persistedState == null || persistedState.getLastProcessedCount() == null ? 0 : persistedState.getLastProcessedCount());
        double progressPercent = runtimeState != null
                ? runtimeState.getProgressPercent()
                : (persistedState != null && TaskExecutionStateService.STATUS_SUCCESS.equalsIgnoreCase(persistedState.getLastStatus()) ? 100.0 : 0.0);

        item.put("running", running);
        item.put("canTrigger", currentActiveTaskKey == null);
        item.put("blockedByTask", currentActiveTaskKey != null && !definition.taskKey().equals(currentActiveTaskKey)
                ? findTaskName(currentActiveTaskKey)
                : null);
        item.put("processedCount", processedCount);
        item.put("totalCount", totalCount);
        item.put("progressPercent", progressPercent);
        item.put("operatorLabel", runtimeState == null ? null : runtimeState.getOperatorLabel());
        item.put("message", running
                ? runtimeState.getMessage()
                : persistedState == null ? "尚未执行" : persistedState.getLastMessage());
        item.put("lastStatus", running
                ? TaskExecutionStateService.STATUS_RUNNING
                : (persistedState == null ? null : persistedState.getLastStatus()));
        item.put("lastMessage", persistedState == null ? null : persistedState.getLastMessage());
        item.put("lastProcessedCount", persistedState == null ? 0 : persistedState.getLastProcessedCount());
        item.put("lastStartedTime", running
                ? runtimeState.getStartedTime()
                : (persistedState == null ? null : persistedState.getLastStartedTime()));
        item.put("lastCompletedTime", running
                ? null
                : (persistedState == null ? null : persistedState.getLastCompletedTime()));
        item.put("updateTime", running
                ? runtimeState.getUpdatedTime()
                : (persistedState == null ? null : persistedState.getUpdateTime()));
        return item;
    }

    private int safeTotalUsers() {
        long totalUsers = campusUserRepository.count();
        return totalUsers > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalUsers;
    }

    private double calculateProgress(int processedCount, int totalCount) {
        if (totalCount <= 0) {
            return 0.0;
        }
        return Math.min(100.0, Math.max(0.0, processedCount * 100.0 / totalCount));
    }

    private List<TaskDefinition> definitions() {
        return List.of(
                new TaskDefinition(TASK_ANALYSIS_FULL, "全量多维分析", "基于真实日志重算全体用户健康度、风险分与关键行为指标"),
                new TaskDefinition(TASK_PROFILE_FULL, "全量画像重建", "基于最新健康度和行为特征重新聚类并刷新用户画像标签"),
                new TaskDefinition(TASK_WARNING_FULL, "全量预警同步", "按最新分析结果批量重建开放预警、关闭失效预警")
        );
    }

    private Map<String, TaskDefinition> definitionMap() {
        return definitions().stream()
                .collect(Collectors.toMap(TaskDefinition::taskKey, definition -> definition, (left, right) -> left, LinkedHashMap::new));
    }

    private String findTaskName(String taskKey) {
        return definitionMap().getOrDefault(taskKey, new TaskDefinition(taskKey, taskKey, "")).taskName();
    }

    private String resolveOperatorLabel(SessionUser operator) {
        if (operator == null) {
            return "SYSTEM";
        }
        if (operator.getDisplayName() != null
                && !operator.getDisplayName().isBlank()
                && !Objects.equals(operator.getDisplayName(), operator.getUsername())) {
            return operator.getDisplayName() + " (" + operator.getUsername() + ")";
        }
        return operator.getUsername();
    }

    @FunctionalInterface
    private interface TaskRunner {
        int run(ProgressReporter progressReporter) throws Exception;
    }

    @FunctionalInterface
    private interface ProgressReporter {
        void update(int processedCount, int totalCount, String message);
    }

    private record TaskDefinition(String taskKey, String taskName, String description) {
    }

    private static final class RuntimeTaskState {
        private String taskKey;
        private String taskName;
        private String description;
        private boolean running;
        private int processedCount;
        private int totalCount;
        private double progressPercent;
        private String message;
        private String lastStatus;
        private String lastMessage;
        private String operatorLabel;
        private Date startedTime;
        private Date completedTime;
        private Date updatedTime;

        public String getTaskKey() {
            return taskKey;
        }

        public void setTaskKey(String taskKey) {
            this.taskKey = taskKey;
        }

        public String getTaskName() {
            return taskName;
        }

        public void setTaskName(String taskName) {
            this.taskName = taskName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        public int getProcessedCount() {
            return processedCount;
        }

        public void setProcessedCount(int processedCount) {
            this.processedCount = processedCount;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }

        public double getProgressPercent() {
            return progressPercent;
        }

        public void setProgressPercent(double progressPercent) {
            this.progressPercent = progressPercent;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getLastStatus() {
            return lastStatus;
        }

        public void setLastStatus(String lastStatus) {
            this.lastStatus = lastStatus;
        }

        public String getLastMessage() {
            return lastMessage;
        }

        public void setLastMessage(String lastMessage) {
            this.lastMessage = lastMessage;
        }

        public String getOperatorLabel() {
            return operatorLabel;
        }

        public void setOperatorLabel(String operatorLabel) {
            this.operatorLabel = operatorLabel;
        }

        public Date getStartedTime() {
            return startedTime;
        }

        public void setStartedTime(Date startedTime) {
            this.startedTime = startedTime;
        }

        public Date getCompletedTime() {
            return completedTime;
        }

        public void setCompletedTime(Date completedTime) {
            this.completedTime = completedTime;
        }

        public Date getUpdatedTime() {
            return updatedTime;
        }

        public void setUpdatedTime(Date updatedTime) {
            this.updatedTime = updatedTime;
        }
    }
}