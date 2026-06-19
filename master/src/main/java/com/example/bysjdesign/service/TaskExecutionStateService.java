package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.TaskExecutionLog;
import com.example.bysjdesign.campus.entity.TaskExecutionState;
import com.example.bysjdesign.repository.TaskExecutionLogRepository;
import com.example.bysjdesign.repository.TaskExecutionStateRepository;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Service
@Transactional(readOnly = true)
public class TaskExecutionStateService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final TaskExecutionStateRepository taskExecutionStateRepository;
    private final TaskExecutionLogRepository taskExecutionLogRepository;
    private final Scheduler scheduler;

    @Value("${app.node.id:${HOSTNAME:local-node}}")
    private String nodeId;

    public TaskExecutionStateService(TaskExecutionStateRepository taskExecutionStateRepository,
                                     TaskExecutionLogRepository taskExecutionLogRepository,
                                     Scheduler scheduler) {
        this.taskExecutionStateRepository = taskExecutionStateRepository;
        this.taskExecutionLogRepository = taskExecutionLogRepository;
        this.scheduler = scheduler;
    }

    public LocalDateTime getLastCompletedTime(String taskKey) {
        return taskExecutionStateRepository.findById(taskKey)
                .map(TaskExecutionState::getLastCompletedTime)
                .map(value -> LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault()))
                .orElse(null);
    }

    @Transactional
    public TaskExecutionState markRunning(String taskKey, String taskName) {
        TaskExecutionState state = loadOrCreate(taskKey, taskName);
        Date now = new Date();
        state.setTaskName(taskName);
        state.setLastStatus(STATUS_RUNNING);
        state.setLastProcessedCount(0);
        state.setLastMessage("Task is running");
        state.setLastStartedTime(now);
        state.setUpdateTime(now);
        return taskExecutionStateRepository.save(state);
    }

    @Transactional
    public TaskExecutionState updateRunningProgress(String taskKey,
                                                    String taskName,
                                                    int processedCount,
                                                    String message) {
        TaskExecutionState state = loadOrCreate(taskKey, taskName);
        Date now = new Date();
        state.setTaskName(taskName);
        state.setLastStatus(STATUS_RUNNING);
        state.setLastProcessedCount(Math.max(processedCount, 0));
        state.setLastMessage(trimMessage(message));
        if (state.getLastStartedTime() == null) {
            state.setLastStartedTime(now);
        }
        state.setUpdateTime(now);
        return taskExecutionStateRepository.save(state);
    }

    @Transactional
    public TaskExecutionState markSuccess(String taskKey, String taskName, int processedCount, String message) {
        return markFinished(taskKey, taskName, STATUS_SUCCESS, processedCount, message, null);
    }

    @Transactional
    public TaskExecutionState markSkipped(String taskKey, String taskName, String message) {
        return markFinished(taskKey, taskName, STATUS_SKIPPED, 0, message, null);
    }

    @Transactional
    public TaskExecutionState markFailed(String taskKey, String taskName, String message) {
        return markFailed(taskKey, taskName, message, (String) null);
    }

    @Transactional
    public TaskExecutionState markFailed(String taskKey, String taskName, String message, Throwable throwable) {
        return markFailed(taskKey, taskName, message, buildFailureDetail(throwable));
    }

    @Transactional
    public TaskExecutionState markFailed(String taskKey, String taskName, String message, String detail) {
        TaskExecutionState state = loadOrCreate(taskKey, taskName);
        Date now = new Date();
        state.setTaskName(taskName);
        state.setLastStatus(STATUS_FAILED);
        state.setLastProcessedCount(0);
        state.setLastMessage(trimMessage(message));
        state.setLastCompletedTime(now);
        state.setUpdateTime(now);
        TaskExecutionState saved = taskExecutionStateRepository.save(state);
        appendExecutionLog(saved, STATUS_FAILED, 0, message, detail, now);
        return saved;
    }

    private TaskExecutionState markFinished(String taskKey,
                                            String taskName,
                                            String status,
                                            int processedCount,
                                            String message,
                                            String detail) {
        TaskExecutionState state = loadOrCreate(taskKey, taskName);
        Date now = new Date();
        state.setTaskName(taskName);
        state.setLastStatus(status);
        state.setLastProcessedCount(processedCount);
        state.setLastMessage(trimMessage(message));
        state.setLastCompletedTime(now);
        state.setUpdateTime(now);
        TaskExecutionState saved = taskExecutionStateRepository.save(state);
        appendExecutionLog(saved, status, processedCount, message, detail, now);
        return saved;
    }

    private TaskExecutionState loadOrCreate(String taskKey, String taskName) {
        return taskExecutionStateRepository.findById(taskKey).orElseGet(() -> {
            TaskExecutionState state = new TaskExecutionState();
            state.setTaskKey(taskKey);
            state.setTaskName(taskName);
            return state;
        });
    }

    private String trimMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private String trimDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.length() > 4000 ? normalized.substring(0, 4000) : normalized;
    }

    private void appendExecutionLog(TaskExecutionState state,
                                    String status,
                                    int processedCount,
                                    String message,
                                    String detail,
                                    Date completedTime) {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setTaskKey(state.getTaskKey());
        log.setTaskName(state.getTaskName());
        log.setStatus(status);
        log.setProcessedCount(processedCount);
        log.setMessage(trimMessage(message));
        log.setDetail(trimDetail(detail));
        log.setStartedTime(state.getLastStartedTime());
        log.setCompletedTime(completedTime);
        log.setDurationMs(calculateDuration(state.getLastStartedTime(), completedTime));
        log.setSchedulerInstanceId(resolveSchedulerInstanceId());
        log.setNodeId(nodeId);
        log.setCreateTime(completedTime);
        taskExecutionLogRepository.save(log);
    }

    private Long calculateDuration(Date startedTime, Date completedTime) {
        if (startedTime == null || completedTime == null) {
            return null;
        }
        return Math.max(0L, completedTime.getTime() - startedTime.getTime());
    }

    private String resolveSchedulerInstanceId() {
        try {
            return scheduler.getMetaData().getSchedulerInstanceId();
        } catch (SchedulerException ex) {
            return "UNKNOWN";
        }
    }

    private String buildFailureDetail(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }
}
