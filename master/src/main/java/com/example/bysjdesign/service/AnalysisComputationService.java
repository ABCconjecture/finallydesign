package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AccessLog;
import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.BorrowLog;
import com.example.bysjdesign.campus.entity.NetworkLog;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AccessLogRepository;
import com.example.bysjdesign.repository.BorrowLogRepository;
import com.example.bysjdesign.repository.NetworkLogRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AnalysisComputationService {

    private static final double HIGH_RISK_THRESHOLD = 65.0;
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final int OPEN_STATUS = 0;
    private static final int CLOSED_STATUS = 1;
    private static final Set<String> MANAGED_WARNING_TYPES = Set.of(
            "综合风险",
            "网络行为异常",
            "行为考勤异常",
            "学业投入异常",
            "群体偏移预警",
            "画像群体风险",
            "网络异常",
            "借阅学习异常",
            "ACADEMIC_RISK"
    );

    private final NetworkLogRepository networkLogRepository;
    private final AccessLogRepository accessLogRepository;
    private final BorrowLogRepository borrowLogRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final UserProfileRepository userProfileRepository;

    public AnalysisComputationService(NetworkLogRepository networkLogRepository,
                                      AccessLogRepository accessLogRepository,
                                      BorrowLogRepository borrowLogRepository,
                                      RiskWarningRepository riskWarningRepository,
                                      UserProfileRepository userProfileRepository) {
        this.networkLogRepository = networkLogRepository;
        this.accessLogRepository = accessLogRepository;
        this.borrowLogRepository = borrowLogRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.userProfileRepository = userProfileRepository;
    }

    public AnalysisData buildSnapshotForUser(Integer userId) {
        List<NetworkLog> networkLogs = networkLogRepository.findByUserId(userId);
        List<AccessLog> accessLogs = accessLogRepository.findByUserId(userId);
        List<BorrowLog> borrowLogs = borrowLogRepository.findByUserId(userId);
        return buildSnapshot(userId, networkLogs, accessLogs, borrowLogs);
    }

    public AnalysisData buildSnapshot(Integer userId,
                                      List<NetworkLog> allNetworkLogs,
                                      List<AccessLog> allAccessLogs,
                                      List<BorrowLog> allBorrowLogs) {
        List<NetworkLog> safeNetworkLogs = allNetworkLogs == null ? Collections.emptyList() : allNetworkLogs;
        List<AccessLog> safeAccessLogs = allAccessLogs == null ? Collections.emptyList() : allAccessLogs;
        List<BorrowLog> safeBorrowLogs = allBorrowLogs == null ? Collections.emptyList() : allBorrowLogs;

        LocalDate anchorDate = resolveAnchorDate(safeNetworkLogs, safeAccessLogs, safeBorrowLogs);
        LocalDate windowEndDate = anchorDate != null ? anchorDate : LocalDate.now();
        LocalDate windowStartDate = windowEndDate.minusDays(29);

        List<NetworkLog> networkLogs = filterNetworkLogs(safeNetworkLogs, windowStartDate, windowEndDate);
        List<AccessLog> accessLogs = filterAccessLogs(safeAccessLogs, windowStartDate, windowEndDate);
        List<BorrowLog> borrowLogs = filterBorrowLogs(safeBorrowLogs, windowStartDate, windowEndDate);

        Set<LocalDate> activeDays = collectActiveDays(networkLogs, accessLogs, borrowLogs);
        LocalDate analysisDate = LocalDate.now();

        long totalDurationSec = networkLogs.stream()
                .map(NetworkLog::getDurationSec)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
        int networkActivityCount = networkLogs.size();
        double avgOnlineHours = activeDays.isEmpty() ? 0.0 : totalDurationSec / 3600.0 / activeDays.size();

        long totalTraffic = networkLogs.stream().mapToLong(NetworkLog::getDataVolume).sum();
        long studyTraffic = networkLogs.stream()
                .filter(log -> isStudyCategory(log.getCategory()))
                .mapToLong(NetworkLog::getDataVolume)
                .sum();
        long leisureTraffic = networkLogs.stream()
                .filter(log -> isLeisureCategory(log.getCategory()))
                .mapToLong(NetworkLog::getDataVolume)
                .sum();
        double studyTrafficRatio = totalTraffic == 0 ? 0.0 : (double) studyTraffic / totalTraffic;
        double leisureTrafficRatio = totalTraffic == 0 ? 0.0 : (double) leisureTraffic / totalTraffic;
        boolean abnormalTrafficFlag = networkLogs.stream().anyMatch(log -> Integer.valueOf(1).equals(log.getIsAbnormal()));

        long libraryAccessCount = accessLogs.stream()
                .filter(log -> containsKeyword(log.getLocationType(), "图书馆"))
                .count();
        long classroomAccessCount = accessLogs.stream()
                .filter(log -> containsKeyword(log.getLocationType(), "教学楼"))
                .count();
        long lateReturnCount = accessLogs.stream()
                .filter(log -> containsKeyword(log.getLocationType(), "宿舍"))
                .filter(log -> getHour(log.getEntryTime()) >= 23)
                .count();
        double avgAccessFrequency = activeDays.isEmpty() ? 0.0 : (double) accessLogs.size() / activeDays.size();
        boolean absenteeFlag = classroomAccessCount < 6 && !activeDays.isEmpty();

        long borrowCount = borrowLogs.size();
        long unreturnedCount = borrowLogs.stream().filter(log -> log.getReturnDate() == null).count();
        double avgBorrowDays = borrowLogs.stream()
                .mapToLong(this::calculateBorrowDays)
                .average()
                .orElse(0.0);

        double rawNetworkRisk = clamp((avgOnlineHours - 4.0) * 8.0, 0.0, 32.0)
                + clamp((leisureTrafficRatio - 0.55) * 80.0, 0.0, 28.0)
                + (abnormalTrafficFlag ? 25.0 : 0.0)
                + (networkLogs.isEmpty() ? 15.0 : 0.0);
        rawNetworkRisk = clamp(rawNetworkRisk, 0.0, 100.0);

        double attendanceScore = weightedScore(List.of(
                new ScoreFactor(scoreByRange(classroomAccessCount, 4.0, 36.0), 0.55),
                new ScoreFactor(scoreByInverseRange(lateReturnCount, 1.0, 8.0), 0.25),
                new ScoreFactor(absenteeFlag ? 20.0 : 100.0, 0.20)
        ));
        double accessRisk = clamp(100.0 - attendanceScore, 0.0, 100.0);

        double creditScore = weightedScore(List.of(
                new ScoreFactor(scoreBorrowCredit(avgBorrowDays, borrowCount), 0.35),
                new ScoreFactor(scoreByInverseRange(unreturnedCount, 0.0, 3.0), 0.65)
        ));
        double borrowRisk = clamp(100.0 - creditScore, 0.0, 100.0);

        double studyInvestmentScore = weightedScore(List.of(
                new ScoreFactor(scoreByRange(studyTrafficRatio, 0.08, 0.45), 0.35),
                new ScoreFactor(scoreByRange(classroomAccessCount, 4.0, 36.0), 0.30),
                new ScoreFactor(scoreByRange(libraryAccessCount, 0.0, 45.0), 0.20),
                new ScoreFactor(scoreBorrowEngagement(borrowCount), 0.15)
        ));

        double routineScore = weightedScore(List.of(
                new ScoreFactor(scoreByInverseRange(lateReturnCount, 1.0, 8.0), 0.45),
                new ScoreFactor(scoreByRange(avgAccessFrequency, 2.0, 12.0), 0.35),
                new ScoreFactor(absenteeFlag ? 20.0 : 100.0, 0.20)
        ));

        double networkHealthScore = weightedScore(List.of(
                new ScoreFactor(scoreByInverseRange(avgOnlineHours, 2.5, 8.0), 0.45),
                new ScoreFactor(abnormalTrafficFlag ? 10.0 : 100.0, 0.35),
                new ScoreFactor(100.0 - rawNetworkRisk, 0.20)
        ));

        double healthScore = clamp(
                studyInvestmentScore * 0.35
                        + routineScore * 0.25
                        + networkHealthScore * 0.25
                        + creditScore * 0.15,
                0.0,
                100.0
        );
        double riskScore = clamp(100.0 - healthScore, 0.0, 100.0);

        AnalysisData data = new AnalysisData();
        data.setUserId(userId);
        data.setAnalysisDate(analysisDate);
        data.setCreateTime(LocalDateTime.now());
        data.setUpdateTime(LocalDateTime.now());
        data.setAvgOnlineHours(round(avgOnlineHours));
        data.setStudyTrafficRatio(round(studyTrafficRatio));
        data.setLibraryAccessCount(toInt(libraryAccessCount));
        data.setBorrowCount(borrowCount);
        data.setNetworkActivityCount(networkActivityCount);
        data.setClassroomAccessCount(toInt(classroomAccessCount));
        data.setLateReturnCount(toInt(lateReturnCount));
        data.setActiveDays(activeDays.size());
        data.setAvgAccessFrequency(round(avgAccessFrequency));
        data.setAvgBorrowDays(round(avgBorrowDays));
        data.setUnreturnedCount(toInt(unreturnedCount));
        data.setNetworkRisk(round(rawNetworkRisk));
        data.setAccessRisk(round(accessRisk));
        data.setBorrowRisk(round(borrowRisk));
        data.setRiskScore(round(riskScore));
        data.setHealthScore(round(healthScore));
        data.setAbnormalTrafficFlag(abnormalTrafficFlag);
        data.setAbsenteeFlag(absenteeFlag);
        return data;
    }

    public boolean isHighRisk(AnalysisData data) {
        return data != null && data.getRiskScore() != null && data.getRiskScore() >= HIGH_RISK_THRESHOLD;
    }

    public Optional<RiskWarning> buildWarning(AnalysisData data) {
        ClusterContext clusterContext = resolveClusterContext(data == null ? null : data.getUserId());
        return evaluateWarningCandidates(data, clusterContext).stream()
                .max(Comparator.comparingInt(WarningCandidate::riskScore))
                .map(candidate -> toWarning(candidate, new RiskWarning(), data, true));
    }

    public Optional<RiskWarning> createOrUpdateOpenWarning(AnalysisData data) {
        return syncWarningsForAnalysis(data).stream()
                .max(Comparator.comparingInt(warning -> safeInt(warning.getRiskScore())));
    }
    public List<RiskWarning> syncWarningsForAnalysis(AnalysisData data) {
        if (data == null || data.getUserId() == null) {
            return Collections.emptyList();
        }

        ClusterContext clusterContext = resolveClusterContext(data.getUserId());
        List<WarningCandidate> candidates = evaluateWarningCandidates(data, clusterContext);
        List<RiskWarning> existingOpenWarnings = riskWarningRepository.findByUserIdAndStatus(data.getUserId(), OPEN_STATUS);
        Map<String, RiskWarning> managedOpenWarnings = existingOpenWarnings.stream()
                .filter(warning -> isManagedWarning(warning.getWarningType()))
                .collect(Collectors.toMap(RiskWarning::getWarningType, warning -> warning, (left, right) -> left, LinkedHashMap::new));

        List<RiskWarning> warningsToSave = new ArrayList<>();
        Date now = new Date();

        for (WarningCandidate candidate : candidates) {
            RiskWarning warning = managedOpenWarnings.remove(candidate.type());
            warningsToSave.add(toWarning(candidate, warning == null ? new RiskWarning() : warning, data, warning == null || warning.getCreateTime() == null));
        }

        for (RiskWarning staleWarning : managedOpenWarnings.values()) {
            closeWarning(staleWarning, now);
            warningsToSave.add(staleWarning);
        }

        if (warningsToSave.isEmpty()) {
            return Collections.emptyList();
        }

        List<RiskWarning> savedWarnings = riskWarningRepository.saveAll(warningsToSave);
        return savedWarnings.stream()
                .filter(warning -> Integer.valueOf(OPEN_STATUS).equals(warning.getStatus()))
                .sorted(this::compareWarningPriority)
                .collect(Collectors.toList());
    }

    private List<WarningCandidate> evaluateWarningCandidates(AnalysisData data, ClusterContext clusterContext) {
        if (data == null || data.getUserId() == null) {
            return Collections.emptyList();
        }

        List<WarningCandidate> candidates = new ArrayList<>();

        addIfPresent(candidates, buildNetworkWarning(data, clusterContext));
        addIfPresent(candidates, buildAttendanceWarning(data, clusterContext));
        addIfPresent(candidates, buildAcademicWarning(data, clusterContext));
        addIfPresent(candidates, buildClusterLinkedWarning(data, clusterContext));
        addIfPresent(candidates, buildOverallWarning(data, clusterContext, candidates));

        return candidates.stream()
                .sorted(this::compareCandidatePriority)
                .collect(Collectors.toList());
    }

    private void addIfPresent(List<WarningCandidate> candidates, WarningCandidate candidate) {
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private WarningCandidate buildNetworkWarning(AnalysisData data, ClusterContext clusterContext) {
        double networkRisk = valueOrZero(data.getNetworkRisk());
        double avgOnlineHours = valueOrZero(data.getAvgOnlineHours());
        boolean abnormalTraffic = Boolean.TRUE.equals(data.getAbnormalTrafficFlag());
        boolean hit = networkRisk >= 50.0 || abnormalTraffic || avgOnlineHours >= 6.0;
        if (!hit) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (abnormalTraffic) {
            reasons.add("检测到异常流量标记");
        }
        if (avgOnlineHours >= 6.0) {
            reasons.add(String.format(Locale.ROOT, "近 30 天日均在线 %.2f 小时", avgOnlineHours));
        }
        if (networkRisk >= 50.0) {
            reasons.add(String.format(Locale.ROOT, "网络健康风险 %.2f", networkRisk));
        }
        if (clusterContext.isRiskCluster()) {
            reasons.add("当前画像群体整体存在较高网络风险");
        }

        int score = clampToInt(Math.round(valueOrZero(data.getRiskScore())
                + (abnormalTraffic ? 8 : 0)
                + (avgOnlineHours >= 7.0 ? 6 : 0)
                + (clusterContext.isRiskCluster() ? 4 : 0)), 60, 100);

        return new WarningCandidate(
                "网络行为异常",
                score,
                appendClusterContext(String.format(Locale.ROOT,
                        "网络行为活跃度与风险显著偏高，日均在线 %.2f 小时，网络风险 %.2f。",
                        avgOnlineHours,
                        networkRisk), clusterContext),
                joinReasons(reasons, "网络行为达到预警阈值"),
                clusterContext.isRiskCluster()
                        ? "建议结合网络中心日志与辅导员走访，重点核查异常流量、夜间长时在线与访问去向。"
                        : "建议复核近 30 天网络访问结构，核查异常流量和超长在线行为。"
        );
    }

    private WarningCandidate buildAttendanceWarning(AnalysisData data, ClusterContext clusterContext) {
        double accessRisk = valueOrZero(data.getAccessRisk());
        boolean absentee = Boolean.TRUE.equals(data.getAbsenteeFlag());
        int lateReturnCount = safeInt(data.getLateReturnCount());
        int classroomAccessCount = safeInt(data.getClassroomAccessCount());
        boolean hit = accessRisk >= 45.0 || absentee || lateReturnCount >= 5 || classroomAccessCount < 6;
        if (!hit) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (absentee) {
            reasons.add("课堂相关门禁活跃度偏低");
        }
        if (classroomAccessCount < 6) {
            reasons.add("近 30 天教学楼通行次数不足 6 次");
        }
        if (lateReturnCount >= 5) {
            reasons.add("晚归次数明显偏多");
        }
        if (accessRisk >= 45.0) {
            reasons.add(String.format(Locale.ROOT, "作息与考勤风险 %.2f", accessRisk));
        }
        if (clusterContext.matchesAny("稳健自律型", "高投入学习型") && (lateReturnCount >= 5 || absentee)) {
            reasons.add("当前行为明显偏离原有稳定画像");
        }

        int score = clampToInt(Math.round(valueOrZero(data.getRiskScore())
                + (absentee ? 7 : 0)
                + (lateReturnCount >= 7 ? 5 : 0)
                + (clusterContext.matchesAny("稳健自律型", "高投入学习型") ? 4 : 0)), 60, 100);

        return new WarningCandidate(
                "行为考勤异常",
                score,
                appendClusterContext(String.format(Locale.ROOT,
                        "作息与考勤表现出现波动，教学楼通行 %d 次，晚归 %d 次。",
                        classroomAccessCount,
                        lateReturnCount), clusterContext),
                joinReasons(reasons, "行为考勤指标达到预警阈值"),
                clusterContext.matchesAny("稳健自律型", "高投入学习型")
                        ? "建议优先核查近期作息与课堂参与变化，判断是否出现学习状态回落或生活节奏失衡。"
                        : "建议结合教学楼、宿舍门禁记录开展访谈，评估出勤、作息和在校活跃度变化。"
        );
    }

    private WarningCandidate buildAcademicWarning(AnalysisData data, ClusterContext clusterContext) {
        double borrowRisk = valueOrZero(data.getBorrowRisk());
        double studyTrafficRatio = valueOrZero(data.getStudyTrafficRatio());
        long borrowCount = safeLong(data.getBorrowCount());
        int libraryAccessCount = safeInt(data.getLibraryAccessCount());
        int classroomAccessCount = safeInt(data.getClassroomAccessCount());
        int unreturnedCount = safeInt(data.getUnreturnedCount());
        boolean lowAcademicEngagement = studyTrafficRatio < 0.18 && classroomAccessCount < 6;
        boolean hit = borrowRisk >= 45.0
                || unreturnedCount > 0
                || lowAcademicEngagement
                || (borrowCount == 0 && libraryAccessCount == 0 && safeInt(data.getActiveDays()) >= 10);
        if (!hit) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (studyTrafficRatio < 0.18) {
            reasons.add(String.format(Locale.ROOT, "学习类流量占比仅 %.2f", studyTrafficRatio));
        }
        if (classroomAccessCount < 6) {
            reasons.add("课堂参与度偏低");
        }
        if (libraryAccessCount == 0 && borrowCount == 0) {
            reasons.add("图书馆与借阅行为均不活跃");
        }
        if (unreturnedCount > 0) {
            reasons.add("存在未归还图书");
        }
        if (clusterContext.matchesAny("高投入学习型", "稳健自律型") && lowAcademicEngagement) {
            reasons.add("当前学业投入低于画像预期");
        }

        int score = clampToInt(Math.round(valueOrZero(data.getRiskScore())
                + (lowAcademicEngagement ? 8 : 0)
                + (unreturnedCount > 0 ? 5 : 0)
                + (clusterContext.matchesAny("高投入学习型", "稳健自律型") ? 4 : 0)), 60, 100);

        return new WarningCandidate(
                "学业投入异常",
                score,
                appendClusterContext(String.format(Locale.ROOT,
                        "学业投入相关指标偏弱，学习流量占比 %.2f，图书馆通行 %d 次，借阅 %d 本。",
                        studyTrafficRatio,
                        libraryAccessCount,
                        borrowCount), clusterContext),
                joinReasons(reasons, "学业投入指标达到预警阈值"),
                clusterContext.matchesAny("高投入学习型", "稳健自律型")
                        ? "建议优先核查近期学习状态回落原因，结合课堂参与、借阅和作息变化安排针对性帮扶。"
                        : "建议关注图书馆、借阅与课堂参与情况，必要时安排阶段性学习辅导。"
        );
    }

    private WarningCandidate buildClusterLinkedWarning(AnalysisData data, ClusterContext clusterContext) {
        if (!clusterContext.available()) {
            return null;
        }

        double riskScore = valueOrZero(data.getRiskScore());
        double studyTrafficRatio = valueOrZero(data.getStudyTrafficRatio());
        int lateReturnCount = safeInt(data.getLateReturnCount());
        int classroomAccessCount = safeInt(data.getClassroomAccessCount());
        int activeDays = safeInt(data.getActiveDays());
        boolean abnormalTraffic = Boolean.TRUE.equals(data.getAbnormalTrafficFlag());

        List<String> reasons = new ArrayList<>();
        String type = "群体偏移预警";
        String recommendation = "建议结合当前画像标签和近 30 天行为变化开展针对性核查，判断是否出现持续偏移。";
        int bonus = 0;

        if (clusterContext.matchesAny("在线风险型")) {
            if (riskScore < 62.0) {
                return null;
            }
            type = "画像群体风险";
            reasons.add("当前画像属于在线风险型群体");
            reasons.add(String.format(Locale.ROOT, "综合风险分 %.2f 仍处于高位", riskScore));
            recommendation = "建议将该用户纳入重点跟踪对象，联合网络中心与院系持续关注异常流量和夜间上网行为。";
            bonus = 8;
        } else if (clusterContext.matchesAny("夜间失衡型") && (abnormalTraffic || lateReturnCount >= 5 || riskScore >= 68.0)) {
            reasons.add("当前行为特征继续偏向夜间失衡型画像");
            if (abnormalTraffic) {
                reasons.add("出现异常流量访问");
            }
            if (lateReturnCount >= 5) {
                reasons.add("晚归行为明显增加");
            }
            recommendation = "建议重点核查近期作息、网络行为和生活事件变化，避免行为进一步失衡。";
            bonus = 6;
        } else if (clusterContext.matchesAny("高投入学习型") && (studyTrafficRatio < 0.22 || classroomAccessCount < 8)) {
            reasons.add("当前学业投入低于高投入学习型画像预期");
            if (studyTrafficRatio < 0.22) {
                reasons.add("学习类流量占比下降");
            }
            if (classroomAccessCount < 8) {
                reasons.add("课堂参与度下降");
            }
            recommendation = "建议尽快确认是否存在课程压力、学习挫折或作息紊乱，并安排学业帮扶。";
            bonus = 6;
        } else if (clusterContext.matchesAny("校园参与型") && activeDays < 12) {
            reasons.add("在校活跃度低于校园参与型画像预期");
            reasons.add(String.format(Locale.ROOT, "近 30 天活跃天数仅 %d 天", activeDays));
            recommendation = "建议核查是否存在请假、离校、情绪波动或社交退缩等情况。";
            bonus = 5;
        } else if (clusterContext.matchesAny("稳健自律型") && riskScore >= 70.0) {
            reasons.add("稳健自律型用户近期风险快速上升");
            recommendation = "建议及时介入，识别导致健康度下降的主导因素，防止优质群体快速失稳。";
            bonus = 5;
        } else {
            return null;
        }

        int score = clampToInt(Math.round(Math.max(riskScore, 65.0) + bonus), 65, 100);
        return new WarningCandidate(
                type,
                score,
                String.format(Locale.ROOT,
                        "画像联动规则命中：当前用户属于“%s”，需要结合群体特征解释近期风险变化。",
                        clusterContext.displaySummary()),
                joinReasons(reasons, "画像联动规则命中"),
                recommendation
        );
    }

    private WarningCandidate buildOverallWarning(AnalysisData data,
                                                 ClusterContext clusterContext,
                                                 List<WarningCandidate> dimensionCandidates) {
        double riskScore = valueOrZero(data.getRiskScore());
        int highDimensionCount = dimensionCandidates.size();
        boolean hit = riskScore >= 68.0 || highDimensionCount >= 2 || (clusterContext.isRiskCluster() && riskScore >= 62.0);
        if (!hit) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (riskScore >= 68.0) {
            reasons.add(String.format(Locale.ROOT, "综合风险分 %.2f 达到预警阈值", riskScore));
        }
        if (highDimensionCount >= 2) {
            reasons.add("同时命中多个维度风险规则");
        }
        if (clusterContext.isRiskCluster()) {
            reasons.add("所在画像群体整体风险偏高");
        }

        String dimensionSummary = dimensionCandidates.stream()
                .map(WarningCandidate::type)
                .distinct()
                .collect(Collectors.joining("、"));
        int score = clampToInt(Math.round(Math.max(riskScore, maxCandidateScore(dimensionCandidates))
                + (highDimensionCount >= 3 ? 4 : 0)
                + (clusterContext.isRiskCluster() ? 3 : 0)), 70, 100);

        return new WarningCandidate(
                "综合风险",
                score,
                appendClusterContext(String.format(Locale.ROOT,
                        "综合风险需要重点关注，风险分 %.2f，健康度 %.7f%s。",
                        riskScore,
                        valueOrZero(data.getHealthScore()),
                        dimensionSummary.isBlank() ? "" : "，涉及 " + dimensionSummary), clusterContext),
                joinReasons(reasons, "综合风险达到预警阈值"),
                dimensionCandidates.size() >= 2
                        ? "建议以辅导员为主导，联合院系与信息中心对网络、作息和学业维度开展联动干预。"
                        : "建议持续跟踪综合风险变化，并针对主导风险维度安排相应干预。"
        );
    }

    private RiskWarning toWarning(WarningCandidate candidate, RiskWarning warning, AnalysisData data, boolean resetCreateTime) {
        Date now = new Date();
        warning.setUserId(data.getUserId());
        warning.setWarningType(candidate.type());
        warning.setWarningLevel(resolveWarningLevel(candidate.riskScore()));
        warning.setRiskScore(candidate.riskScore());
        warning.setRiskDescription(candidate.description());
        warning.setTriggerRule(truncate(candidate.triggerRule(), 200));
        warning.setRecommendedIntervention(candidate.recommendation());
        warning.setStatus(OPEN_STATUS);
        warning.setHandler(null);
        warning.setHandlerRemark(null);
        warning.setHandleTime(null);
        if (resetCreateTime) {
            warning.setCreateTime(now);
        }
        warning.setUpdateTime(now);
        return warning;
    }

    private void closeWarning(RiskWarning warning, Date now) {
        warning.setStatus(CLOSED_STATUS);
        warning.setHandler("system");
        warning.setHandlerRemark("最新分析未再命中该预警规则，系统自动关闭");
        warning.setHandleTime(now);
        warning.setUpdateTime(now);
    }
    private ClusterContext resolveClusterContext(Integer userId) {
        if (userId == null) {
            return ClusterContext.empty();
        }
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null) {
            return ClusterContext.empty();
        }
        return new ClusterContext(profile.getClusterId(), extractTagSummary(profile.getTags()));
    }

    private String extractTagSummary(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(rawTags);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("summary")) {
                    return object.get("summary").getAsString();
                }
                if (object.has("label")) {
                    return object.get("label").getAsString();
                }
            }
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
        } catch (Exception ignored) {
            return rawTags;
        }
        return rawTags;
    }

    private String appendClusterContext(String description, ClusterContext clusterContext) {
        if (!clusterContext.available()) {
            return description;
        }
        return description + " 当前画像：" + clusterContext.displaySummary() + "。";
    }

    private String joinReasons(List<String> reasons, String fallback) {
        List<String> nonBlankReasons = reasons.stream()
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct()
                .collect(Collectors.toList());
        return nonBlankReasons.isEmpty() ? fallback : String.join("；", nonBlankReasons);
    }

    private int maxCandidateScore(List<WarningCandidate> candidates) {
        return candidates.stream().mapToInt(WarningCandidate::riskScore).max().orElse(0);
    }

    private boolean isManagedWarning(String warningType) {
        return warningType != null && MANAGED_WARNING_TYPES.contains(warningType);
    }

    private int compareCandidatePriority(WarningCandidate left, WarningCandidate right) {
        return Integer.compare(right.riskScore(), left.riskScore());
    }

    private int compareWarningPriority(RiskWarning left, RiskWarning right) {
        return Integer.compare(safeInt(right.getRiskScore()), safeInt(left.getRiskScore()));
    }

    private String resolveWarningLevel(int score) {
        if (score >= 90) {
            return "CRITICAL";
        }
        if (score >= 80) {
            return "HIGH";
        }
        if (score >= 65) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private LocalDate resolveAnchorDate(List<NetworkLog> networkLogs,
                                        List<AccessLog> accessLogs,
                                        List<BorrowLog> borrowLogs) {
        return collectActiveDays(networkLogs, accessLogs, borrowLogs).stream()
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private Set<LocalDate> collectActiveDays(List<NetworkLog> networkLogs,
                                             List<AccessLog> accessLogs,
                                             List<BorrowLog> borrowLogs) {
        Set<LocalDate> days = new HashSet<>();
        networkLogs.stream()
                .map(NetworkLog::getSessionStart)
                .map(this::toLocalDate)
                .filter(Objects::nonNull)
                .forEach(days::add);
        accessLogs.stream()
                .map(AccessLog::getEntryTime)
                .map(this::toLocalDate)
                .filter(Objects::nonNull)
                .forEach(days::add);
        borrowLogs.stream()
                .map(BorrowLog::getBorrowDate)
                .map(this::toLocalDate)
                .filter(Objects::nonNull)
                .forEach(days::add);
        return days;
    }

    private List<NetworkLog> filterNetworkLogs(List<NetworkLog> logs, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return logs;
        }
        return logs.stream()
                .filter(log -> isWithinRange(toLocalDate(log.getSessionStart()), startDate, endDate))
                .collect(Collectors.toList());
    }

    private List<AccessLog> filterAccessLogs(List<AccessLog> logs, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return logs;
        }
        return logs.stream()
                .filter(log -> isWithinRange(toLocalDate(log.getEntryTime()), startDate, endDate))
                .collect(Collectors.toList());
    }

    private List<BorrowLog> filterBorrowLogs(List<BorrowLog> logs, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return logs;
        }
        return logs.stream()
                .filter(log -> isWithinRange(toLocalDate(log.getBorrowDate()), startDate, endDate))
                .collect(Collectors.toList());
    }

    private boolean isWithinRange(LocalDate date, LocalDate startDate, LocalDate endDate) {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return date.toInstant().atZone(ZONE_ID).toLocalDate();
    }

    private int getHour(Date date) {
        return date == null ? -1 : date.toInstant().atZone(ZONE_ID).getHour();
    }

    private long calculateBorrowDays(BorrowLog log) {
        LocalDate borrowDate = toLocalDate(log.getBorrowDate());
        LocalDate endDate = toLocalDate(log.getReturnDate());
        if (endDate == null) {
            endDate = toLocalDate(log.getDueDate());
        }
        if (borrowDate == null || endDate == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(borrowDate, endDate));
    }

    private boolean containsKeyword(String value, String keyword) {
        return value != null && keyword != null && value.contains(keyword);
    }

    private boolean isStudyCategory(String category) {
        return containsAny(category, "学习", "学术", "教育", "科研", "课程");
    }

    private boolean isLeisureCategory(String category) {
        return containsAny(category, "游戏", "视频", "娱乐", "社交", "休闲", "直播");
    }

    private boolean containsAny(String value, String... keywords) {
        if (value == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double weightedScore(List<ScoreFactor> factors) {
        return factors.stream().mapToDouble(factor -> factor.score() * factor.weight()).sum();
    }

    private double scoreByRange(double value, double lowerBound, double upperBound) {
        if (value <= lowerBound) {
            return 0.0;
        }
        if (value >= upperBound) {
            return 100.0;
        }
        return (value - lowerBound) / (upperBound - lowerBound) * 100.0;
    }

    private double scoreByInverseRange(double value, double goodThreshold, double badThreshold) {
        if (value <= goodThreshold) {
            return 100.0;
        }
        if (value >= badThreshold) {
            return 0.0;
        }
        return (badThreshold - value) / (badThreshold - goodThreshold) * 100.0;
    }

    private double scoreBorrowEngagement(long borrowCount) {
        if (borrowCount <= 0) {
            return 60.0;
        }
        return clamp(60.0 + borrowCount * 16.0, 60.0, 100.0);
    }

    private double scoreBorrowCredit(double avgBorrowDays, long borrowCount) {
        if (borrowCount <= 0) {
            return 85.0;
        }
        return scoreByInverseRange(avgBorrowDays, 18.0, 60.0);
    }

    private int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampToInt(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000_000.0) / 10_000_000.0;
    }

    private record ScoreFactor(double score, double weight) {
    }

    private record WarningCandidate(String type,
                                    int riskScore,
                                    String description,
                                    String triggerRule,
                                    String recommendation) {
    }

    private record ClusterContext(Integer clusterId, String summary) {

        private static ClusterContext empty() {
            return new ClusterContext(null, null);
        }

        private boolean available() {
            return clusterId != null || (summary != null && !summary.isBlank());
        }

        private String displaySummary() {
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            return clusterId == null ? "未知群体" : "群体 " + clusterId;
        }

        private boolean isRiskCluster() {
            return matchesAny("夜间失衡型", "在线风险型");
        }

        private boolean matchesAny(String... keywords) {
            if (summary == null || summary.isBlank()) {
                return false;
            }
            for (String keyword : keywords) {
                if (summary.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}




