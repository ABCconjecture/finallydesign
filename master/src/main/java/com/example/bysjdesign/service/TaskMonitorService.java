package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.TaskExecutionLog;
import com.example.bysjdesign.campus.entity.TaskOperationAudit;
import com.example.bysjdesign.campus.entity.TaskExecutionState;
import com.example.bysjdesign.config.QuartzConfig;
import com.example.bysjdesign.repository.TaskExecutionLogRepository;
import com.example.bysjdesign.repository.TaskOperationAuditRepository;
import com.example.bysjdesign.repository.TaskExecutionStateRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerMetaData;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskMonitorService {

    private static final DateTimeFormatter AUDIT_TIME_FILTER_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final TaskExecutionStateRepository taskExecutionStateRepository;
    private final TaskExecutionLogRepository taskExecutionLogRepository;
    private final TaskOperationAuditRepository taskOperationAuditRepository;
    private final TaskOperationAuditService taskOperationAuditService;
    private final Scheduler scheduler;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.quartz.analysis.cron:0 0 * * * ?}")
    private String analysisCron;

    @Value("${app.quartz.profile.cron:0 5 * * * ?}")
    private String profileCron;

    @Value("${app.quartz.warning.cron:0 10 * * * ?}")
    private String warningCron;

    @Value("${spring.quartz.properties.org.quartz.scheduler.instanceName:campusScheduler}")
    private String schedulerName;

    @Value("${app.node.id:${HOSTNAME:local-node}}")
    private String nodeId;

    @Value("${app.task.retry.max-consecutive-failures:3}")
    private int retryMaxConsecutiveFailures;

    @Value("${app.task.retry.cooldown-seconds:300}")
    private long retryCooldownSeconds;

    public TaskMonitorService(TaskExecutionStateRepository taskExecutionStateRepository,
                              TaskExecutionLogRepository taskExecutionLogRepository,
                              TaskOperationAuditRepository taskOperationAuditRepository,
                              TaskOperationAuditService taskOperationAuditService,
                              Scheduler scheduler,
                              JdbcTemplate jdbcTemplate) {
        this.taskExecutionStateRepository = taskExecutionStateRepository;
        this.taskExecutionLogRepository = taskExecutionLogRepository;
        this.taskOperationAuditRepository = taskOperationAuditRepository;
        this.taskOperationAuditService = taskOperationAuditService;
        this.scheduler = scheduler;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getTaskStatusDashboard() {
        Map<String, TaskExecutionState> stateMap = taskExecutionStateRepository.findAllByOrderByTaskKeyAsc().stream()
                .collect(Collectors.toMap(TaskExecutionState::getTaskKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskDefinition definition : getDefinitions()) {
            tasks.add(buildTaskItem(definition, stateMap.get(definition.taskKey())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduler", buildSchedulerInfo());
        result.put("tasks", tasks);
        return result;
    }

    public Map<String, Object> triggerTask(String taskKey, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        try {
            JobKey jobKey = JobKey.jobKey(definition.jobName());
            if (!scheduler.checkExists(jobKey)) {
                throw new IllegalArgumentException("Task job does not exist: " + taskKey);
            }
            scheduler.triggerJob(jobKey);
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "TRIGGER",
                    operator,
                    "Manual execution request submitted"
            );
            return buildOperationResult(definition, "TRIGGERED", "Manual execution request submitted");
        } catch (IllegalArgumentException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "TRIGGER",
                    operator,
                    ex.getMessage(),
                    (String) null
            );
            throw ex;
        } catch (SchedulerException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "TRIGGER",
                    operator,
                    "Failed to trigger task: " + taskKey,
                    ex
            );
            throw new IllegalStateException("Failed to trigger task: " + taskKey, ex);
        }
    }

    public Map<String, Object> retryTask(String taskKey,
                                         String retryReason,
                                         String approvalNote,
                                         SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        TaskExecutionState currentState = taskExecutionStateRepository.findById(definition.taskKey()).orElse(null);
        TaskExecutionLog latestFailure = taskExecutionLogRepository.findFirstByTaskKeyAndStatusOrderByIdDesc(
                definition.taskKey(),
                TaskExecutionStateService.STATUS_FAILED
        );
        RetryGuard retryGuard = evaluateRetryGuard(definition.taskKey(), currentState, true, false);
        if (!retryGuard.allowed()) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "RETRY",
                    operator,
                    retryGuard.reason(),
                    buildRetryAuditDetail(retryReason, approvalNote, latestFailure, retryGuard.reason(), null)
            );
            throw new IllegalArgumentException(retryGuard.reason());
        }

        try {
            JobKey jobKey = JobKey.jobKey(definition.jobName());
            if (!scheduler.checkExists(jobKey)) {
                throw new IllegalArgumentException("Task job does not exist: " + taskKey);
            }
            scheduler.triggerJob(jobKey);
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "RETRY",
                    operator,
                    "Retry request submitted for latest failed execution",
                    buildRetryAuditDetail(retryReason, approvalNote, latestFailure, null, null)
            );
            Map<String, Object> result = buildOperationResult(
                    definition,
                    "RETRIED",
                    "Retry request submitted for latest failed execution"
            );
            result.put("retrySource", latestFailure == null ? null : mapExecutionLog(latestFailure));
            result.put("retryGuard", mapRetryGuard(retryGuard));
            result.put("retryReason", normalizeAuditNote(retryReason, 200));
            result.put("approvalNote", normalizeAuditNote(approvalNote, 300));
            return result;
        } catch (IllegalArgumentException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "RETRY",
                    operator,
                    ex.getMessage(),
                    buildRetryAuditDetail(retryReason, approvalNote, latestFailure, ex.getMessage(), null)
            );
            throw ex;
        } catch (SchedulerException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "RETRY",
                    operator,
                    "Failed to retry task: " + taskKey,
                    buildRetryAuditDetail(retryReason, approvalNote, latestFailure, "Failed to retry task: " + taskKey, ex)
            );
            throw new IllegalStateException("Failed to retry task: " + taskKey, ex);
        }
    }

    public Map<String, Object> pauseTask(String taskKey, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        try {
            TriggerKey triggerKey = TriggerKey.triggerKey(definition.triggerName());
            if (!scheduler.checkExists(triggerKey)) {
                throw new IllegalArgumentException("Task trigger does not exist: " + taskKey);
            }
            scheduler.pauseTrigger(triggerKey);
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "PAUSE",
                    operator,
                    "Scheduled trigger paused"
            );
            return buildOperationResult(definition, "PAUSED", "Scheduled trigger paused");
        } catch (IllegalArgumentException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "PAUSE",
                    operator,
                    ex.getMessage(),
                    (String) null
            );
            throw ex;
        } catch (SchedulerException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "PAUSE",
                    operator,
                    "Failed to pause task: " + taskKey,
                    ex
            );
            throw new IllegalStateException("Failed to pause task: " + taskKey, ex);
        }
    }

    public Map<String, Object> resumeTask(String taskKey, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        try {
            TriggerKey triggerKey = TriggerKey.triggerKey(definition.triggerName());
            if (!scheduler.checkExists(triggerKey)) {
                throw new IllegalArgumentException("Task trigger does not exist: " + taskKey);
            }
            scheduler.resumeTrigger(triggerKey);
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "RESUME",
                    operator,
                    "Scheduled trigger resumed"
            );
            return buildOperationResult(definition, "RESUMED", "Scheduled trigger resumed");
        } catch (IllegalArgumentException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "RESUME",
                    operator,
                    ex.getMessage(),
                    (String) null
            );
            throw ex;
        } catch (SchedulerException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "RESUME",
                    operator,
                    "Failed to resume task: " + taskKey,
                    ex
            );
            throw new IllegalStateException("Failed to resume task: " + taskKey, ex);
        }
    }

    public Map<String, Object> getTaskLogs(String taskKey, int page, int size) {
        TaskDefinition definition = requireDefinition(taskKey);
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 20);

        Page<TaskExecutionLog> logPage = taskExecutionLogRepository.findByTaskKeyOrderByIdDesc(
                definition.taskKey(),
                PageRequest.of(normalizedPage, normalizedSize)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskKey", definition.taskKey());
        result.put("taskName", definition.taskName());
        result.put("page", normalizedPage);
        result.put("size", normalizedSize);
        result.put("totalPages", logPage.getTotalPages());
        result.put("totalElements", logPage.getTotalElements());
        result.put("hasPrevious", logPage.hasPrevious());
        result.put("hasNext", logPage.hasNext());
        result.put("content", logPage.getContent().stream().map(this::mapExecutionLog).collect(Collectors.toList()));
        TaskExecutionLog latestFailure = taskExecutionLogRepository.findFirstByTaskKeyAndStatusOrderByIdDesc(
                definition.taskKey(),
                TaskExecutionStateService.STATUS_FAILED
        );
        result.put("latestFailure", latestFailure == null ? null : mapExecutionLog(latestFailure));
        return result;
    }

    public Map<String, Object> getTaskAudits(String taskKey,
                                             int page,
                                             int size,
                                             String action,
                                             String operatorKeyword,
                                             String resultStatus,
                                             String startTime,
                                             String endTime) {
        TaskDefinition definition = requireDefinition(taskKey);
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 20);
        AuditFilter filter = normalizeAuditFilter(action, operatorKeyword, resultStatus, startTime, endTime);

        Page<TaskOperationAudit> auditPage = taskOperationAuditRepository.findAll(
                buildAuditSpecification(definition.taskKey(), filter),
                PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "id"))
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskKey", definition.taskKey());
        result.put("taskName", definition.taskName());
        result.put("action", filter.action());
        result.put("operatorKeyword", filter.operatorKeyword());
        result.put("resultStatus", filter.resultStatus());
        result.put("startTime", filter.startTimeText());
        result.put("endTime", filter.endTimeText());
        result.put("page", normalizedPage);
        result.put("size", normalizedSize);
        result.put("totalPages", auditPage.getTotalPages());
        result.put("totalElements", auditPage.getTotalElements());
        result.put("hasPrevious", auditPage.hasPrevious());
        result.put("hasNext", auditPage.hasNext());
        result.put("content", auditPage.getContent().stream().map(this::mapOperationAudit).collect(Collectors.toList()));
        return result;
    }

    public ExportFile exportTaskAudits(String taskKey,
                                       String action,
                                       String operatorKeyword,
                                       String resultStatus,
                                       String startTime,
                                       String endTime,
                                       SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        AuditFilter filter = normalizeAuditFilter(action, operatorKeyword, resultStatus, startTime, endTime);
        List<TaskOperationAudit> audits = taskOperationAuditRepository.findAll(buildAuditSpecification(definition.taskKey(), filter));
        audits.sort((left, right) -> Long.compare(right.getId(), left.getId()));

        try (StringWriter output = new StringWriter();
             CSVPrinter csvPrinter = new CSVPrinter(output, CSVFormat.DEFAULT.withHeader(
                     "taskKey",
                     "taskName",
                     "action",
                     "result",
                     "operatorUsername",
                     "operatorDisplayName",
                     "operatorRole",
                     "createTime",
                     "nodeId",
                     "schedulerInstanceId",
                     "message",
                     "retryReason",
                     "approvalNote",
                     "detail"
             ))) {
            for (TaskOperationAudit audit : audits) {
                RetryAuditMetadata retryAuditMetadata = parseRetryAuditMetadata(audit.getDetail());
                csvPrinter.printRecord(
                        audit.getTaskKey(),
                        audit.getTaskName(),
                        audit.getAction(),
                        audit.getResult(),
                        audit.getOperatorUsername(),
                        audit.getOperatorDisplayName(),
                        audit.getOperatorRole(),
                        audit.getCreateTime(),
                        audit.getNodeId(),
                        audit.getSchedulerInstanceId(),
                        audit.getMessage(),
                        retryAuditMetadata.retryReason(),
                        retryAuditMetadata.approvalNote(),
                        audit.getDetail()
                );
            }
            csvPrinter.flush();
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "EXPORT_AUDIT",
                    operator,
                    buildAuditExportMessage(filter)
            );
            String fileName = String.format("task-%s-audits.csv", definition.taskKey());
            return new ExportFile(fileName, output.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "EXPORT_AUDIT",
                    operator,
                    "Failed to export task audits: " + taskKey,
                    ex
            );
            throw new IllegalStateException("Failed to export task audits: " + taskKey, ex);
        }
    }

    public Map<String, Object> copyTaskAuditDetail(String taskKey, Long auditId, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        TaskOperationAudit audit = taskOperationAuditRepository.findByIdAndTaskKey(auditId, definition.taskKey())
                .orElseThrow(() -> new IllegalArgumentException("Task audit record does not exist: " + auditId));

        String content = resolveAuditCopyContent(audit);
        if (content == null) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "COPY_AUDIT_DETAIL",
                    operator,
                    "No audit detail is available for copying",
                    (String) null
            );
            throw new IllegalArgumentException("No audit detail is available for copying");
        }

        taskOperationAuditService.recordSuccess(
                definition.taskKey(),
                definition.taskName(),
                "COPY_AUDIT_DETAIL",
                operator,
                "Copied task audit detail from task dashboard"
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskKey", definition.taskKey());
        result.put("taskName", definition.taskName());
        result.put("auditId", audit.getId());
        result.put("audit", mapOperationAudit(audit));
        result.put("content", content);
        return result;
    }

    public Map<String, Object> getLatestFailureStack(String taskKey, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        TaskExecutionLog latestFailure = taskExecutionLogRepository.findFirstByTaskKeyAndStatusOrderByIdDesc(
                definition.taskKey(),
                TaskExecutionStateService.STATUS_FAILED
        );
        if (latestFailure == null || latestFailure.getDetail() == null || latestFailure.getDetail().isBlank()) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "COPY_FAILURE_STACK",
                    operator,
                    "No failure stack is available for copying",
                    (String) null
            );
            throw new IllegalArgumentException("No failure stack is available for copying");
        }

        taskOperationAuditService.recordSuccess(
                definition.taskKey(),
                definition.taskName(),
                "COPY_FAILURE_STACK",
                operator,
                "Latest failure stack copied from task dashboard"
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskKey", definition.taskKey());
        result.put("taskName", definition.taskName());
        result.put("latestFailure", mapExecutionLog(latestFailure));
        result.put("detail", latestFailure.getDetail());
        return result;
    }

    public ExportFile exportTaskLogs(String taskKey, SessionUser operator) {
        TaskDefinition definition = requireDefinition(taskKey);
        List<TaskExecutionLog> logs = taskExecutionLogRepository.findByTaskKeyOrderByIdDesc(definition.taskKey());

        try (StringWriter output = new StringWriter();
             CSVPrinter csvPrinter = new CSVPrinter(output, CSVFormat.DEFAULT.withHeader(
                     "taskKey",
                     "taskName",
                     "status",
                     "processedCount",
                     "startedTime",
                     "completedTime",
                     "durationMs",
                     "nodeId",
                     "schedulerInstanceId",
                     "message",
                     "detail"
             ))) {
            for (TaskExecutionLog log : logs) {
                csvPrinter.printRecord(
                        log.getTaskKey(),
                        log.getTaskName(),
                        log.getStatus(),
                        log.getProcessedCount(),
                        log.getStartedTime(),
                        log.getCompletedTime(),
                        log.getDurationMs(),
                        log.getNodeId(),
                        log.getSchedulerInstanceId(),
                        log.getMessage(),
                        log.getDetail()
                );
            }
            csvPrinter.flush();
            String fileName = String.format("task-%s-logs.csv", definition.taskKey());
            taskOperationAuditService.recordSuccess(
                    definition.taskKey(),
                    definition.taskName(),
                    "EXPORT_LOG",
                    operator,
                    "Exported task logs from task dashboard"
            );
            return new ExportFile(fileName, output.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            taskOperationAuditService.recordFailure(
                    definition.taskKey(),
                    definition.taskName(),
                    "EXPORT_LOG",
                    operator,
                    "Failed to export task logs: " + taskKey,
                    ex
            );
            throw new IllegalStateException("Failed to export task logs: " + taskKey, ex);
        }
    }

    private Specification<TaskOperationAudit> buildAuditSpecification(String taskKey, AuditFilter filter) {
        return (root, query, builder) -> {
            List<javax.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("taskKey"), taskKey));
            if (filter.action() != null) {
                predicates.add(builder.equal(root.get("action"), filter.action()));
            }
            if (filter.resultStatus() != null) {
                predicates.add(builder.equal(root.get("result"), filter.resultStatus()));
            }
            if (filter.operatorKeyword() != null) {
                String pattern = "%" + filter.operatorKeyword().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("operatorUsername")), pattern),
                        builder.like(builder.lower(root.get("operatorDisplayName")), pattern)
                ));
            }
            if (filter.startTime() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createTime"), filter.startTime()));
            }
            if (filter.endTime() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createTime"), filter.endTime()));
            }
            return builder.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        };
    }

    private AuditFilter normalizeAuditFilter(String action,
                                             String operatorKeyword,
                                             String resultStatus,
                                             String startTime,
                                             String endTime) {
        String normalizedAction = normalizeActionFilter(action);
        String normalizedKeyword = normalizeKeyword(operatorKeyword);
        String normalizedResultStatus = normalizeResultStatus(resultStatus);
        String normalizedStartTime = normalizeAuditTimeText(startTime);
        String normalizedEndTime = normalizeAuditTimeText(endTime);
        Date parsedStartTime = parseAuditTime(normalizedStartTime, "startTime");
        Date parsedEndTime = parseAuditTime(normalizedEndTime, "endTime");
        if (parsedStartTime != null && parsedEndTime != null && parsedStartTime.after(parsedEndTime)) {
            throw new IllegalArgumentException("Audit startTime must not be later than endTime");
        }
        return new AuditFilter(
                normalizedAction,
                normalizedKeyword,
                normalizedResultStatus,
                normalizedStartTime,
                normalizedEndTime,
                parsedStartTime,
                parsedEndTime
        );
    }

    private String normalizeActionFilter(String action) {
        if (action == null || action.isBlank() || "ALL".equalsIgnoreCase(action)) {
            return null;
        }
        String normalized = action.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TRIGGER", "RETRY", "PAUSE", "RESUME", "EXPORT_LOG", "EXPORT_AUDIT", "COPY_FAILURE_STACK", "COPY_AUDIT_DETAIL" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported audit action filter: " + action);
        };
    }

    private String normalizeKeyword(String operatorKeyword) {
        if (operatorKeyword == null || operatorKeyword.isBlank()) {
            return null;
        }
        String normalized = operatorKeyword.trim();
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private String normalizeResultStatus(String resultStatus) {
        if (resultStatus == null || resultStatus.isBlank() || "ALL".equalsIgnoreCase(resultStatus)) {
            return null;
        }
        String normalized = resultStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case TaskOperationAuditService.RESULT_SUCCESS, TaskOperationAuditService.RESULT_FAILED -> normalized;
            default -> throw new IllegalArgumentException("Unsupported audit result filter: " + resultStatus);
        };
    }

    private String normalizeAuditTimeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String normalized = value.trim();
            LocalDateTime parsed = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return parsed.format(AUDIT_TIME_FILTER_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Unsupported audit time filter: " + value);
        }
    }

    private Date parseAuditTime(String value, String parameterName) {
        if (value == null) {
            return null;
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(value, AUDIT_TIME_FILTER_FORMATTER);
            if ("endTime".equals(parameterName)) {
                parsed = parsed.plusMinutes(1).minusNanos(1);
            }
            return Date.from(parsed.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Unsupported audit time filter for " + parameterName + ": " + value);
        }
    }

    private String buildAuditExportMessage(AuditFilter filter) {
        List<String> segments = new ArrayList<>();
        if (filter.action() != null) {
            segments.add("action=" + filter.action());
        }
        if (filter.operatorKeyword() != null) {
            segments.add("operator=" + filter.operatorKeyword());
        }
        if (filter.resultStatus() != null) {
            segments.add("result=" + filter.resultStatus());
        }
        if (filter.startTimeText() != null) {
            segments.add("startTime=" + filter.startTimeText());
        }
        if (filter.endTimeText() != null) {
            segments.add("endTime=" + filter.endTimeText());
        }
        if (segments.isEmpty()) {
            return "Exported task operation audits from task dashboard";
        }
        return "Exported task operation audits from task dashboard (" + String.join(", ", segments) + ")";
    }

    private String buildRetryAuditDetail(String retryReason,
                                         String approvalNote,
                                         TaskExecutionLog latestFailure,
                                         String guardOrErrorMessage,
                                         Throwable throwable) {
        List<String> segments = new ArrayList<>();
        String normalizedRetryReason = normalizeAuditNote(retryReason, 200);
        String normalizedApprovalNote = normalizeAuditNote(approvalNote, 300);
        if (normalizedRetryReason != null) {
            segments.add("Retry reason: " + normalizedRetryReason);
        }
        if (normalizedApprovalNote != null) {
            segments.add("Approval note: " + normalizedApprovalNote);
        }
        if (latestFailure != null && latestFailure.getMessage() != null && !latestFailure.getMessage().isBlank()) {
            segments.add("Latest failure: " + latestFailure.getMessage().trim());
        }
        if (guardOrErrorMessage != null && !guardOrErrorMessage.isBlank()) {
            segments.add("Retry decision: " + guardOrErrorMessage.trim());
        }
        if (throwable != null) {
            StringWriter buffer = new StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(buffer));
            segments.add("Exception stack:\n" + buffer);
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join("\n", segments);
    }

    private String normalizeAuditNote(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String resolveAuditCopyContent(TaskOperationAudit audit) {
        String detail = audit.getDetail();
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        String message = audit.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return null;
    }

    private RetryGuard evaluateRetryGuard(String taskKey,
                                          TaskExecutionState state,
                                          boolean jobExists,
                                          boolean executing) {
        if (!jobExists) {
            return new RetryGuard(false, "Task job does not exist", 0, 0);
        }
        if (executing) {
            return new RetryGuard(false, "Task is currently running. Please wait until it finishes", 0, 0);
        }
        if (state == null || !TaskExecutionStateService.STATUS_FAILED.equalsIgnoreCase(state.getLastStatus())) {
            return new RetryGuard(false, "Current task has no failed execution to retry", 0, 0);
        }

        int failureLimit = Math.max(1, retryMaxConsecutiveFailures);
        int consecutiveFailureCount = countConsecutiveFailures(taskKey, failureLimit + 1, state);
        if (consecutiveFailureCount >= failureLimit) {
            return new RetryGuard(
                    false,
                    "Task has failed consecutively " + consecutiveFailureCount
                            + " times. Please inspect logs before retrying again",
                    consecutiveFailureCount,
                    0
            );
        }

        long remainingCooldownSeconds = calculateRetryCooldownRemainingSeconds(taskKey, state);
        if (remainingCooldownSeconds > 0) {
            return new RetryGuard(
                    false,
                    "Retry cooldown is active. Please wait " + remainingCooldownSeconds + " seconds before retrying",
                    consecutiveFailureCount,
                    remainingCooldownSeconds
            );
        }

        return new RetryGuard(true, null, consecutiveFailureCount, 0);
    }

    private int countConsecutiveFailures(String taskKey, int limit, TaskExecutionState state) {
        if (limit <= 0) {
            return 0;
        }
        List<TaskExecutionLog> recentLogs = taskExecutionLogRepository.findByTaskKeyOrderByIdDesc(
                taskKey,
                PageRequest.of(0, limit)
        ).getContent();

        Date failureCompletedTime = state == null ? null : state.getLastCompletedTime();
        long failureWindowStart = failureCompletedTime == null
                ? Long.MIN_VALUE
                : failureCompletedTime.getTime() - TimeUnit.MINUTES.toMillis(15);

        int count = 0;
        for (TaskExecutionLog log : recentLogs) {
            if (!TaskExecutionStateService.STATUS_FAILED.equalsIgnoreCase(log.getStatus())) {
                break;
            }
            Date completedTime = log.getCompletedTime();
            if (completedTime != null && completedTime.getTime() < failureWindowStart) {
                break;
            }
            count++;
        }
        return count;
    }

    private long calculateRetryCooldownRemainingSeconds(String taskKey, TaskExecutionState state) {
        long cooldown = Math.max(0L, retryCooldownSeconds);
        if (cooldown <= 0L) {
            return 0L;
        }

        TaskOperationAudit latestRetry = taskOperationAuditRepository.findFirstByTaskKeyAndActionAndResultOrderByIdDesc(
                taskKey,
                "RETRY",
                TaskOperationAuditService.RESULT_SUCCESS
        );
        if (latestRetry == null || latestRetry.getCreateTime() == null) {
            return 0L;
        }

        Date failureCompletedTime = state == null ? null : state.getLastCompletedTime();
        if (failureCompletedTime != null && latestRetry.getCreateTime().before(failureCompletedTime)) {
            return 0L;
        }

        long elapsedMillis = System.currentTimeMillis() - latestRetry.getCreateTime().getTime();
        long remainingMillis = TimeUnit.SECONDS.toMillis(cooldown) - elapsedMillis;
        if (remainingMillis <= 0L) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil(remainingMillis / 1000.0));
    }

    private Map<String, Object> mapRetryGuard(RetryGuard retryGuard) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", retryGuard.allowed());
        result.put("reason", retryGuard.reason());
        result.put("consecutiveFailureCount", retryGuard.consecutiveFailureCount());
        result.put("cooldownRemainingSeconds", retryGuard.cooldownRemainingSeconds());
        result.put("cooldownSeconds", retryCooldownSeconds);
        result.put("maxConsecutiveFailures", retryMaxConsecutiveFailures);
        return result;
    }

    private Map<String, Object> buildOperationResult(TaskDefinition definition, String action, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("message", message);
        result.put("task", buildTaskItem(definition, taskExecutionStateRepository.findById(definition.taskKey()).orElse(null)));
        result.put("scheduler", buildSchedulerInfo());
        return result;
    }

    private Map<String, Object> buildTaskItem(TaskDefinition definition, TaskExecutionState state) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskKey", definition.taskKey());
        item.put("taskName", definition.taskName());
        item.put("description", definition.description());
        item.put("cronExpression", definition.cronExpression());
        item.put("jobName", definition.jobName());
        item.put("triggerName", definition.triggerName());

        try {
            TriggerKey triggerKey = TriggerKey.triggerKey(definition.triggerName());
            JobKey jobKey = JobKey.jobKey(definition.jobName());
            Trigger trigger = scheduler.getTrigger(triggerKey);
            Trigger.TriggerState triggerState = trigger == null ? null : scheduler.getTriggerState(triggerKey);
            Set<JobKey> runningJobs = scheduler.getCurrentlyExecutingJobs().stream()
                    .map(JobExecutionContext::getJobDetail)
                    .map(detail -> detail.getKey())
                    .collect(Collectors.toSet());

            item.put("triggerState", triggerState == null ? "MISSING" : triggerState.name());
            item.put("nextFireTime", trigger == null ? null : trigger.getNextFireTime());
            item.put("previousFireTime", trigger == null ? null : trigger.getPreviousFireTime());
            item.put("jobExists", scheduler.checkExists(jobKey));
            item.put("executing", runningJobs.contains(jobKey));
            item.put("canTrigger", scheduler.checkExists(jobKey));
            RetryGuard retryGuard = evaluateRetryGuard(
                    definition.taskKey(),
                    state,
                    scheduler.checkExists(jobKey),
                    runningJobs.contains(jobKey)
            );
            item.put("canRetry", retryGuard.allowed());
            item.put("retryGuardReason", retryGuard.reason());
            item.put("consecutiveFailureCount", retryGuard.consecutiveFailureCount());
            item.put("retryCooldownRemainingSeconds", retryGuard.cooldownRemainingSeconds());
            item.put("retryCooldownSeconds", retryCooldownSeconds);
            item.put("retryMaxConsecutiveFailures", retryMaxConsecutiveFailures);
            item.put("canPause", trigger != null && triggerState != Trigger.TriggerState.PAUSED);
            item.put("canResume", trigger != null && triggerState == Trigger.TriggerState.PAUSED);
        } catch (SchedulerException ex) {
            item.put("triggerState", "ERROR");
            item.put("nextFireTime", null);
            item.put("previousFireTime", null);
            item.put("jobExists", false);
            item.put("executing", false);
            item.put("canTrigger", false);
            item.put("canRetry", false);
            item.put("retryGuardReason", "Scheduler metadata is unavailable");
            item.put("consecutiveFailureCount", 0);
            item.put("retryCooldownRemainingSeconds", 0);
            item.put("retryCooldownSeconds", retryCooldownSeconds);
            item.put("retryMaxConsecutiveFailures", retryMaxConsecutiveFailures);
            item.put("canPause", false);
            item.put("canResume", false);
            item.put("schedulerError", ex.getMessage());
        }

        if (state != null) {
            item.put("lastStatus", state.getLastStatus());
            item.put("lastProcessedCount", state.getLastProcessedCount());
            item.put("lastMessage", state.getLastMessage());
            item.put("lastStartedTime", state.getLastStartedTime());
            item.put("lastCompletedTime", state.getLastCompletedTime());
            item.put("updateTime", state.getUpdateTime());
        } else {
            item.put("lastStatus", null);
            item.put("lastProcessedCount", 0);
            item.put("lastMessage", "Task has not been executed yet");
            item.put("lastStartedTime", null);
            item.put("lastCompletedTime", null);
            item.put("updateTime", null);
        }

        item.put("recentLogs", buildRecentLogs(definition.taskKey()));
        return item;
    }

    private List<Map<String, Object>> buildRecentLogs(String taskKey) {
        return taskExecutionLogRepository.findByTaskKeyOrderByIdDesc(taskKey, PageRequest.of(0, 3)).stream()
                .map(this::mapExecutionLog)
                .collect(Collectors.toList());
    }

    private Map<String, Object> mapExecutionLog(TaskExecutionLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.getId());
        item.put("status", log.getStatus());
        item.put("processedCount", log.getProcessedCount());
        item.put("message", log.getMessage());
        item.put("detail", log.getDetail());
        item.put("startedTime", log.getStartedTime());
        item.put("completedTime", log.getCompletedTime());
        item.put("durationMs", log.getDurationMs());
        item.put("schedulerInstanceId", log.getSchedulerInstanceId());
        item.put("nodeId", log.getNodeId());
        return item;
    }

    private Map<String, Object> mapOperationAudit(TaskOperationAudit audit) {
        RetryAuditMetadata retryAuditMetadata = parseRetryAuditMetadata(audit.getDetail());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", audit.getId());
        item.put("action", audit.getAction());
        item.put("result", audit.getResult());
        item.put("operatorUserId", audit.getOperatorUserId());
        item.put("operatorUsername", audit.getOperatorUsername());
        item.put("operatorDisplayName", audit.getOperatorDisplayName());
        item.put("operatorRole", audit.getOperatorRole());
        item.put("operatorLabel", buildOperatorLabel(audit));
        item.put("message", audit.getMessage());
        item.put("retryReason", retryAuditMetadata.retryReason());
        item.put("approvalNote", retryAuditMetadata.approvalNote());
        item.put("detail", audit.getDetail());
        item.put("schedulerInstanceId", audit.getSchedulerInstanceId());
        item.put("nodeId", audit.getNodeId());
        item.put("createTime", audit.getCreateTime());
        return item;
    }

    private RetryAuditMetadata parseRetryAuditMetadata(String detail) {
        if (detail == null || detail.isBlank()) {
            return RetryAuditMetadata.EMPTY;
        }
        String retryReason = null;
        String approvalNote = null;
        for (String rawLine : detail.split("\\r?\\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("Retry reason:")) {
                retryReason = extractAuditDetailValue(line, "Retry reason:");
            } else if (line.startsWith("Approval note:")) {
                approvalNote = extractAuditDetailValue(line, "Approval note:");
            }
        }
        return new RetryAuditMetadata(retryReason, approvalNote);
    }

    private String extractAuditDetailValue(String line, String prefix) {
        String value = line.substring(prefix.length()).trim();
        return value.isEmpty() ? null : value;
    }

    private String buildOperatorLabel(TaskOperationAudit audit) {
        if (audit.getOperatorDisplayName() != null && !audit.getOperatorDisplayName().isBlank()) {
            if (audit.getOperatorUsername() != null && !audit.getOperatorUsername().isBlank()
                    && !audit.getOperatorDisplayName().equals(audit.getOperatorUsername())) {
                return audit.getOperatorDisplayName() + " (" + audit.getOperatorUsername() + ")";
            }
            return audit.getOperatorDisplayName();
        }
        if (audit.getOperatorUsername() != null && !audit.getOperatorUsername().isBlank()) {
            return audit.getOperatorUsername();
        }
        return "SYSTEM";
    }

    private Map<String, Object> buildSchedulerInfo() {
        Map<String, Object> item = new LinkedHashMap<>();
        try {
            SchedulerMetaData metaData = scheduler.getMetaData();
            item.put("schedulerName", metaData.getSchedulerName());
            item.put("schedulerInstanceId", metaData.getSchedulerInstanceId());
            item.put("started", !metaData.isInStandbyMode());
            item.put("shutdown", metaData.isShutdown());
            item.put("jobStoreClass", metaData.getJobStoreClass() == null ? null : metaData.getJobStoreClass().getSimpleName());
            item.put("threadPoolClass", metaData.getThreadPoolClass() == null ? null : metaData.getThreadPoolClass().getSimpleName());
            item.put("threadPoolSize", metaData.getThreadPoolSize());
            item.put("numberOfJobsExecuted", metaData.getNumberOfJobsExecuted());
            item.put("persistent", metaData.isJobStoreSupportsPersistence());
            item.put("clustered", metaData.isJobStoreClustered());
        } catch (SchedulerException ex) {
            item.put("schedulerName", schedulerName);
            item.put("schedulerInstanceId", "UNKNOWN");
            item.put("started", false);
            item.put("shutdown", false);
            item.put("jobStoreClass", "UNKNOWN");
            item.put("threadPoolClass", null);
            item.put("threadPoolSize", 0);
            item.put("numberOfJobsExecuted", 0);
            item.put("persistent", true);
            item.put("clustered", true);
            item.put("error", ex.getMessage());
        }

        item.put("nodeId", nodeId);
        item.put("clusterNodes", loadClusterNodes());
        return item;
    }

    private List<Map<String, Object>> loadClusterNodes() {
        try {
            long now = System.currentTimeMillis();
            return jdbcTemplate.query(
                    "SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM QRTZ_SCHEDULER_STATE WHERE SCHED_NAME = ? ORDER BY LAST_CHECKIN_TIME DESC",
                    (rs, rowNum) -> {
                        long lastCheckinTime = rs.getLong("LAST_CHECKIN_TIME");
                        long checkinInterval = rs.getLong("CHECKIN_INTERVAL");
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("instanceName", rs.getString("INSTANCE_NAME"));
                        item.put("lastCheckinTime", new Date(lastCheckinTime));
                        item.put("checkinInterval", checkinInterval);
                        item.put("alive", now - lastCheckinTime <= Math.max(checkinInterval * 2, 60000L));
                        return item;
                    },
                    schedulerName
            );
        } catch (Exception ex) {
            return List.of();
        }
    }

    private TaskDefinition requireDefinition(String taskKey) {
        return getDefinitions().stream()
                .filter(definition -> definition.taskKey().equals(taskKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task key: " + taskKey));
    }

    private List<TaskDefinition> getDefinitions() {
        return List.of(
                new TaskDefinition(
                        IncrementalSyncService.TASK_ANALYSIS,
                        "Analysis Incremental Sync",
                        QuartzConfig.ANALYSIS_JOB_NAME,
                        QuartzConfig.ANALYSIS_TRIGGER_NAME,
                        analysisCron,
                        "Scan recent access, borrowing, and network logs, then refresh impacted analysis snapshots."
                ),
                new TaskDefinition(
                        IncrementalSyncService.TASK_PROFILE,
                        "Profile Incremental Sync",
                        QuartzConfig.PROFILE_JOB_NAME,
                        QuartzConfig.PROFILE_TRIGGER_NAME,
                        profileCron,
                        "Rebuild clustering and user profiles based on the latest analysis results."
                ),
                new TaskDefinition(
                        IncrementalSyncService.TASK_WARNING,
                        "Warning Incremental Sync",
                        QuartzConfig.WARNING_JOB_NAME,
                        QuartzConfig.WARNING_TRIGGER_NAME,
                        warningCron,
                        "Synchronize warning states after analysis and profile updates."
                )
        );
    }

    private record TaskDefinition(String taskKey,
                                  String taskName,
                                  String jobName,
                                  String triggerName,
                                  String cronExpression,
                                  String description) {
    }

    private record AuditFilter(String action,
                               String operatorKeyword,
                               String resultStatus,
                               String startTimeText,
                               String endTimeText,
                               Date startTime,
                               Date endTime) {
    }

    private record RetryAuditMetadata(String retryReason, String approvalNote) {
        private static final RetryAuditMetadata EMPTY = new RetryAuditMetadata(null, null);
    }

    private record RetryGuard(boolean allowed,
                              String reason,
                              int consecutiveFailureCount,
                              long cooldownRemainingSeconds) {
    }

    public record ExportFile(String fileName, byte[] content) {
    }
}
