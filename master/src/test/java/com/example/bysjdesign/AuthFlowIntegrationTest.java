package com.example.bysjdesign;

import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.TaskExecutionLog;
import com.example.bysjdesign.campus.entity.TaskOperationAudit;
import com.example.bysjdesign.campus.entity.TaskExecutionState;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.TaskExecutionLogRepository;
import com.example.bysjdesign.repository.TaskOperationAuditRepository;
import com.example.bysjdesign.repository.TaskExecutionStateRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.example.bysjdesign.service.ml.PythonClusterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.session.store-type=none")
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest {

    private static final int OPEN_STATUS = 0;
    private static final String BOOTSTRAP_USERNAME = "\u5f6d\u4e8e\u664f";
    private static final String BOOTSTRAP_PASSWORD = "123456";
    private static final DateTimeFormatter AUDIT_TIME_FILTER_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RiskWarningRepository riskWarningRepository;

    @Autowired
    private TaskExecutionStateRepository taskExecutionStateRepository;

    @Autowired
    private TaskExecutionLogRepository taskExecutionLogRepository;

    @Autowired
    private TaskOperationAuditRepository taskOperationAuditRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @MockBean
    private PythonClusterClient pythonClusterClient;

    @BeforeEach
    void setUpPythonClusterClient() {
        when(pythonClusterClient.isEnabled()).thenReturn(true);
        when(pythonClusterClient.cluster(anyList(), anyInt())).thenAnswer(invocation -> {
            List<Map<String, Object>> rows = invocation.getArgument(0);
            Integer clusterCount = invocation.getArgument(1);
            return Optional.of(buildMockClusterResponse(rows, Math.max(1, clusterCount)));
        });
    }

    @Test
    void shouldRejectAnonymousSensitiveOperations() throws Exception {
        RiskWarning openWarning = findAnyOpenWarning();

        assertUnauthorized(post("/api/campus/warning/{warningId}/handle", openWarning.getWarningId())
                .contentType(APPLICATION_JSON)
                .content("{\"handlerRemark\":\"anonymous should fail\"}"));
        assertUnauthorized(post("/api/campus/analysis/1/trigger"));
        assertUnauthorized(post("/api/campus/analysis/trigger-all"));
        assertUnauthorized(post("/api/campus/cluster/trigger"));
        assertUnauthorized(post("/api/campus/warning/trigger"));
        assertUnauthorized(post("/api/campus/tasks/analysis_incremental/pause"));
        assertUnauthorized(post("/api/campus/tasks/analysis_incremental/retry"));
        assertUnauthorized(get("/api/campus/stats"));
        assertUnauthorized(get("/api/campus/warning"));
        assertUnauthorized(get("/api/campus/tasks/status"));
        assertUnauthorized(get("/api/auth/users"));
        assertUnauthorized(patch("/api/auth/users/1/role")
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"));
        assertUnauthorized(put("/api/auth/me/profile")
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"guest\",\"displayName\":\"guest\"}"));
    }

    @Test
    void shouldRedirectAnonymousPageRequestsToLogin() throws Exception {
        mockMvc.perform(get("/dashboard.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html?redirect=dashboard.html"));

        mockMvc.perform(get("/campus/tasks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html?redirect=tasks.html"));
    }

    @Test
    void shouldUseLoggedInUserAsWarningHandler() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(BOOTSTRAP_USERNAME));

        RiskWarning openWarning = findAnyOpenWarning();
        mockMvc.perform(post("/api/campus/warning/{warningId}/handle", openWarning.getWarningId())
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"handlerRemark\":\"integration-login-handler\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.handler", containsString(BOOTSTRAP_USERNAME)))
                .andExpect(jsonPath("$.data.handlerRemark").value("integration-login-handler"));
    }

    @Test
    void shouldReturnPagedWarningDataForWarningPage() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);

        mockMvc.perform(get("/api/campus/warning/page?page=0&size=5").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.totalPages").isNumber());

        mockMvc.perform(get("/api/campus/warning/high-risk/page?page=0&size=3").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(3))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.totalPages").isNumber());
    }

    @Test
    void shouldReturnLowHealthAttentionAndPagedClusterUsers() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        Integer clusterId = userProfileRepository.findAll().stream()
                .map(profile -> profile.getClusterId())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected at least one cluster profile"));
        Integer userId = userProfileRepository.findAll().stream()
                .map(profile -> profile.getUserId())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected at least one analyzed user"));

        mockMvc.perform(get("/api/campus/attention/low-health?size=5").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/campus/warning/low-health-unwarned?size=5").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/campus/warning/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.funnel").isArray())
                .andExpect(jsonPath("$.data.levelDistribution").isArray())
                .andExpect(jsonPath("$.data.ruleRanking").isArray())
                .andExpect(jsonPath("$.data.lowHealthReview").exists());

        mockMvc.perform(get("/api/campus/cluster/{clusterId}/users/page?page=0&size=5", clusterId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.totalPages").isNumber());

        mockMvc.perform(get("/api/campus/cluster/{clusterId}/insight", clusterId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.clusterId").value(clusterId))
                .andExpect(jsonPath("$.data.clusterLabel").isString())
                .andExpect(jsonPath("$.data.radar.labels").isArray())
                .andExpect(jsonPath("$.data.radar.values").isArray())
                .andExpect(jsonPath("$.data.riskSources").isArray())
                .andExpect(jsonPath("$.data.samples").isArray());

        mockMvc.perform(get("/api/campus/analysis/{userId}/insight", userId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.comparison").exists())
                .andExpect(jsonPath("$.data.dimensions").isArray())
                .andExpect(jsonPath("$.data.timeline").isArray())
                .andExpect(jsonPath("$.data.clusterBenchmark").exists());
    }

    @Test
    void shouldSupportAccountManagementAndPasswordFlow() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String uniqueSuffix = String.valueOf(System.currentTimeMillis()).substring(6);
        String createdUsername = "it_admin_" + uniqueSuffix;
        String renamedUsername = "it_ops_" + uniqueSuffix;

        mockMvc.perform(get("/api/auth/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].self").value(true));

        MvcResult createResult = mockMvc.perform(post("/api/auth/users")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"displayName\":\"Test Admin\",\"password\":\"Init12345\",\"role\":\"ADMIN\"}", createdUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(createdUsername))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andReturn();

        Integer createdUserId = JsonPathHelper.readInt(createResult.getResponse().getContentAsString(), "$.data.userId");

        mockMvc.perform(post("/api/auth/users/{userId}/password/reset", createdUserId)
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"newPassword\":\"Reset12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MockHttpSession createdUserSession = loginAndGetSession(createdUsername, "Reset12345");

        mockMvc.perform(put("/api/auth/me/profile")
                        .session(createdUserSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"displayName\":\"Renamed Admin\"}", renamedUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(renamedUsername))
                .andExpect(jsonPath("$.data.displayName").value("Renamed Admin"));

        mockMvc.perform(post("/api/auth/me/password")
                        .session(createdUserSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Reset12345\",\"newPassword\":\"Changed12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        loginAndGetSession(renamedUsername, "Changed12345");

        mockMvc.perform(patch("/api/auth/users/{userId}/status", createdUserId)
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(0));

        assertUnauthorized(get("/api/auth/users").session(createdUserSession));
    }

    @Test
    void shouldRestrictViewerRoleAndRefreshExistingSessionAfterPromotion() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String uniqueSuffix = String.valueOf(System.nanoTime()).substring(6);
        String viewerUsername = "viewer_" + uniqueSuffix;
        seedFailedTaskExecution("analysis_incremental", "Analysis Incremental Sync");

        MvcResult createResult = mockMvc.perform(post("/api/auth/users")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"displayName\":\"Read Only\",\"password\":\"Viewer12345\",\"role\":\"VIEWER\"}", viewerUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("VIEWER"))
                .andReturn();

        Integer viewerUserId = JsonPathHelper.readInt(createResult.getResponse().getContentAsString(), "$.data.userId");
        MockHttpSession viewerSession = loginAndGetSession(viewerUsername, "Viewer12345");

        mockMvc.perform(get("/api/auth/me").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("VIEWER"))
                .andExpect(jsonPath("$.data.admin").value(false));

        mockMvc.perform(get("/api/campus/stats").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/campus/tasks/status").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scheduler.persistent").value(true))
                .andExpect(jsonPath("$.data.scheduler.clusterNodes").isArray())
                .andExpect(jsonPath("$.data.tasks[0].taskKey").exists())
                .andExpect(jsonPath("$.data.tasks[0].recentLogs").isArray());

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/logs?page=0&size=2").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskKey").value("analysis_incremental"))
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/logs/export").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("analysis_incremental")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/copy-latest-failure").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskKey").value("analysis_incremental"))
                .andExpect(jsonPath("$.data.detail", containsString("Mock failure stack for testing")));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits?page=0&size=5").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskKey").value("analysis_incremental"))
                .andExpect(jsonPath("$.data.content[0].action").value("COPY_FAILURE_STACK"))
                .andExpect(jsonPath("$.data.content[0].operatorUsername").value(viewerUsername));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits?page=0&size=5&action=COPY_FAILURE_STACK&operator=" + viewerUsername)
                        .session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.action").value("COPY_FAILURE_STACK"))
                .andExpect(jsonPath("$.data.operatorKeyword").value(viewerUsername))
                .andExpect(jsonPath("$.data.resultStatus").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].action").value("COPY_FAILURE_STACK"));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits?page=0&size=5&result=SUCCESS")
                        .session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resultStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].result").value("SUCCESS"));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits/export?action=COPY_FAILURE_STACK&operator=" + viewerUsername + "&result=SUCCESS")
                        .session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("analysis_incremental")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));

        TaskOperationAudit latestAudit = taskOperationAuditRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected at least one task audit record"));

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/audits/{auditId}/copy-detail", latestAudit.getId())
                        .session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.auditId").value(latestAudit.getId()))
                .andExpect(jsonPath("$.data.content").exists());

        RiskWarning openWarning = findAnyOpenWarning();
        assertForbidden(get("/api/auth/users").session(viewerSession));
        assertForbidden(post("/api/campus/analysis/trigger-all").session(viewerSession));
        assertForbidden(post("/api/campus/tasks/analysis_incremental/retry").session(viewerSession));
        assertForbidden(post("/api/campus/warning/{warningId}/handle", openWarning.getWarningId())
                .session(viewerSession)
                .contentType(APPLICATION_JSON)
                .content("{\"handlerRemark\":\"viewer should fail\"}"));

        mockMvc.perform(put("/api/auth/me/profile")
                        .session(viewerSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"displayName\":\"Viewer Updated\"}", viewerUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.displayName").value("Viewer Updated"));

        mockMvc.perform(patch("/api/auth/users/{userId}/role", viewerUserId)
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(get("/api/auth/me").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.admin").value(true));

        mockMvc.perform(get("/api/auth/users").session(viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void shouldAllowAdminToPauseAndResumeTaskButBlockViewer() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String viewerUsername = "viewer_task_" + String.valueOf(System.nanoTime()).substring(6);

        mockMvc.perform(post("/api/auth/users")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"displayName\":\"Task Viewer\",\"password\":\"Viewer12345\",\"role\":\"VIEWER\"}", viewerUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MockHttpSession viewerSession = loginAndGetSession(viewerUsername, "Viewer12345");

        assertForbidden(post("/api/campus/tasks/analysis_incremental/pause").session(viewerSession));

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/pause").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.action").value("PAUSED"))
                .andExpect(jsonPath("$.data.task.triggerState").value("PAUSED"));

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/resume").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.action").value("RESUMED"))
                .andExpect(jsonPath("$.data.task.triggerState").value("NORMAL"));
    }

    @Test
    void shouldAllowAdminToRetryFailedTaskAndWriteAudit() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        seedFailedTaskExecution("analysis_incremental", "Analysis Incremental Sync");

        String retryReason = "Resolved dependent service issue before retry";
        String approvalNote = "Approved by on-duty administrator";

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/retry")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"retryReason\":\"%s\",\"approvalNote\":\"%s\"}", retryReason, approvalNote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.action").value("RETRIED"))
                .andExpect(jsonPath("$.data.retrySource.status").value("FAILED"))
                .andExpect(jsonPath("$.data.retryReason").value(retryReason))
                .andExpect(jsonPath("$.data.approvalNote").value(approvalNote));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits?page=0&size=5").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].action").value("RETRY"))
                .andExpect(jsonPath("$.data.content[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].operatorUsername").value(BOOTSTRAP_USERNAME))
                .andExpect(jsonPath("$.data.content[0].retryReason").value(retryReason))
                .andExpect(jsonPath("$.data.content[0].approvalNote").value(approvalNote));

        TaskOperationAudit latestRetryAudit = findLatestTaskAudit("analysis_incremental", "RETRY");
        if (latestRetryAudit.getDetail() == null) {
            throw new IllegalStateException("Expected retry audit detail to be present");
        }
        org.junit.jupiter.api.Assertions.assertTrue(latestRetryAudit.getDetail().contains(retryReason));
        org.junit.jupiter.api.Assertions.assertTrue(latestRetryAudit.getDetail().contains(approvalNote));
    }

    @Test
    void shouldFilterTaskAuditsByTimeRange() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        seedFailedTaskExecution("analysis_incremental", "Analysis Incremental Sync");

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/copy-latest-failure").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        TaskOperationAudit latestAudit = findLatestTaskAudit("analysis_incremental", "COPY_FAILURE_STACK");
        String startTime = formatAuditTime(new Date(latestAudit.getCreateTime().getTime() - TimeUnit.MINUTES.toMillis(1)));
        String endTime = formatAuditTime(new Date(latestAudit.getCreateTime().getTime() + TimeUnit.MINUTES.toMillis(1)));

        mockMvc.perform(get("/api/campus/tasks/analysis_incremental/audits?page=0&size=5&action=COPY_FAILURE_STACK&startTime="
                        + startTime + "&endTime=" + endTime)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.action").value("COPY_FAILURE_STACK"))
                .andExpect(jsonPath("$.data.startTime").value(startTime))
                .andExpect(jsonPath("$.data.endTime").value(endTime))
                .andExpect(jsonPath("$.data.content[0].action").value("COPY_FAILURE_STACK"));
    }

    @Test
    void shouldBlockRetryWhenCooldownActive() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        seedFailedTaskExecution("analysis_incremental", "Analysis Incremental Sync");

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/retry")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/retry")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"retryReason\":\"retry too soon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("cooldown")));
    }

    @Test
    void shouldBlockRetryWhenConsecutiveFailuresReachLimit() throws Exception {
        MockHttpSession adminSession = loginAndGetSession(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        seedFailedTaskExecution("analysis_incremental", "Analysis Incremental Sync", 3);

        mockMvc.perform(post("/api/campus/tasks/analysis_incremental/retry")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"retryReason\":\"too many failures\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("failed consecutively")));
    }

    private MockHttpSession loginAndGetSession(String username, String password) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();

        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }

    private PythonClusterClient.ClusterResponse buildMockClusterResponse(List<Map<String, Object>> rows, int clusterCount) {
        PythonClusterClient.ClusterResponse response = new PythonClusterClient.ClusterResponse();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("clusterCount", clusterCount);
        metrics.put("sampleCount", rows.size());

        Map<Integer, Integer> clusterCounts = new LinkedHashMap<>();
        List<PythonClusterClient.ClusterAssignment> assignments = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            Integer userId = ((Number) row.get("userId")).intValue();
            int clusterId = index % clusterCount;
            clusterCounts.merge(clusterId, 1, Integer::sum);

            PythonClusterClient.ClusterAssignment assignment = new PythonClusterClient.ClusterAssignment();
            ReflectionTestUtils.setField(assignment, "userId", userId);
            ReflectionTestUtils.setField(assignment, "clusterId", clusterId);
            ReflectionTestUtils.setField(assignment, "label", "测试行为画像-" + clusterId);
            ReflectionTestUtils.setField(assignment, "summary", "测试行为画像-" + clusterId + "：模型服务模拟结果");
            ReflectionTestUtils.setField(assignment, "focus", "模型服务模拟结果");
            assignments.add(assignment);
        }

        List<PythonClusterClient.ClusterDescriptorPayload> clusters = new ArrayList<>();
        for (int clusterId = 0; clusterId < clusterCount; clusterId++) {
            Map<String, Double> centroid = new LinkedHashMap<>();
            centroid.put("healthScore", 70.0);
            centroid.put("riskScore", 30.0);
            centroid.put("avgOnlineHours", 2.0);
            centroid.put("studyTrafficRatio", 0.25);
            centroid.put("lateReturnCount", 1.0);
            centroid.put("classroomAccessCount", 20.0);
            centroid.put("libraryAccessCount", 20.0);

            PythonClusterClient.ClusterDescriptorPayload cluster = new PythonClusterClient.ClusterDescriptorPayload();
            ReflectionTestUtils.setField(cluster, "clusterId", clusterId);
            ReflectionTestUtils.setField(cluster, "label", "测试行为画像-" + clusterId);
            ReflectionTestUtils.setField(cluster, "summary", "测试行为画像-" + clusterId + "：模型服务模拟结果");
            ReflectionTestUtils.setField(cluster, "focus", "模型服务模拟结果");
            ReflectionTestUtils.setField(cluster, "count", clusterCounts.getOrDefault(clusterId, 0));
            ReflectionTestUtils.setField(cluster, "centroid", centroid);
            clusters.add(cluster);
        }

        ReflectionTestUtils.setField(response, "status", "ok");
        ReflectionTestUtils.setField(response, "algorithm", "mock-python-kmeans");
        ReflectionTestUtils.setField(response, "metrics", metrics);
        ReflectionTestUtils.setField(response, "assignments", assignments);
        ReflectionTestUtils.setField(response, "clusters", clusters);
        return response;
    }

    private void assertUnauthorized(RequestBuilder requestBuilder) throws Exception {
        mockMvc.perform(requestBuilder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private void assertForbidden(RequestBuilder requestBuilder) throws Exception {
        mockMvc.perform(requestBuilder)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private RiskWarning findAnyOpenWarning() {
        List<RiskWarning> warnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(OPEN_STATUS);
        if (warnings.isEmpty()) {
            throw new IllegalStateException("Expected at least one open warning for integration testing");
        }
        return warnings.get(0);
    }

    private void seedFailedTaskExecution(String taskKey, String taskName) {
        seedFailedTaskExecution(taskKey, taskName, 1);
    }

    private void seedFailedTaskExecution(String taskKey, String taskName, int failureCount) {
        long now = System.currentTimeMillis();

        TaskExecutionState state = new TaskExecutionState();
        state.setTaskKey(taskKey);
        state.setTaskName(taskName);
        state.setLastStatus("FAILED");
        state.setLastProcessedCount(0);
        state.setLastMessage("Mock failed execution");
        state.setLastStartedTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(3)));
        state.setLastCompletedTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(2)));
        state.setUpdateTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(2)));
        taskExecutionStateRepository.save(state);

        for (int index = Math.max(1, failureCount) - 1; index >= 0; index--) {
            TaskExecutionLog log = new TaskExecutionLog();
            log.setTaskKey(taskKey);
            log.setTaskName(taskName);
            log.setStatus("FAILED");
            log.setProcessedCount(0);
            log.setMessage("Mock failed execution #" + (index + 1));
            log.setDetail("Mock failure stack for testing");
            log.setStartedTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(3 + index)));
            log.setCompletedTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(2 + index)));
            log.setDurationMs(TimeUnit.MINUTES.toMillis(1));
            log.setSchedulerInstanceId("integration-test-node");
            log.setNodeId("integration-test-node");
            log.setCreateTime(new java.util.Date(now - TimeUnit.MINUTES.toMillis(2 + index)));
            taskExecutionLogRepository.save(log);
        }
    }

    private TaskOperationAudit findLatestTaskAudit(String taskKey, String action) {
        return taskOperationAuditRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(audit -> taskKey.equals(audit.getTaskKey()))
                .filter(audit -> action.equals(audit.getAction()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected task audit for " + taskKey + " / " + action));
    }

    private String formatAuditTime(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(AUDIT_TIME_FILTER_FORMATTER);
    }

    private static final class JsonPathHelper {
        private JsonPathHelper() {
        }

        private static Integer readInt(String json, String path) {
            String marker = "\"userId\":";
            int index = json.indexOf(marker);
            if (index < 0) {
                throw new IllegalStateException("Missing userId in response: " + json);
            }
            int start = index + marker.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            return Integer.parseInt(json.substring(start, end));
        }
    }
}
