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
import com.example.bysjdesign.service.ClusterInsightService;
import com.example.bysjdesign.service.HealthAttentionService;
import com.example.bysjdesign.service.KMeansService;
import com.example.bysjdesign.service.ManualFullTaskService;
import com.example.bysjdesign.service.UserProfileService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
@RequestMapping("/api/campus/cluster")
public class ClusterRestController {

    private final UserProfileRepository userProfileRepository;
    private final CampusUserRepository userRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final UserProfileService userProfileService;
    private final KMeansService kMeansService;
    private final ManualFullTaskService manualFullTaskService;
    private final AuthService authService;
    private final HealthAttentionService healthAttentionService;
    private final ClusterInsightService clusterInsightService;

    public ClusterRestController(UserProfileRepository userProfileRepository,
                                 CampusUserRepository userRepository,
                                 AnalysisDataRepository analysisDataRepository,
                                 RiskWarningRepository riskWarningRepository,
                                 UserProfileService userProfileService,
                                 KMeansService kMeansService,
                                 ManualFullTaskService manualFullTaskService,
                                 AuthService authService,
                                 HealthAttentionService healthAttentionService,
                                 ClusterInsightService clusterInsightService) {
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.userProfileService = userProfileService;
        this.kMeansService = kMeansService;
        this.manualFullTaskService = manualFullTaskService;
        this.authService = authService;
        this.healthAttentionService = healthAttentionService;
        this.clusterInsightService = clusterInsightService;
    }

    @GetMapping("/counts")
    public Map<String, Object> getClusterCounts() {
        return success(kMeansService.getClusterStats());
    }

    @GetMapping("/overview")
    public Map<String, Object> getClusterOverview() {
        List<Map<String, Object>> overview = new ArrayList<>(kMeansService.getClusterOverview());
        Map<Integer, Long> warningUsersByCluster = buildWarningUsersByCluster();
        Map<Integer, Long> lowHealthUsersByCluster = healthAttentionService.countLowHealthUsersByCluster();

        overview.forEach(item -> {
            Integer clusterId = ((Number) item.getOrDefault("clusterId", 0)).intValue();
            long count = ((Number) item.getOrDefault("count", 0)).longValue();
            long warningUsers = warningUsersByCluster.getOrDefault(clusterId, 0L);
            long lowHealthUsers = lowHealthUsersByCluster.getOrDefault(clusterId, 0L);
            item.put("warningUserCount", warningUsers);
            item.put("lowHealthUserCount", lowHealthUsers);
            item.put("warningRate", count == 0 ? 0.0 : round((double) warningUsers / count * 100.0));
        });
        return success(overview);
    }

    @PostMapping("/trigger")
    public Map<String, Object> triggerProfileUpdate(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", manualFullTaskService.submitProfileTask(authService.getCurrentUser(session)));
        } catch (IllegalStateException e) {
            result.put("code", 409);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "提交全量画像任务失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/{clusterId}/users")
    public Map<String, Object> getClusterUsers(@PathVariable Integer clusterId) {
        return success(loadClusterUsers(clusterId));
    }

    @GetMapping("/{clusterId}/users/page")
    public Map<String, Object> getClusterUsersPage(@PathVariable Integer clusterId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.max(1, Math.min(size, 30));
        List<Map<String, Object>> allUsers = loadClusterUsers(clusterId);
        int fromIndex = Math.min(normalizedPage * normalizedSize, allUsers.size());
        int toIndex = Math.min(fromIndex + normalizedSize, allUsers.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", allUsers.subList(fromIndex, toIndex));
        data.put("page", normalizedPage);
        data.put("size", normalizedSize);
        data.put("total", allUsers.size());
        data.put("totalPages", Math.max(1, (int) Math.ceil((double) allUsers.size() / normalizedSize)));
        data.put("hasPrevious", normalizedPage > 0);
        data.put("hasNext", toIndex < allUsers.size());
        return success(data);
    }

    @GetMapping("/{clusterId}/insight")
    public Map<String, Object> getClusterInsight(@PathVariable Integer clusterId) {
        return success(clusterInsightService.buildClusterInsight(clusterId));
    }

    private List<Map<String, Object>> loadClusterUsers(Integer clusterId) {
        List<UserProfile> clusterProfiles = userProfileRepository.findByClusterId(clusterId);
        List<Integer> userIds = clusterProfiles.stream()
                .map(UserProfile::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Integer, CampusUser> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(CampusUser::getUserId, user -> user));
        Map<Integer, AnalysisData> analysisMap = analysisDataRepository.findLatestAnalysisForUsers(userIds).stream()
                .collect(Collectors.toMap(AnalysisData::getUserId, analysis -> analysis));
        Map<Integer, List<RiskWarning>> warningMap = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0).stream()
                .filter(warning -> userIds.contains(warning.getUserId()))
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> userDetails = clusterProfiles.stream()
                .map(profile -> buildClusterUser(profile, userMap, analysisMap, warningMap))
                .filter(user -> !user.isEmpty())
                .sorted(Comparator
                        .comparingDouble((Map<String, Object> item) -> toDouble(item.get("riskScore"))).reversed()
                        .thenComparing(item -> String.valueOf(item.getOrDefault("studentId", ""))))
                .collect(Collectors.toList());
        return userDetails;
    }

    private Map<String, Object> buildClusterUser(UserProfile profile,
                                                 Map<Integer, CampusUser> userMap,
                                                 Map<Integer, AnalysisData> analysisMap,
                                                 Map<Integer, List<RiskWarning>> warningMap) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        CampusUser user = userMap.get(profile.getUserId());
        if (user == null) {
            return userInfo;
        }

        AnalysisData latestAnalysis = analysisMap.get(user.getUserId());
        List<RiskWarning> warnings = warningMap.getOrDefault(user.getUserId(), List.of());
        Map<String, String> tags = extractTags(profile.getTags());

        userInfo.put("userId", user.getUserId());
        userInfo.put("studentId", user.getStudentId());
        userInfo.put("name", user.getName());
        userInfo.put("gender", user.getGender());
        userInfo.put("college", user.getCollege());
        userInfo.put("major", user.getMajor());
        userInfo.put("clusterId", profile.getClusterId());
        userInfo.put("clusterLabel", tags.getOrDefault("label", kMeansService.getClusterLabel(profile.getClusterId())));
        userInfo.put("clusterSummary", tags.getOrDefault("summary", kMeansService.getClusterFocus(profile.getClusterId())));
        userInfo.put("healthScore", latestAnalysis == null ? null : latestAnalysis.getHealthScore());
        userInfo.put("riskScore", latestAnalysis == null ? null : latestAnalysis.getRiskScore());
        userInfo.put("avgOnlineHours", latestAnalysis == null ? null : latestAnalysis.getAvgOnlineHours());
        userInfo.put("studyTrafficRatio", latestAnalysis == null ? null : latestAnalysis.getStudyTrafficRatio());
        userInfo.put("lateReturnCount", latestAnalysis == null ? 0 : latestAnalysis.getLateReturnCount());
        userInfo.put("activeDays", latestAnalysis == null ? 0 : latestAnalysis.getActiveDays());
        userInfo.put("warningCount", warnings.size());
        userInfo.put("topWarningType", warnings.isEmpty() ? null : warnings.get(0).getWarningType());
        userInfo.put("statusLabel", resolveStatusLabel(latestAnalysis, warnings.size()));
        return userInfo;
    }

    private Map<Integer, Long> buildWarningUsersByCluster() {
        Map<Integer, UserProfile> profileMap = userProfileRepository.findAll().stream()
                .filter(profile -> profile.getUserId() != null)
                .collect(Collectors.toMap(UserProfile::getUserId, profile -> profile, (left, right) -> left));

        return riskWarningRepository.findByStatusOrderByCreateTimeDesc(0).stream()
                .map(RiskWarning::getUserId)
                .distinct()
                .map(profileMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(UserProfile::getClusterId, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, String> extractTags(String rawTags) {
        Map<String, String> payload = new HashMap<>();
        if (rawTags == null || rawTags.isBlank()) {
            return payload;
        }
        try {
            JsonElement element = JsonParser.parseString(rawTags);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                object.entrySet().forEach(entry -> payload.put(entry.getKey(), entry.getValue().getAsString()));
            } else if (element.isJsonPrimitive()) {
                payload.put("summary", element.getAsString());
            }
        } catch (Exception ignored) {
            payload.put("summary", rawTags);
        }
        return payload;
    }

    private String resolveStatusLabel(AnalysisData analysis, int warningCount) {
        double riskScore = analysis == null || analysis.getRiskScore() == null ? 0.0 : analysis.getRiskScore();
        if (warningCount > 0 && riskScore >= 65.0) {
            return "重点预警";
        }
        if (riskScore >= 45.0 || warningCount > 0) {
            return "持续关注";
        }
        if (riskScore >= 25.0) {
            return "轻度波动";
        }
        return "状态稳定";
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }
}
