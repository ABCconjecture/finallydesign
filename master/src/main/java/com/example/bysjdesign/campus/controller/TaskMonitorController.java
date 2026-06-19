package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.service.AuthService;
import com.example.bysjdesign.service.TaskMonitorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/campus/tasks")
public class TaskMonitorController {

    private final AuthService authService;
    private final TaskMonitorService taskMonitorService;

    public TaskMonitorController(AuthService authService,
                                 TaskMonitorService taskMonitorService) {
        this.authService = authService;
        this.taskMonitorService = taskMonitorService;
    }

    @GetMapping("/status")
    public Map<String, Object> getTaskStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", taskMonitorService.getTaskStatusDashboard());
        return result;
    }

    @GetMapping("/{taskKey}/logs")
    public Map<String, Object> getTaskLogs(@PathVariable String taskKey,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "5") int size) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", taskMonitorService.getTaskLogs(taskKey, page, size));
        } catch (IllegalArgumentException ex) {
            result.put("code", 400);
            result.put("message", ex.getMessage());
        }
        return result;
    }

    @GetMapping("/{taskKey}/audits")
    public Map<String, Object> getTaskAudits(@PathVariable String taskKey,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "5") int size,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) String operator,
                                             @RequestParam(required = false, name = "result") String auditResult,
                                             @RequestParam(required = false) String startTime,
                                             @RequestParam(required = false) String endTime) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", taskMonitorService.getTaskAudits(
                    taskKey,
                    page,
                    size,
                    action,
                    operator,
                    auditResult,
                    startTime,
                    endTime
            ));
        } catch (IllegalArgumentException ex) {
            result.put("code", 400);
            result.put("message", ex.getMessage());
        }
        return result;
    }

    @GetMapping("/{taskKey}/audits/export")
    public ResponseEntity<byte[]> exportTaskAudits(@PathVariable String taskKey,
                                                   @RequestParam(required = false) String action,
                                                   @RequestParam(required = false) String operator,
                                                   @RequestParam(required = false, name = "result") String auditResult,
                                                   @RequestParam(required = false) String startTime,
                                                   @RequestParam(required = false) String endTime,
                                                   HttpSession session) {
        TaskMonitorService.ExportFile exportFile = taskMonitorService.exportTaskAudits(
                taskKey,
                action,
                operator,
                auditResult,
                startTime,
                endTime,
                authService.getCurrentUser(session)
        );
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFile.fileName() + "\"")
                .body(exportFile.content());
    }

    @PostMapping("/{taskKey}/audits/{auditId}/copy-detail")
    public Map<String, Object> copyTaskAuditDetail(@PathVariable String taskKey,
                                                   @PathVariable Long auditId,
                                                   HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.copyTaskAuditDetail(
                taskKey,
                auditId,
                authService.getCurrentUser(session)
        ));
    }

    @GetMapping("/{taskKey}/logs/export")
    public ResponseEntity<byte[]> exportTaskLogs(@PathVariable String taskKey, HttpSession session) {
        TaskMonitorService.ExportFile exportFile = taskMonitorService.exportTaskLogs(
                taskKey,
                authService.getCurrentUser(session)
        );
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFile.fileName() + "\"")
                .body(exportFile.content());
    }

    @PostMapping("/{taskKey}/copy-latest-failure")
    public Map<String, Object> copyLatestFailure(@PathVariable String taskKey, HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.getLatestFailureStack(
                taskKey,
                authService.getCurrentUser(session)
        ));
    }

    @PostMapping("/{taskKey}/trigger")
    public Map<String, Object> triggerTask(@PathVariable String taskKey, HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.triggerTask(
                taskKey,
                authService.getCurrentUser(session)
        ));
    }

    @PostMapping("/{taskKey}/retry")
    public Map<String, Object> retryTask(@PathVariable String taskKey,
                                         @RequestBody(required = false) Map<String, Object> payload,
                                         HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.retryTask(
                taskKey,
                payload == null ? null : asString(payload.get("retryReason")),
                payload == null ? null : asString(payload.get("approvalNote")),
                authService.getCurrentUser(session)
        ));
    }

    @PostMapping("/{taskKey}/pause")
    public Map<String, Object> pauseTask(@PathVariable String taskKey, HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.pauseTask(
                taskKey,
                authService.getCurrentUser(session)
        ));
    }

    @PostMapping("/{taskKey}/resume")
    public Map<String, Object> resumeTask(@PathVariable String taskKey, HttpSession session) {
        return executeTaskAction(() -> taskMonitorService.resumeTask(
                taskKey,
                authService.getCurrentUser(session)
        ));
    }

    private Map<String, Object> executeTaskAction(TaskAction taskAction) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", taskAction.execute());
        } catch (IllegalArgumentException ex) {
            result.put("code", 400);
            result.put("message", ex.getMessage());
        } catch (IllegalStateException ex) {
            result.put("code", 500);
            result.put("message", ex.getMessage());
        }
        return result;
    }

    @FunctionalInterface
    private interface TaskAction {
        Map<String, Object> execute();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
