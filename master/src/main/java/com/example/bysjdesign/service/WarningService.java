package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
public class WarningService {

    private static final Logger logger = LoggerFactory.getLogger(WarningService.class);
    private static final int OPEN_STATUS = 0;
    private static final int HANDLED_STATUS = 1;

    private final CampusUserRepository campusUserRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final AnalysisComputationService analysisComputationService;
    private final KMeansService kMeansService;
    private final UserProfileService userProfileService;
    private final RiskWarningRepository riskWarningRepository;

    public WarningService(CampusUserRepository campusUserRepository,
                          AnalysisDataRepository analysisDataRepository,
                          AnalysisComputationService analysisComputationService,
                          KMeansService kMeansService,
                          UserProfileService userProfileService,
                          RiskWarningRepository riskWarningRepository) {
        this.campusUserRepository = campusUserRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.analysisComputationService = analysisComputationService;
        this.kMeansService = kMeansService;
        this.userProfileService = userProfileService;
        this.riskWarningRepository = riskWarningRepository;
    }

    public int checkAll() {
        List<Integer> userIds = campusUserRepository.findAll().stream()
                .map(CampusUser::getUserId)
                .filter(id -> id != null)
                .toList();
        if (userIds.isEmpty()) {
            logger.warn("当前没有可用于预警同步的用户数据");
            return 0;
        }

        kMeansService.performClustering();
        userProfileService.generateUserProfiles(false);
        int processedCount = syncWarningsForUsers(userIds);
        logger.info("预警同步完成，已按最新分析快照处理 {} 名用户", processedCount);
        return processedCount;
    }

    public int checkAllWarnings() {
        return checkAll();
    }

    public int syncWarningsForAllUsers(BiConsumer<Integer, Integer> progressConsumer) {
        List<Integer> userIds = campusUserRepository.findAll().stream()
                .map(CampusUser::getUserId)
                .filter(id -> id != null)
                .toList();
        return syncWarningsForUsers(userIds, progressConsumer);
    }

    public int syncWarningsForUsers(Collection<Integer> userIds) {
        return syncWarningsForUsers(userIds, null);
    }

    public int syncWarningsForUsers(Collection<Integer> userIds, BiConsumer<Integer, Integer> progressConsumer) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }

        Set<Integer> distinctUserIds = userIds.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctUserIds.isEmpty()) {
            return 0;
        }

        List<AnalysisData> latestAnalysis = analysisDataRepository.findLatestAnalysisForUsers(List.copyOf(distinctUserIds));
        latestAnalysis.sort(Comparator.comparing(AnalysisData::getUserId));

        int total = latestAnalysis.size();
        int processed = 0;
        for (AnalysisData analysis : latestAnalysis) {
            analysisComputationService.syncWarningsForAnalysis(analysis);
            processed++;
            if (progressConsumer != null) {
                progressConsumer.accept(processed, total);
            }
        }
        return total;
    }

    public RiskWarning handleWarning(Integer warningId, String handler, String handlerRemark) {
        RiskWarning warning = riskWarningRepository.findById(warningId)
                .orElseThrow(() -> new IllegalArgumentException("预警记录不存在: " + warningId));

        Date now = new Date();
        if (Integer.valueOf(HANDLED_STATUS).equals(warning.getStatus())) {
            if ((warning.getHandler() == null || warning.getHandler().isBlank()) && handler != null && !handler.isBlank()) {
                warning.setHandler(handler.trim());
            }
            if ((warning.getHandlerRemark() == null || warning.getHandlerRemark().isBlank()) && handlerRemark != null && !handlerRemark.isBlank()) {
                warning.setHandlerRemark(handlerRemark.trim());
            }
            if (warning.getHandleTime() == null) {
                warning.setHandleTime(now);
            }
            warning.setUpdateTime(now);
            return riskWarningRepository.save(warning);
        }

        warning.setStatus(HANDLED_STATUS);
        warning.setHandler(defaultIfBlank(handler, "manual-handler"));
        warning.setHandlerRemark(defaultIfBlank(handlerRemark, "已人工核查并关闭预警"));
        warning.setHandleTime(now);
        warning.setUpdateTime(now);
        return riskWarningRepository.save(warning);
    }

    public void validateDataConsistency() {
        List<RiskWarning> openWarnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(OPEN_STATUS);
        Map<String, Long> duplicates = openWarnings.stream()
                .collect(Collectors.groupingBy(
                        warning -> buildDuplicateKey(warning.getUserId(), warning.getWarningType()),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        long duplicateCount = duplicates.values().stream().filter(count -> count > 1).count();
        if (duplicateCount > 0) {
            logger.warn("检测到 {} 组重复的未关闭预警，请关注 risk_warning 表的数据一致性", duplicateCount);
        } else {
            logger.info("预警数据一致性检查通过，当前未发现重复的未关闭预警");
        }
    }

    private String buildDuplicateKey(Integer userId, String warningType) {
        return userId + "::" + warningType;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}