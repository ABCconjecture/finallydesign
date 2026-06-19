package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.example.bysjdesign.service.AuthService;
import com.example.bysjdesign.service.HealthAttentionService;
import com.example.bysjdesign.service.ManualFullTaskService;
import com.example.bysjdesign.service.SessionUser;
import com.example.bysjdesign.service.WarningService;
import com.example.bysjdesign.service.WarningDashboardService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campus/warning")
public class WarningController {

    private static final int OPEN_STATUS = 0;
    private static final int DEFAULT_WARNING_PAGE_SIZE = 10;
    private static final int DEFAULT_HIGH_RISK_PAGE_SIZE = 8;
    private static final int MAX_PAGE_SIZE = 50;

    private final RiskWarningRepository riskWarningRepository;
    private final CampusUserRepository campusUserRepository;
    private final UserProfileRepository userProfileRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final WarningService warningService;
    private final AuthService authService;
    private final ManualFullTaskService manualFullTaskService;
    private final HealthAttentionService healthAttentionService;
    private final WarningDashboardService warningDashboardService;

    public WarningController(RiskWarningRepository riskWarningRepository,
                             CampusUserRepository campusUserRepository,
                             UserProfileRepository userProfileRepository,
                             AnalysisDataRepository analysisDataRepository,
                             WarningService warningService,
                             AuthService authService,
                             ManualFullTaskService manualFullTaskService,
                             HealthAttentionService healthAttentionService,
                             WarningDashboardService warningDashboardService) {
        this.riskWarningRepository = riskWarningRepository;
        this.campusUserRepository = campusUserRepository;
        this.userProfileRepository = userProfileRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.warningService = warningService;
        this.authService = authService;
        this.manualFullTaskService = manualFullTaskService;
        this.healthAttentionService = healthAttentionService;
        this.warningDashboardService = warningDashboardService;
    }

    @GetMapping
    public Map<String, Object> getUserWarnings(@RequestParam(required = false) Integer userId) {
        List<Map<String, Object>> data = loadOpenWarnings(userId).stream()
                .map(this::buildWarningItem)
                .collect(Collectors.toList());
        return success(data);
    }

    @GetMapping("/page")
    public Map<String, Object> getWarningPage(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) Integer userId) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = normalizePageSize(size, DEFAULT_WARNING_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Direction.DESC, "createTime", "warningId")
        );

        Page<RiskWarning> warningPage = (userId != null)
                ? riskWarningRepository.findByUserIdAndStatus(userId, OPEN_STATUS, pageable)
                : riskWarningRepository.findByStatus(OPEN_STATUS, pageable);

        List<Map<String, Object>> records = warningPage.getContent().stream()
                .map(this::buildWarningItem)
                .collect(Collectors.toList());

        Map<String, Object> data = buildPagedData(
                records,
                warningPage.getNumber(),
                warningPage.getSize(),
                warningPage.getTotalElements()
        );
        data.put("hasPrevious", warningPage.hasPrevious());
        data.put("hasNext", warningPage.hasNext());
        return success(data);
    }

    @GetMapping("/stats")
    public Map<String, Object> getWarningStats() {
        List<RiskWarning> activeWarnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(OPEN_STATUS);
        long totalUsers = campusUserRepository.count();
        long warnedUsers = activeWarnings.stream().map(RiskWarning::getUserId).distinct().count();
        long unwarnedUsers = Math.max(0, totalUsers - warnedUsers);

        List<Map<String, Object>> distribution = new ArrayList<>();
        activeWarnings.stream()
                .collect(Collectors.groupingBy(
                        warning -> warning.getWarningType() != null ? warning.getWarningType() : "综合风险",
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .forEach((type, warnings) -> {
                    long userCount = warnings.stream().map(RiskWarning::getUserId).distinct().count();
                    long warningCount = warnings.size();

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", type);
                    item.put("count", userCount);
                    item.put("userCount", userCount);
                    item.put("warningCount", warningCount);
                    item.put("userPercentage", totalUsers == 0 ? 0.0 : round((double) userCount / totalUsers * 100.0));
                    item.put("warningPercentage", activeWarnings.isEmpty() ? 0.0 : round((double) warningCount / activeWarnings.size() * 100.0));
                    distribution.add(item);
                });

        Map<String, Object> noWarningItem = new LinkedHashMap<>();
        noWarningItem.put("type", "未预警");
        noWarningItem.put("count", unwarnedUsers);
        noWarningItem.put("userCount", unwarnedUsers);
        noWarningItem.put("warningCount", 0);
        noWarningItem.put("userPercentage", totalUsers == 0 ? 0.0 : round((double) unwarnedUsers / totalUsers * 100.0));
        noWarningItem.put("warningPercentage", 0.0);
        distribution.add(noWarningItem);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", Map.of(
                "openWarnings", activeWarnings.size(),
                "warnedUsers", warnedUsers,
                "unwarnedUsers", unwarnedUsers,
                "coverage", totalUsers == 0 ? 0.0 : round((double) warnedUsers / totalUsers * 100.0)
        ));
        data.put("types", distribution.stream().map(item -> item.get("type")).collect(Collectors.toList()));
        data.put("counts", distribution.stream().map(item -> item.get("count")).collect(Collectors.toList()));
        data.put("distribution", distribution);
        return success(data);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getWarningDashboard() {
        return success(warningDashboardService.buildDashboard());
    }

    @GetMapping("/high-risk")
    public Map<String, Object> getHighRiskUsers() {
        return success(buildHighRiskUsers());
    }

    @GetMapping("/high-risk/page")
    public Map<String, Object> getHighRiskUsersPage(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "8") int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = normalizePageSize(size, DEFAULT_HIGH_RISK_PAGE_SIZE);
        List<Map<String, Object>> allHighRiskUsers = buildHighRiskUsers();
        int fromIndex = Math.min(normalizedPage * normalizedSize, allHighRiskUsers.size());
        int toIndex = Math.min(fromIndex + normalizedSize, allHighRiskUsers.size());

        Map<String, Object> data = buildPagedData(
                allHighRiskUsers.subList(fromIndex, toIndex),
                normalizedPage,
                normalizedSize,
                allHighRiskUsers.size()
        );
        data.put("hasPrevious", normalizedPage > 0);
        data.put("hasNext", toIndex < allHighRiskUsers.size());
        return success(data);
    }

    @GetMapping("/low-health-unwarned")
    public Map<String, Object> getLowHealthUnwarned(@RequestParam(defaultValue = "6") int size) {
        int normalizedSize = normalizePageSize(size, 6);
        return success(healthAttentionService.getLowHealthUnwarnedUsers(normalizedSize));
    }

    @PostMapping("/trigger")
    public Map<String, Object> triggerWarningCheck(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", manualFullTaskService.submitWarningTask(authService.getCurrentUser(session)));
        } catch (IllegalStateException e) {
            result.put("code", 409);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "提交全量预警任务失败: " + (e.getMessage() != null ? e.getMessage() : "内部服务错误"));
        }
        return result;
    }

    @PostMapping("/{warningId}/handle")
    public Map<String, Object> handleWarning(@PathVariable Integer warningId,
                                             @RequestBody(required = false) Map<String, String> payload,
                                             HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            if (currentUser == null) {
                result.put("code", 401);
                result.put("message", "请先登录后再处理预警");
                return result;
            }

            String handlerRemark = payload == null ? null : payload.get("handlerRemark");
            RiskWarning warning = warningService.handleWarning(warningId, buildHandlerName(currentUser), handlerRemark);
            result.put("code", 200);
            result.put("message", "预警已处理");
            result.put("data", buildWarningItem(warning));
        } catch (IllegalArgumentException e) {
            result.put("code", 404);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "处理失败: " + (e.getMessage() != null ? e.getMessage() : "内部服务错误"));
        }
        return result;
    }

    private List<RiskWarning> loadOpenWarnings(Integer userId) {
        return (userId != null)
                ? riskWarningRepository.findByUserIdOrderByCreateTimeDesc(userId).stream()
                .filter(warning -> Integer.valueOf(OPEN_STATUS).equals(warning.getStatus()))
                .collect(Collectors.toList())
                : riskWarningRepository.findByStatusOrderByCreateTimeDesc(OPEN_STATUS);
    }

    private List<Map<String, Object>> buildHighRiskUsers() {
        Map<Integer, List<RiskWarning>> groupedWarnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(OPEN_STATUS).stream()
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));

        return groupedWarnings.entrySet().stream()
                .map(entry -> buildHighRiskUser(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .filter(item -> parseDouble(item.get("riskScore")) >= 65.0
                        || parseInteger(item.get("maxRiskScore")) >= 65
                        || parseInteger(item.get("warningCount")) >= 2)
                .sorted(Comparator
                        .comparingDouble((Map<String, Object> item) -> parseDouble(item.get("riskScore")))
                        .reversed()
                        .thenComparing(Comparator
                                .comparingInt((Map<String, Object> item) -> parseInteger(item.get("maxRiskScore")))
                                .reversed())
                        .thenComparing(Comparator
                                .comparingInt((Map<String, Object> item) -> parseInteger(item.get("warningCount")))
                                .reversed())
                        .thenComparing(item -> parseInteger(item.get("userId"))))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildWarningItem(RiskWarning warning) {
        Map<String, Object> item = new LinkedHashMap<>();
        CampusUser user = campusUserRepository.findById(warning.getUserId()).orElse(null);
        AnalysisData analysis = analysisDataRepository.findFirstByUserIdOrderByAnalysisDateDescIdDesc(warning.getUserId()).orElse(null);
        item.put("warningId", warning.getWarningId());
        item.put("userId", warning.getUserId());
        item.put("studentId", user == null ? null : user.getStudentId());
        item.put("name", user == null ? null : user.getName());
        item.put("college", user == null ? null : user.getCollege());
        item.put("major", user == null ? null : user.getMajor());
        item.put("warningType", warning.getWarningType());
        item.put("warningLevel", warning.getWarningLevel());
        item.put("riskScore", warning.getRiskScore());
        item.put("riskDescription", warning.getRiskDescription());
        item.put("triggerRule", warning.getTriggerRule());
        item.put("recommendedIntervention", warning.getRecommendedIntervention());
        item.put("analysisRiskScore", analysis == null ? null : analysis.getRiskScore());
        item.put("healthScore", analysis == null ? null : analysis.getHealthScore());
        item.put("status", warning.getStatus());
        item.put("handler", warning.getHandler());
        item.put("handlerRemark", warning.getHandlerRemark());
        item.put("createTime", warning.getCreateTime());
        item.put("handleTime", warning.getHandleTime());
        item.put("updateTime", warning.getUpdateTime());
        item.put("clusterLabel", extractClusterLabel(warning.getUserId()));
        item.put("clusterTag", extractClusterTag(warning.getUserId()));
        item.put("statusLabel", resolveStatusLabel(analysis, warning));
        return item;
    }

    private Map<String, Object> buildHighRiskUser(Integer userId, List<RiskWarning> warnings) {
        CampusUser user = campusUserRepository.findById(userId).orElse(null);
        if (user == null || warnings == null || warnings.isEmpty()) {
            return null;
        }

        RiskWarning topWarning = warnings.stream()
                .filter(Objects::nonNull)
                .max((left, right) -> Integer.compare(
                        severityScore(left.getWarningLevel(), left.getRiskScore()),
                        severityScore(right.getWarningLevel(), right.getRiskScore())
                ))
                .orElse(warnings.get(0));

        AnalysisData analysis = analysisDataRepository.findFirstByUserIdOrderByAnalysisDateDescIdDesc(userId).orElse(null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", user.getUserId());
        item.put("studentId", user.getStudentId());
        item.put("name", user.getName());
        item.put("college", user.getCollege());
        item.put("major", user.getMajor());
        item.put("warningCount", warnings.size());
        item.put("maxRiskLevel", topWarning.getWarningLevel() != null ? topWarning.getWarningLevel() : "HIGH");
        item.put("maxRiskScore", topWarning.getRiskScore() != null ? topWarning.getRiskScore() : 0);
        item.put("topWarningType", topWarning.getWarningType());
        item.put("healthScore", analysis == null ? null : analysis.getHealthScore());
        item.put("riskScore", analysis == null ? null : analysis.getRiskScore());
        item.put("avgOnlineHours", analysis == null ? null : analysis.getAvgOnlineHours());
        item.put("lateReturnCount", analysis == null ? null : analysis.getLateReturnCount());
        item.put("analysisStatus", resolveStatusLabel(analysis, topWarning));
        item.put("clusterLabel", extractClusterLabel(user.getUserId()));
        item.put("clusterTag", extractClusterTag(user.getUserId()));
        return item;
    }

    private Map<String, Object> buildPagedData(List<Map<String, Object>> records,
                                               int page,
                                               int size,
                                               long total) {
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / size);
        Map<String, Object> data = new HashMap<>();
        data.put("records", records);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("totalPages", totalPages);
        return data;
    }

    private int normalizePageSize(int size, int defaultSize) {
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private int parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private int severityScore(String level, Integer riskScore) {
        int base = switch (level == null ? "" : level) {
            case "CRITICAL" -> 300;
            case "HIGH" -> 200;
            case "MEDIUM" -> 100;
            default -> 0;
        };
        return base + (riskScore == null ? 0 : riskScore);
    }

    private String extractClusterTag(Integer userId) {
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null || profile.getTags() == null || profile.getTags().isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(profile.getTags());
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("summary")) {
                    return object.get("summary").getAsString();
                }
            }
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
        } catch (Exception ignored) {
            return profile.getTags();
        }
        return profile.getTags();
    }

    private String extractClusterLabel(Integer userId) {
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null || profile.getTags() == null || profile.getTags().isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(profile.getTags());
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("label")) {
                    return object.get("label").getAsString();
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String resolveStatusLabel(AnalysisData analysis, RiskWarning warning) {
        int warningCount = warning == null ? 0 : 1;
        double riskScore = analysis == null || analysis.getRiskScore() == null ? 0.0 : analysis.getRiskScore();
        if (warningCount > 0 && riskScore >= 65.0) {
            return "重点预警";
        }
        if (warningCount > 0 || riskScore >= 45.0) {
            return "持续关注";
        }
        if (riskScore >= 25.0) {
            return "轻度波动";
        }
        return "状态稳定";
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String buildHandlerName(SessionUser currentUser) {
        if (currentUser.getDisplayName() == null || currentUser.getDisplayName().isBlank()
                || Objects.equals(currentUser.getDisplayName(), currentUser.getUsername())) {
            return currentUser.getUsername();
        }
        return currentUser.getDisplayName() + "(" + currentUser.getUsername() + ")";
    }
}
