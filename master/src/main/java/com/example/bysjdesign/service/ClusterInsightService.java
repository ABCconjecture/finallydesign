package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class ClusterInsightService {

    private final UserProfileRepository userProfileRepository;
    private final CampusUserRepository campusUserRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final KMeansService kMeansService;

    public ClusterInsightService(UserProfileRepository userProfileRepository,
                                 CampusUserRepository campusUserRepository,
                                 AnalysisDataRepository analysisDataRepository,
                                 RiskWarningRepository riskWarningRepository,
                                 KMeansService kMeansService) {
        this.userProfileRepository = userProfileRepository;
        this.campusUserRepository = campusUserRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.kMeansService = kMeansService;
    }

    public Map<String, Object> buildClusterInsight(Integer clusterId) {
        List<UserProfile> profiles = userProfileRepository.findByClusterId(clusterId);
        List<Integer> userIds = profiles.stream()
                .map(UserProfile::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Integer, CampusUser> userMap = campusUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(CampusUser::getUserId, user -> user));
        Map<Integer, AnalysisData> analysisMap = analysisDataRepository.findLatestAnalysisForUsers(userIds).stream()
                .collect(Collectors.toMap(AnalysisData::getUserId, analysis -> analysis, (left, right) -> left));
        Map<Integer, List<RiskWarning>> warningMap = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0).stream()
                .filter(warning -> userIds.contains(warning.getUserId()))
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));

        Map<String, Object> clusterOverview = kMeansService.getClusterOverview().stream()
                .filter(item -> Objects.equals(((Number) item.getOrDefault("clusterId", -1)).intValue(), clusterId))
                .findFirst()
                .orElseGet(LinkedHashMap::new);

        Map<String, Double> centroid = readCentroid(clusterOverview.get("centroid"));
        List<ClusterMember> members = userIds.stream()
                .map(userId -> buildMember(userId, userMap.get(userId), analysisMap.get(userId), warningMap.getOrDefault(userId, List.of())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterId", clusterId);
        result.put("clusterLabel", clusterOverview.getOrDefault("label", kMeansService.getClusterLabel(clusterId)));
        result.put("clusterFocus", clusterOverview.getOrDefault("focus", kMeansService.getClusterFocus(clusterId)));
        result.put("radar", buildRadarData(centroid));
        result.put("riskSources", buildRiskSources(members));
        result.put("samples", buildTypicalSamples(members, centroid));
        return result;
    }

    private ClusterMember buildMember(Integer userId, CampusUser user, AnalysisData analysis, List<RiskWarning> warnings) {
        if (user == null || analysis == null) {
            return null;
        }
        return new ClusterMember(userId, user, analysis, warnings);
    }

    private Map<String, Object> buildRadarData(Map<String, Double> centroid) {
        Map<String, Object> radar = new LinkedHashMap<>();
        radar.put("labels", List.of("学习投入", "网络健康", "校园参与", "作息规律", "综合健康"));
        radar.put("values", List.of(
                clamp(value(centroid.get("studyTrafficRatio")) * 240.0),
                clamp(100.0 - value(centroid.get("networkRisk"))),
                clamp((value(centroid.get("libraryAccessCount")) + value(centroid.get("classroomAccessCount"))) * 2.0),
                clamp(100.0 - value(centroid.get("lateReturnCount")) * 12.0 - value(centroid.get("absenteeFlag")) * 20.0),
                clamp(value(centroid.get("healthScore")))
        ));
        return radar;
    }

    private List<Map<String, Object>> buildRiskSources(List<ClusterMember> members) {
        int total = Math.max(1, members.size());
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(buildRiskSource(
                "在线行为波动",
                members,
                member -> value(member.analysis().getAvgOnlineHours()) >= 4.5
                        || value(member.analysis().getNetworkRisk()) >= 25.0
                        || Boolean.TRUE.equals(member.analysis().getAbnormalTrafficFlag()),
                "在线时长偏高、网络健康风险偏高或出现异常流量波动。",
                total
        ));
        sources.add(buildRiskSource(
                "夜间作息波动",
                members,
                member -> value(member.analysis().getLateReturnCount()) >= 4.0
                        || value(member.analysis().getAccessRisk()) >= 20.0
                        || Boolean.TRUE.equals(member.analysis().getAbsenteeFlag()),
                "晚归、作息波动或考勤异常更集中。",
                total
        ));
        sources.add(buildRiskSource(
                "学业投入偏弱",
                members,
                member -> value(member.analysis().getStudyTrafficRatio()) < 0.18
                        || value(member.analysis().getClassroomAccessCount()) < 6.0
                        || value(member.analysis().getLibraryAccessCount()) < 4.0,
                "学习流量占比以及课堂、图书馆参与度偏弱。",
                total
        ));
        sources.add(buildRiskSource(
                "借阅履约风险",
                members,
                member -> value(member.analysis().getUnreturnedCount()) > 0
                        || value(member.analysis().getBorrowRisk()) >= 15.0
                        || value(member.analysis().getAvgBorrowDays()) >= 30.0,
                "借阅未归还、借阅周期偏长或履约风险偏高。",
                total
        ));

        List<Map<String, Object>> ranked = sources.stream()
                .filter(item -> ((Number) item.getOrDefault("count", 0)).intValue() > 0)
                .sorted(Comparator
                        .comparingInt((Map<String, Object> item) -> ((Number) item.get("count")).intValue())
                        .reversed()
                        .thenComparing(item -> String.valueOf(item.getOrDefault("type", ""))))
                .limit(4)
                .collect(Collectors.toList());

        if (!ranked.isEmpty()) {
            return ranked;
        }

        return List.of(Map.of(
                "type", "综合状态稳定",
                "count", 0,
                "percentage", 0.0,
                "description", "当前群体未呈现明显集中的风险来源，整体状态相对稳定。"
        ));
    }

    private Map<String, Object> buildRiskSource(String type,
                                                List<ClusterMember> members,
                                                Predicate<ClusterMember> predicate,
                                                String description,
                                                int total) {
        long count = members.stream().filter(predicate).count();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("count", count);
        item.put("percentage", round2((double) count / total * 100.0));
        item.put("description", description);
        return item;
    }

    private List<Map<String, Object>> buildTypicalSamples(List<ClusterMember> members, Map<String, Double> centroid) {
        if (members.isEmpty()) {
            return List.of();
        }

        ClusterMember stable = members.stream()
                .sorted(Comparator
                        .comparingDouble((ClusterMember member) -> value(member.analysis().getHealthScore())).reversed()
                        .thenComparingDouble(member -> value(member.analysis().getRiskScore()))
                        .thenComparingInt(member -> member.warnings().size()))
                .findFirst()
                .orElse(null);

        ClusterMember risk = members.stream()
                .sorted(Comparator
                        .comparingDouble((ClusterMember member) -> value(member.analysis().getRiskScore())).reversed()
                        .thenComparing(Comparator.comparingInt((ClusterMember member) -> member.warnings().size()).reversed())
                        .thenComparingDouble(member -> value(member.analysis().getHealthScore())))
                .findFirst()
                .orElse(null);

        ClusterMember center = members.stream()
                .min(Comparator.comparingDouble(member -> calculateDistance(member.analysis(), centroid)))
                .orElse(null);

        List<Map<String, Object>> samples = new ArrayList<>();
        if (stable != null) {
            samples.add(buildSample("最稳健样本", stable, "健康度高、风险低，能够代表该群体的稳定状态。"));
        }
        if (risk != null) {
            samples.add(buildSample("最高风险样本", risk, "风险分和预警更集中，适合作为重点关注对象。"));
        }
        if (center != null) {
            samples.add(buildSample("最接近群体中心", center, "与该群体平均特征最接近，适合作为群体典型代表。"));
        }
        return samples;
    }

    private Map<String, Object> buildSample(String type, ClusterMember member, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("userId", member.userId());
        item.put("studentId", member.user().getStudentId());
        item.put("name", member.user().getName());
        item.put("college", member.user().getCollege());
        item.put("major", member.user().getMajor());
        item.put("healthScore", member.analysis().getHealthScore());
        item.put("riskScore", member.analysis().getRiskScore());
        item.put("warningCount", member.warnings().size());
        item.put("topWarningType", member.warnings().isEmpty() ? null : member.warnings().get(0).getWarningType());
        item.put("reason", reason);
        return item;
    }

    private double calculateDistance(AnalysisData analysis, Map<String, Double> centroid) {
        if (centroid.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double distance = 0.0;
        distance += square(value(analysis.getAvgOnlineHours()) - value(centroid.get("avgOnlineHours")));
        distance += square(value(analysis.getStudyTrafficRatio()) - value(centroid.get("studyTrafficRatio")));
        distance += square(value(analysis.getHealthScore()) - value(centroid.get("healthScore")));
        distance += square(value(analysis.getRiskScore()) - value(centroid.get("riskScore")));
        distance += square(value(analysis.getLateReturnCount()) - value(centroid.get("lateReturnCount")));
        distance += square(value(analysis.getClassroomAccessCount()) - value(centroid.get("classroomAccessCount")));
        distance += square(value(analysis.getLibraryAccessCount()) - value(centroid.get("libraryAccessCount")));
        return Math.sqrt(distance);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> readCentroid(Object rawCentroid) {
        if (rawCentroid instanceof Map<?, ?> centroidMap) {
            Map<String, Double> centroid = new HashMap<>();
            centroidMap.forEach((key, value) -> centroid.put(String.valueOf(key), value(value)));
            return centroid;
        }
        return Map.of();
    }

    private double square(double value) {
        return value * value;
    }

    private double value(Number value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private double value(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record ClusterMember(Integer userId, CampusUser user, AnalysisData analysis, List<RiskWarning> warnings) {
    }
}
