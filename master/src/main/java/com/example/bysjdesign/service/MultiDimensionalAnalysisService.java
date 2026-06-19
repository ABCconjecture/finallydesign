package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MultiDimensionalAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(MultiDimensionalAnalysisService.class);

    private final AnalysisDataRepository analysisDataRepository;
    private final CampusUserRepository campusUserRepository;
    private final AnalysisComputationService analysisComputationService;
    private final KMeansService kMeansService;
    private final UserProfileService userProfileService;

    public MultiDimensionalAnalysisService(AnalysisDataRepository analysisDataRepository,
                                           CampusUserRepository campusUserRepository,
                                           AnalysisComputationService analysisComputationService,
                                           KMeansService kMeansService,
                                           UserProfileService userProfileService) {
        this.analysisDataRepository = analysisDataRepository;
        this.campusUserRepository = campusUserRepository;
        this.analysisComputationService = analysisComputationService;
        this.kMeansService = kMeansService;
        this.userProfileService = userProfileService;
    }

    public void analyzeAllUsers() {
        analyzeAllUsers(null);
    }

    public int analyzeAllUsers(ProgressListener progressListener) {
        logger.info("========== 开始全量用户多维分析 ==========");
        List<Integer> userIds = campusUserRepository.findAll().stream()
                .map(CampusUser::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (progressListener != null) {
            progressListener.onProgress(0, userIds.size(), "正在加载用户列表并准备全量分析");
        }

        List<AnalysisData> savedSnapshots = analyzeUsers(userIds, false, progressListener);
        if (progressListener != null) {
            progressListener.onProgress(userIds.size(), userIds.size(), "健康度计算完成，正在同步画像与预警");
        }
        refreshProfilesAndWarnings(savedSnapshots);
        if (progressListener != null) {
            progressListener.onProgress(userIds.size(), userIds.size(), "全量分析已完成");
        }
        logger.info("========== 全量用户多维分析完成 ==========");
        return savedSnapshots.size();
    }

    public AnalysisData analyzeUser(Integer userId) {
        if (userId == null || !campusUserRepository.existsById(userId)) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }

        List<AnalysisData> savedSnapshots = analyzeUsers(List.of(userId), false, null);
        if (savedSnapshots.isEmpty()) {
            throw new IllegalStateException("未生成有效的分析结果");
        }

        AnalysisData saved = savedSnapshots.get(0);
        refreshProfilesAndWarnings(savedSnapshots);
        logger.info("用户 {} 分析完成，riskScore={}, healthScore={}",
                userId,
                saved.getRiskScore(),
                saved.getHealthScore());
        return saved;
    }

    public List<AnalysisData> analyzeUsers(Collection<Integer> userIds, boolean refreshDownstream) {
        return analyzeUsers(userIds, refreshDownstream, null);
    }

    public List<AnalysisData> analyzeUsers(Collection<Integer> userIds,
                                           boolean refreshDownstream,
                                           ProgressListener progressListener) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<AnalysisData> savedSnapshots = new ArrayList<>();
        List<Integer> distinctUserIds = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        int totalCount = distinctUserIds.size();
        int processedCount = 0;
        for (Integer userId : distinctUserIds) {
            if (!campusUserRepository.existsById(userId)) {
                logger.warn("跳过不存在的用户 {}", userId);
                processedCount++;
                if (progressListener != null) {
                    progressListener.onProgress(
                            processedCount,
                            totalCount,
                            "已完成 " + processedCount + " / " + totalCount + " 名用户的健康度计算"
                    );
                }
                continue;
            }

            try {
                AnalysisData snapshot = analysisComputationService.buildSnapshotForUser(userId);
                savedSnapshots.add(saveOrUpdateSnapshot(snapshot));
            } catch (Exception ex) {
                logger.error("用户 {} 分析失败: {}", userId, ex.getMessage(), ex);
            }

            processedCount++;
            if (progressListener != null) {
                progressListener.onProgress(
                        processedCount,
                        totalCount,
                        "已完成 " + processedCount + " / " + totalCount + " 名用户的健康度计算"
                );
            }
        }

        if (refreshDownstream) {
            if (progressListener != null) {
                progressListener.onProgress(totalCount, totalCount, "健康度计算完成，正在同步画像与预警");
            }
            refreshProfilesAndWarnings(savedSnapshots);
            if (progressListener != null) {
                progressListener.onProgress(totalCount, totalCount, "全量分析已完成");
            }
        }
        return savedSnapshots;
    }

    private AnalysisData saveOrUpdateSnapshot(AnalysisData snapshot) {
        return analysisDataRepository.findByUserIdAndAnalysisDate(snapshot.getUserId(), snapshot.getAnalysisDate())
                .map(existing -> mergeSnapshot(existing, snapshot))
                .map(analysisDataRepository::save)
                .orElseGet(() -> analysisDataRepository.save(snapshot));
    }

    private AnalysisData mergeSnapshot(AnalysisData target, AnalysisData source) {
        target.setAnalysisDate(source.getAnalysisDate());
        target.setUpdateTime(source.getUpdateTime());
        target.setAvgOnlineHours(source.getAvgOnlineHours());
        target.setStudyTrafficRatio(source.getStudyTrafficRatio());
        target.setLibraryAccessCount(source.getLibraryAccessCount());
        target.setBorrowCount(source.getBorrowCount());
        target.setRiskScore(source.getRiskScore());
        target.setHealthScore(source.getHealthScore());
        target.setNetworkActivityCount(source.getNetworkActivityCount());
        target.setClassroomAccessCount(source.getClassroomAccessCount());
        target.setLateReturnCount(source.getLateReturnCount());
        target.setActiveDays(source.getActiveDays());
        target.setAvgAccessFrequency(source.getAvgAccessFrequency());
        target.setAvgBorrowDays(source.getAvgBorrowDays());
        target.setUnreturnedCount(source.getUnreturnedCount());
        target.setNetworkRisk(source.getNetworkRisk());
        target.setAccessRisk(source.getAccessRisk());
        target.setBorrowRisk(source.getBorrowRisk());
        target.setAbnormalTrafficFlag(source.getAbnormalTrafficFlag());
        target.setAbsenteeFlag(source.getAbsenteeFlag());
        return target;
    }

    private void refreshProfilesAndWarnings(List<AnalysisData> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        kMeansService.performClustering();
        userProfileService.generateUserProfiles(false);
        snapshots.forEach(analysisComputationService::syncWarningsForAnalysis);
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int processedCount, int totalCount, String message);
    }
}
