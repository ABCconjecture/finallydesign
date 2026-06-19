package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.service.ml.PythonClusterClient;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KMeansService {

    private static final Logger logger = LoggerFactory.getLogger(KMeansService.class);
    private static final int DEFAULT_CLUSTER_COUNT = 5;
    private static final List<String> CLUSTER_FEATURE_ORDER = List.of(
            "avgOnlineHours",
            "studyTrafficRatio",
            "libraryAccessCount",
            "borrowCount",
            "networkActivityCount",
            "classroomAccessCount",
            "lateReturnCount",
            "activeDays",
            "avgAccessFrequency",
            "avgBorrowDays",
            "unreturnedCount",
            "networkRisk",
            "accessRisk",
            "borrowRisk",
            "abnormalTrafficFlag",
            "absenteeFlag",
            "riskScore",
            "healthScore"
    );

    private final CampusUserRepository userRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final AnalysisComputationService analysisComputationService;
    private final PythonClusterClient pythonClusterClient;
    private final Gson gson = new Gson();

    private final Map<Integer, Integer> userClusterMap = new LinkedHashMap<>();
    private final Map<Integer, String> userTagsMap = new HashMap<>();
    private final Map<Integer, String> userFeatureVectorMap = new HashMap<>();
    private final Map<Integer, ClusterDescriptor> clusterDescriptorMap = new HashMap<>();

    public KMeansService(CampusUserRepository userRepository,
                         AnalysisDataRepository analysisDataRepository,
                         AnalysisComputationService analysisComputationService,
                         PythonClusterClient pythonClusterClient) {
        this.userRepository = userRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.analysisComputationService = analysisComputationService;
        this.pythonClusterClient = pythonClusterClient;
    }

    public synchronized void performClustering() {
        clearCachedResult();

        List<CampusUser> users = userRepository.findAll().stream()
                .filter(user -> user.getUserId() != null)
                .sorted(Comparator.comparing(CampusUser::getUserId))
                .collect(Collectors.toList());

        if (users.isEmpty()) {
            logger.warn("未找到可参与聚类的用户，跳过 K-Means 计算");
            return;
        }

        List<FeatureRow> rows = buildFeatureRows(users);
        if (rows.isEmpty()) {
            logger.warn("没有可用于聚类的特征数据，跳过 K-Means 计算");
            return;
        }

        int actualClusterCount = Math.min(DEFAULT_CLUSTER_COUNT, Math.max(1, countUniqueRows(rows)));
        if (rows.size() == 1 || actualClusterCount == 1) {
            assignSingleCluster(rows);
            logger.info("特征模式单一，已退化为单簇画像，用户数={}", rows.size());
            return;
        }

        if (tryPythonClustering(rows, actualClusterCount)) {
            return;
        }

        clearCachedResult();
        throw new IllegalStateException("Python 聚类服务不可用或响应无效，请先启动 FastAPI 聚类服务后重试");
    }

    public synchronized Map<Integer, Integer> getUserClusterMap() {
        ensureClusterResult();
        return new LinkedHashMap<>(userClusterMap);
    }

    public synchronized String getUserTags(Integer userId) {
        ensureClusterResult();
        return userTagsMap.get(userId);
    }

    public synchronized String getUserFeatureVector(Integer userId) {
        ensureClusterResult();
        return userFeatureVectorMap.get(userId);
    }

    public synchronized Map<String, Integer> getClusterStats() {
        ensureClusterResult();
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (int i = 0; i < DEFAULT_CLUSTER_COUNT; i++) {
            stats.put("cluster_" + i, 0);
        }
        userClusterMap.values().forEach(clusterId -> stats.computeIfPresent("cluster_" + clusterId, (key, value) -> value + 1));
        return stats;
    }

    public synchronized List<Map<String, Object>> getClusterOverview() {
        ensureClusterResult();
        return clusterDescriptorMap.values().stream()
                .sorted(Comparator.comparing(ClusterDescriptor::clusterId))
                .map(descriptor -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("clusterId", descriptor.clusterId());
                    item.put("label", descriptor.label());
                    item.put("summary", descriptor.tagSummary());
                    item.put("focus", descriptor.focus());
                    item.put("count", descriptor.size());
                    item.put("centroid", descriptor.centroid());
                    item.put("avgHealthScore", descriptor.centroid().getOrDefault("healthScore", 0.0));
                    item.put("avgRiskScore", descriptor.centroid().getOrDefault("riskScore", 0.0));
                    item.put("avgOnlineHours", descriptor.centroid().getOrDefault("avgOnlineHours", 0.0));
                    item.put("studyTrafficRatio", descriptor.centroid().getOrDefault("studyTrafficRatio", 0.0));
                    item.put("lateReturnCount", descriptor.centroid().getOrDefault("lateReturnCount", 0.0));
                    item.put("classroomAccessCount", descriptor.centroid().getOrDefault("classroomAccessCount", 0.0));
                    item.put("libraryAccessCount", descriptor.centroid().getOrDefault("libraryAccessCount", 0.0));
                    return item;
                })
                .collect(Collectors.toList());
    }

    public synchronized String getClusterLabel(Integer clusterId) {
        ensureClusterResult();
        return clusterDescriptorMap.getOrDefault(clusterId, defaultDescriptor(clusterId == null ? 0 : clusterId, Collections.emptyMap())).label();
    }

    public synchronized String getClusterFocus(Integer clusterId) {
        ensureClusterResult();
        return clusterDescriptorMap.getOrDefault(clusterId, defaultDescriptor(clusterId == null ? 0 : clusterId, Collections.emptyMap())).focus();
    }

    public synchronized boolean hasClusterResult() {
        return !userClusterMap.isEmpty();
    }

    private boolean tryPythonClustering(List<FeatureRow> rows, int clusterCount) {
        if (!pythonClusterClient.isEnabled()) {
            logger.warn("Python 聚类服务配置为关闭，无法执行聚类");
            return false;
        }

        return pythonClusterClient.cluster(toPythonRows(rows), clusterCount)
                .filter(response -> "ok".equalsIgnoreCase(response.getStatus()))
                .map(response -> applyPythonClusterResult(response, rows))
                .orElse(false);
    }

    private List<Map<String, Object>> toPythonRows(List<FeatureRow> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", row.userId());
                    item.put("features", row.rawFeatures());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private boolean applyPythonClusterResult(PythonClusterClient.ClusterResponse response, List<FeatureRow> rows) {
        Map<Integer, Map<String, Double>> localFeatureMap = rows.stream()
                .collect(Collectors.toMap(FeatureRow::userId, FeatureRow::rawFeatures, (left, right) -> left, LinkedHashMap::new));

        Map<Integer, ClusterDescriptor> descriptors = new LinkedHashMap<>();
        for (PythonClusterClient.ClusterDescriptorPayload cluster : response.getClusters()) {
            if (cluster.getClusterId() == null) {
                continue;
            }
            Map<String, Double> centroid = sanitizeFeatureMap(cluster.getCentroid());
            descriptors.put(cluster.getClusterId(), new ClusterDescriptor(
                    cluster.getClusterId(),
                    defaultIfBlank(cluster.getLabel(), "校园参与型"),
                    defaultIfBlank(cluster.getSummary(), "校园参与型：行为平稳，持续观察"),
                    defaultIfBlank(cluster.getFocus(), "行为平稳，持续观察"),
                    cluster.getCount() == null ? 0 : cluster.getCount(),
                    centroid
            ));
        }

        if (descriptors.isEmpty()) {
            logger.warn("Python 聚类服务未返回有效簇描述");
            return false;
        }

        for (PythonClusterClient.ClusterAssignment assignment : response.getAssignments()) {
            if (assignment.getUserId() == null || assignment.getClusterId() == null) {
                continue;
            }
            Map<String, Double> rawFeatures = localFeatureMap.getOrDefault(
                    assignment.getUserId(),
                    sanitizeFeatureMap(assignment.getFeatures())
            );
            ClusterDescriptor descriptor = descriptors.getOrDefault(
                    assignment.getClusterId(),
                    defaultDescriptor(assignment.getClusterId(), rawFeatures)
            );
            userClusterMap.put(assignment.getUserId(), assignment.getClusterId());
            userTagsMap.put(assignment.getUserId(), defaultIfBlank(assignment.getSummary(), descriptor.tagSummary()));
            userFeatureVectorMap.put(assignment.getUserId(), gson.toJson(rawFeatures));
        }

        if (userClusterMap.isEmpty()) {
            logger.warn("Python 聚类服务未返回有效用户分配");
            clearCachedResult();
            return false;
        }

        clusterDescriptorMap.putAll(descriptors);
        logger.info("Python 聚类完成，algorithm={}, users={}, clusters={}, metrics={}",
                response.getAlgorithm(),
                userClusterMap.size(),
                clusterDescriptorMap.size(),
                response.getMetrics());
        return true;
    }

    private Map<String, Double> sanitizeFeatureMap(Map<String, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            result.put(entry.getKey(), value(entry.getValue()));
        }
        return result;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<FeatureRow> buildFeatureRows(List<CampusUser> users) {
        List<Integer> userIds = users.stream().map(CampusUser::getUserId).collect(Collectors.toList());
        Map<Integer, AnalysisData> latestAnalysisMap = analysisDataRepository.findLatestAnalysisForUsers(userIds).stream()
                .collect(Collectors.toMap(AnalysisData::getUserId, analysis -> analysis, (left, right) -> left, LinkedHashMap::new));

        List<FeatureRow> rows = new ArrayList<>();
        for (CampusUser user : users) {
            AnalysisData analysis = latestAnalysisMap.get(user.getUserId());
            if (analysis == null) {
                analysis = analysisDataRepository.save(analysisComputationService.buildSnapshotForUser(user.getUserId()));
                latestAnalysisMap.put(user.getUserId(), analysis);
            }
            rows.add(new FeatureRow(user.getUserId(), buildRawFeatures(analysis)));
        }
        return rows;
    }

    private Map<String, Double> buildRawFeatures(AnalysisData analysis) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("avgOnlineHours", value(analysis.getAvgOnlineHours()));
        features.put("studyTrafficRatio", value(analysis.getStudyTrafficRatio()));
        features.put("libraryAccessCount", value(analysis.getLibraryAccessCount()));
        features.put("borrowCount", value(analysis.getBorrowCount()));
        features.put("networkActivityCount", value(analysis.getNetworkActivityCount()));
        features.put("classroomAccessCount", value(analysis.getClassroomAccessCount()));
        features.put("lateReturnCount", value(analysis.getLateReturnCount()));
        features.put("activeDays", value(analysis.getActiveDays()));
        features.put("avgAccessFrequency", value(analysis.getAvgAccessFrequency()));
        features.put("avgBorrowDays", value(analysis.getAvgBorrowDays()));
        features.put("unreturnedCount", value(analysis.getUnreturnedCount()));
        features.put("networkRisk", value(analysis.getNetworkRisk()));
        features.put("accessRisk", value(analysis.getAccessRisk()));
        features.put("borrowRisk", value(analysis.getBorrowRisk()));
        features.put("abnormalTrafficFlag", booleanValue(analysis.getAbnormalTrafficFlag()));
        features.put("absenteeFlag", booleanValue(analysis.getAbsenteeFlag()));
        features.put("riskScore", value(analysis.getRiskScore()));
        features.put("healthScore", value(analysis.getHealthScore()));
        return features;
    }

    private int countUniqueRows(List<FeatureRow> rows) {
        Set<String> signatures = new HashSet<>();
        for (FeatureRow row : rows) {
            String signature = CLUSTER_FEATURE_ORDER.stream()
                    .map(feature -> String.format("%.4f", row.rawFeatures().getOrDefault(feature, 0.0)))
                    .collect(Collectors.joining("|"));
            signatures.add(signature);
        }
        return Math.max(1, signatures.size());
    }

    private void assignSingleCluster(List<FeatureRow> rows) {
        Map<String, Double> centroid = averageFeatures(rows);
        ClusterDescriptor descriptor = defaultDescriptor(0, centroid);
        clusterDescriptorMap.put(0, descriptor);
        for (FeatureRow row : rows) {
            userClusterMap.put(row.userId(), 0);
            userTagsMap.put(row.userId(), descriptor.tagSummary());
            userFeatureVectorMap.put(row.userId(), gson.toJson(row.rawFeatures()));
        }
    }

    private Map<String, Double> averageFeatures(List<FeatureRow> rows) {
        Map<String, Double> centroid = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return centroid;
        }

        for (String featureName : rows.get(0).rawFeatures().keySet()) {
            double average = rows.stream()
                    .map(FeatureRow::rawFeatures)
                    .mapToDouble(featureMap -> featureMap.getOrDefault(featureName, 0.0))
                    .average()
                    .orElse(0.0);
            centroid.put(featureName, round(average));
        }
        return centroid;
    }

    private ClusterDescriptor defaultDescriptor(int clusterId, Map<String, Double> centroid) {
        return new ClusterDescriptor(clusterId, "校园参与型", "校园参与型：校园参与场景较广，整体状态平稳", "校园参与场景较广，整体状态平稳", 0, centroid);
    }

    private void ensureClusterResult() {
        if (userClusterMap.isEmpty()) {
            performClustering();
        }
    }

    private void clearCachedResult() {
        userClusterMap.clear();
        userTagsMap.clear();
        userFeatureVectorMap.clear();
        clusterDescriptorMap.clear();
    }

    private double value(Number number) {
        return number == null ? 0.0 : number.doubleValue();
    }

    private double booleanValue(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1.0 : 0.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record FeatureRow(Integer userId, Map<String, Double> rawFeatures) {
    }

    private record ClusterDescriptor(Integer clusterId,
                                     String label,
                                     String tagSummary,
                                     String focus,
                                     int size,
                                     Map<String, Double> centroid) {
    }
}
