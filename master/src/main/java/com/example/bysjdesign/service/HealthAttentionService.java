package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class HealthAttentionService {

    public static final double LOW_HEALTH_THRESHOLD = 60.0;

    private final CampusUserRepository campusUserRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final UserProfileRepository userProfileRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final KMeansService kMeansService;

    public HealthAttentionService(CampusUserRepository campusUserRepository,
                                  AnalysisDataRepository analysisDataRepository,
                                  UserProfileRepository userProfileRepository,
                                  RiskWarningRepository riskWarningRepository,
                                  KMeansService kMeansService) {
        this.campusUserRepository = campusUserRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.userProfileRepository = userProfileRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.kMeansService = kMeansService;
    }

    public List<Map<String, Object>> getLowHealthFocusUsers(int limit) {
        return buildLowHealthUsers(LOW_HEALTH_THRESHOLD, limit, false);
    }

    public List<Map<String, Object>> getLowHealthUnwarnedUsers(int limit) {
        return buildLowHealthUsers(LOW_HEALTH_THRESHOLD, limit, true);
    }

    public Map<Integer, Long> countLowHealthUsersByCluster() {
        return buildLowHealthUsers(LOW_HEALTH_THRESHOLD, 0, false).stream()
                .map(item -> (Number) item.get("clusterId"))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Number::intValue, LinkedHashMap::new, Collectors.counting()));
    }

    private List<Map<String, Object>> buildLowHealthUsers(double threshold, int limit, boolean onlyUnwarned) {
        List<CampusUser> users = campusUserRepository.findAll();
        List<Integer> userIds = users.stream()
                .map(CampusUser::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Integer, AnalysisData> analysisMap = analysisDataRepository.findLatestAnalysisForUsers(userIds).stream()
                .filter(analysis -> analysis.getUserId() != null)
                .collect(Collectors.toMap(AnalysisData::getUserId, analysis -> analysis, (left, right) -> left));
        Map<Integer, UserProfile> profileMap = userProfileRepository.findAll().stream()
                .filter(profile -> profile.getUserId() != null)
                .collect(Collectors.toMap(UserProfile::getUserId, profile -> profile, (left, right) -> left));
        Map<Integer, List<RiskWarning>> openWarningMap = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0).stream()
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));

        return users.stream()
                .map(user -> buildAttentionUser(
                        user,
                        analysisMap.get(user.getUserId()),
                        profileMap.get(user.getUserId()),
                        openWarningMap.getOrDefault(user.getUserId(), List.of())
                ))
                .filter(item -> item.get("healthScore") instanceof Number)
                .filter(item -> ((Number) item.get("healthScore")).doubleValue() < threshold)
                .filter(item -> !onlyUnwarned || ((Number) item.getOrDefault("warningCount", 0)).intValue() == 0)
                .sorted(Comparator
                        .comparingDouble((Map<String, Object> item) -> ((Number) item.get("healthScore")).doubleValue())
                        .thenComparing((Map<String, Object> item) -> toDouble(item.get("riskScore")), Comparator.reverseOrder())
                        .thenComparing(item -> String.valueOf(item.getOrDefault("studentId", ""))))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildAttentionUser(CampusUser user,
                                                   AnalysisData analysis,
                                                   UserProfile profile,
                                                   List<RiskWarning> warnings) {
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, String> tags = extractTags(profile == null ? null : profile.getTags());
        double healthScore = analysis == null || analysis.getHealthScore() == null ? 0.0 : analysis.getHealthScore();
        double riskScore = analysis == null || analysis.getRiskScore() == null ? 0.0 : analysis.getRiskScore();
        int clusterId = profile == null || profile.getClusterId() == null ? -1 : profile.getClusterId();

        item.put("userId", user.getUserId());
        item.put("studentId", user.getStudentId());
        item.put("name", user.getName());
        item.put("college", user.getCollege());
        item.put("major", user.getMajor());
        item.put("clusterId", clusterId >= 0 ? clusterId : null);
        item.put("clusterLabel", tags.getOrDefault("label", clusterId >= 0 ? kMeansService.getClusterLabel(clusterId) : "未聚类"));
        item.put("clusterSummary", tags.getOrDefault("summary", clusterId >= 0 ? kMeansService.getClusterFocus(clusterId) : "暂无画像摘要"));
        item.put("healthScore", round7(healthScore));
        item.put("riskScore", round7(riskScore));
        item.put("warningCount", warnings.size());
        item.put("topWarningType", warnings.isEmpty() ? null : warnings.get(0).getWarningType());
        item.put("statusLabel", resolveStatusLabel(riskScore, warnings.size()));
        item.put("attentionTag", healthScore < 60.0 ? "低健康提醒" : "关注观察");
        item.put("primaryConcern", resolvePrimaryConcern(analysis));
        item.put("focusReasonText", buildFocusReasonText(analysis));
        item.put("suggestion", warnings.isEmpty()
                ? "当前尚未命中开放预警，建议优先查看分析详情并人工复核。"
                : "建议结合开放预警记录与分析详情开展人工干预。");
        return item;
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

    private String resolveStatusLabel(double riskScore, int warningCount) {
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

    private String resolvePrimaryConcern(AnalysisData analysis) {
        if (analysis == null) {
            return "暂无分析详情";
        }
        if (Boolean.TRUE.equals(analysis.getAbnormalTrafficFlag()) || toDouble(analysis.getNetworkRisk()) >= 25.0) {
            return "网络健康";
        }
        if (Boolean.TRUE.equals(analysis.getAbsenteeFlag()) || toDouble(analysis.getLateReturnCount()) >= 5.0) {
            return "作息与考勤";
        }
        if (toDouble(analysis.getStudyTrafficRatio()) < 0.20) {
            return "学业投入";
        }
        if (toDouble(analysis.getUnreturnedCount()) > 0.0) {
            return "借阅履约";
        }
        return "综合关注";
    }

    private String buildFocusReasonText(AnalysisData analysis) {
        if (analysis == null) {
            return "暂无分析数据，建议先执行增量分析。";
        }

        Map<String, String> reasons = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(analysis.getAbnormalTrafficFlag())) {
            reasons.put("networkFlag", "命中异常流量标记");
        } else if (toDouble(analysis.getNetworkRisk()) >= 25.0) {
            reasons.put("networkRisk", "网络风险偏高");
        }
        if (Boolean.TRUE.equals(analysis.getAbsenteeFlag())) {
            reasons.put("attendance", "存在考勤异常标记");
        }
        if (toDouble(analysis.getLateReturnCount()) >= 5.0) {
            reasons.put("lateReturn", "晚归次数较多");
        }
        if (toDouble(analysis.getStudyTrafficRatio()) < 0.20) {
            reasons.put("study", "学习流量占比偏低");
        }
        if (toDouble(analysis.getUnreturnedCount()) > 0.0) {
            reasons.put("credit", "存在未归还图书");
        }

        if (reasons.isEmpty()) {
            return "健康度低于阈值，建议优先人工复核最新分析结果。";
        }
        return String.join("；", reasons.values());
    }

    private Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round7(double value) {
        return Math.round(value * 10_000_000.0) / 10_000_000.0;
    }
}
