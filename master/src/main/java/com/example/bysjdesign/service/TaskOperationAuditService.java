package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.TaskOperationAudit;
import com.example.bysjdesign.repository.TaskOperationAuditRepository;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

@Service
@Transactional(readOnly = true)
public class TaskOperationAuditService {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";

    private final TaskOperationAuditRepository taskOperationAuditRepository;
    private final Scheduler scheduler;

    @Value("${app.node.id:${HOSTNAME:local-node}}")
    private String nodeId;

    public TaskOperationAuditService(TaskOperationAuditRepository taskOperationAuditRepository,
                                     Scheduler scheduler) {
        this.taskOperationAuditRepository = taskOperationAuditRepository;
        this.scheduler = scheduler;
    }

    @Transactional
    public TaskOperationAudit recordSuccess(String taskKey,
                                            String taskName,
                                            String action,
                                            SessionUser operator,
                                            String message) {
        return saveAudit(taskKey, taskName, action, RESULT_SUCCESS, operator, message, null);
    }

    @Transactional
    public TaskOperationAudit recordSuccess(String taskKey,
                                            String taskName,
                                            String action,
                                            SessionUser operator,
                                            String message,
                                            String detail) {
        return saveAudit(taskKey, taskName, action, RESULT_SUCCESS, operator, message, detail);
    }

    @Transactional
    public TaskOperationAudit recordFailure(String taskKey,
                                            String taskName,
                                            String action,
                                            SessionUser operator,
                                            String message,
                                            Throwable throwable) {
        return saveAudit(taskKey, taskName, action, RESULT_FAILED, operator, message, buildDetail(throwable));
    }

    @Transactional
    public TaskOperationAudit recordFailure(String taskKey,
                                            String taskName,
                                            String action,
                                            SessionUser operator,
                                            String message,
                                            String detail) {
        return saveAudit(taskKey, taskName, action, RESULT_FAILED, operator, message, detail);
    }

    private TaskOperationAudit saveAudit(String taskKey,
                                         String taskName,
                                         String action,
                                         String result,
                                         SessionUser operator,
                                         String message,
                                         String detail) {
        TaskOperationAudit audit = new TaskOperationAudit();
        audit.setTaskKey(taskKey);
        audit.setTaskName(taskName);
        audit.setAction(action);
        audit.setResult(result);
        if (operator != null) {
            audit.setOperatorUserId(operator.getUserId());
            audit.setOperatorUsername(trimValue(operator.getUsername(), 100));
            audit.setOperatorDisplayName(trimValue(operator.getDisplayName(), 100));
            audit.setOperatorRole(trimValue(operator.getRole(), 20));
        }
        audit.setMessage(trimValue(message, 500));
        audit.setDetail(trimValue(detail, 4000));
        audit.setSchedulerInstanceId(resolveSchedulerInstanceId());
        audit.setNodeId(trimValue(nodeId, 190));
        audit.setCreateTime(new Date());
        return taskOperationAuditRepository.save(audit);
    }

    private String resolveSchedulerInstanceId() {
        try {
            return scheduler.getMetaData().getSchedulerInstanceId();
        } catch (SchedulerException ex) {
            return "UNKNOWN";
        }
    }

    private String buildDetail(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private String trimValue(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
