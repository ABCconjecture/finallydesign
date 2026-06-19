package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.service.HealthAttentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campus")
public class StatsController {

    private final CampusUserRepository userRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final HealthAttentionService healthAttentionService;

    public StatsController(CampusUserRepository userRepository,
                           RiskWarningRepository riskWarningRepository,
                           AnalysisDataRepository analysisDataRepository,
                           HealthAttentionService healthAttentionService) {
        this.userRepository = userRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.healthAttentionService = healthAttentionService;
    }

    @GetMapping("/stats")
    public Map<String, Object> getSystemStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long totalUsers = userRepository.count();
        stats.put("totalUsers", totalUsers);
        long activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus() == 1)
                .count();
        stats.put("activeUsers", activeUsers);

        List<RiskWarning> openWarnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0);
        List<AnalysisData> latestAnalyses = analysisDataRepository.findLatestAnalysisForUsers(
                userRepository.findAll().stream().map(CampusUser::getUserId).collect(Collectors.toList())
        );
        Set<Integer> warnedUsers = openWarnings.stream().map(RiskWarning::getUserId).collect(Collectors.toSet());
        List<Map<String, Object>> trendData = buildSevenDayTrend(latestAnalyses, openWarnings);

        stats.put("unhandledWarnings", openWarnings.size());
        stats.put("totalWarnings", riskWarningRepository.count());
        stats.put("highRiskUsers", latestAnalyses.stream()
                .filter(analysis -> analysis.getRiskScore() != null && analysis.getRiskScore() >= 65.0)
                .count());
        stats.put("lowHealthUsers", latestAnalyses.stream()
                .filter(analysis -> analysis.getHealthScore() != null && analysis.getHealthScore() < HealthAttentionService.LOW_HEALTH_THRESHOLD)
                .count());
        stats.put("unwarnedUsers", Math.max(0, totalUsers - warnedUsers.size()));
        stats.put("warningCoverage", totalUsers == 0 ? 0.0 : round((double) warnedUsers.size() / totalUsers * 100.0));
        stats.put("maxWarningLevel", openWarnings.stream()
                .map(RiskWarning::getWarningLevel)
                .filter(level -> level != null && !level.isBlank())
                .findFirst()
                .orElse("NONE"));

        double avgHealth = latestAnalyses.stream()
                .mapToDouble(a -> a.getHealthScore() == null ? 0.0 : a.getHealthScore())
                .average()
                .orElse(0.0);
        double avgRisk = latestAnalyses.stream()
                .mapToDouble(a -> a.getRiskScore() == null ? 0.0 : a.getRiskScore())
                .average()
                .orElse(0.0);
        stats.put("avgHealthScore", round7(avgHealth));
        stats.put("avgRiskScore", round7(avgRisk));
        if (trendData.size() >= 2) {
            Map<String, Object> latest = trendData.get(trendData.size() - 1);
            Map<String, Object> previous = trendData.get(trendData.size() - 2);
            String comparisonLabel = String.valueOf(latest.getOrDefault("comparisonLabel", "较上一次分析"));
            stats.put("latestAnalysisDate", latest.get("date"));
            stats.put("previousAnalysisDate", previous.get("date"));
            stats.put("healthComparisonLabel", comparisonLabel);
            stats.put("highRiskComparisonLabel", comparisonLabel);
            stats.put("lowHealthComparisonLabel", comparisonLabel);
            stats.put("healthDeltaComparedToPrevious", round7(toDouble(latest.get("avgHealthScore")) - toDouble(previous.get("avgHealthScore"))));
            stats.put("highRiskDeltaComparedToPrevious", Math.round(toDouble(latest.get("highRiskUsers")) - toDouble(previous.get("highRiskUsers"))));
            stats.put("lowHealthDeltaComparedToPrevious", Math.round(toDouble(latest.get("lowHealthUsers")) - toDouble(previous.get("lowHealthUsers"))));
            stats.put("healthDeltaComparedToYesterday", stats.get("healthDeltaComparedToPrevious"));
            stats.put("warningDeltaComparedToYesterday", stats.get("highRiskDeltaComparedToPrevious"));
        } else {
            stats.put("latestAnalysisDate", trendData.isEmpty() ? null : trendData.get(trendData.size() - 1).get("date"));
            stats.put("previousAnalysisDate", null);
            stats.put("healthComparisonLabel", "较上一分析日");
            stats.put("highRiskComparisonLabel", "较上一分析日");
            stats.put("lowHealthComparisonLabel", "较上一分析日");
            stats.put("healthDeltaComparedToPrevious", 0.0);
            stats.put("highRiskDeltaComparedToPrevious", 0);
            stats.put("lowHealthDeltaComparedToPrevious", 0);
            stats.put("healthDeltaComparedToYesterday", 0.0);
            stats.put("warningDeltaComparedToYesterday", 0);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "成功获取系统统计");
        result.put("data", stats);
        return result;
    }

    @GetMapping("/trend")
    public Map<String, Object> getTrendData() {
        List<AnalysisData> latestAnalyses = analysisDataRepository.findLatestAnalysisForUsers(
                userRepository.findAll().stream().map(CampusUser::getUserId).collect(Collectors.toList())
        );
        List<RiskWarning> openWarnings = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0);
        List<Map<String, Object>> trendData = buildSevenDayTrend(latestAnalyses, openWarnings);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "成功获取趋势数据");
        result.put("data", trendData);
        return result;
    }

    @GetMapping("/attention/low-health")
    public Map<String, Object> getLowHealthAttention(@RequestParam(defaultValue = "6") int size) {
        int normalizedSize = Math.max(1, Math.min(size, 20));
        return success(healthAttentionService.getLowHealthFocusUsers(normalizedSize));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "系统正常");
        result.put("status", "healthy");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/overview")
    public Map<String, Object> getSystemOverview() {
        Map<String, Object> overview = new HashMap<>();
        long totalUsers = userRepository.count();
        overview.put("totalUsers", totalUsers);

        long maleCount = userRepository.findAll().stream()
                .filter(u -> "男".equals(u.getGender()))
                .count();
        overview.put("males", maleCount);
        overview.put("females", totalUsers - maleCount);

        List<Object[]> collegeDistribution = userRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        CampusUser::getCollege,
                        Collectors.counting()
                ))
                .entrySet().stream()
                .map(e -> new Object[]{e.getKey(), e.getValue()})
                .toList();
        overview.put("collegeDistribution", collegeDistribution);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "成功获取系统概览");
        result.put("data", overview);
        return result;
    }

    private List<Map<String, Object>> buildSevenDayTrend(List<AnalysisData> latestAnalyses, List<RiskWarning> openWarnings) {
        Map<LocalDate, List<AnalysisData>> analysisByDay = analysisDataRepository.findAll().stream()
                .filter(analysis -> analysis.getAnalysisDate() != null)
                .collect(Collectors.groupingBy(AnalysisData::getAnalysisDate, TreeMap::new, Collectors.toList()));

        List<LocalDate> snapshotDates = analysisByDay.keySet().stream().sorted().collect(Collectors.toList());
        if (snapshotDates.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("date", LocalDate.now().toString());
            fallback.put("avgHealthScore", round7(latestAnalyses.stream()
                    .mapToDouble(item -> item.getHealthScore() == null ? 0.0 : item.getHealthScore())
                    .average()
                    .orElse(0.0)));
            fallback.put("avgRiskScore", round7(latestAnalyses.stream()
                    .mapToDouble(item -> item.getRiskScore() == null ? 0.0 : item.getRiskScore())
                    .average()
                    .orElse(0.0)));
            fallback.put("highRiskUsers", latestAnalyses.stream()
                    .filter(item -> item.getRiskScore() != null && item.getRiskScore() >= 65.0)
                    .count());
            fallback.put("unhandledWarnings", openWarnings.size());
            fallback.put("healthDelta", 0.0);
            fallback.put("highRiskDelta", 0);
            fallback.put("comparisonLabel", "较上一分析日");
            return List.of(fallback);
        }

        int fromIndex = Math.max(0, snapshotDates.size() - 7);
        List<LocalDate> recentDates = snapshotDates.subList(fromIndex, snapshotDates.size());
        List<Map<String, Object>> trend = new java.util.ArrayList<>();

        for (LocalDate date : recentDates) {
            List<AnalysisData> dailyAnalysis = analysisByDay.getOrDefault(date, List.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("avgHealthScore", round7(dailyAnalysis.stream()
                    .mapToDouble(value -> value.getHealthScore() == null ? 0.0 : value.getHealthScore())
                    .average()
                    .orElse(0.0)));
            item.put("avgRiskScore", round7(dailyAnalysis.stream()
                    .mapToDouble(value -> value.getRiskScore() == null ? 0.0 : value.getRiskScore())
                    .average()
                    .orElse(0.0)));
            item.put("highRiskUsers", dailyAnalysis.stream()
                    .filter(value -> value.getRiskScore() != null && value.getRiskScore() >= 65.0)
                    .count());
            item.put("lowHealthUsers", dailyAnalysis.stream()
                    .filter(value -> value.getHealthScore() != null && value.getHealthScore() < HealthAttentionService.LOW_HEALTH_THRESHOLD)
                    .count());
            item.put("unhandledWarnings", openWarnings.size());
            trend.add(item);
        }

        for (int index = 0; index < trend.size(); index++) {
            Map<String, Object> current = trend.get(index);
            Map<String, Object> previous = index == 0 ? current : trend.get(index - 1);
            LocalDate currentDate = LocalDate.parse(String.valueOf(current.get("date")));
            LocalDate previousDate = LocalDate.parse(String.valueOf(previous.get("date")));
            current.put("healthDelta", round7(toDouble(current.get("avgHealthScore")) - toDouble(previous.get("avgHealthScore"))));
            current.put("highRiskDelta", Math.round(toDouble(current.get("highRiskUsers")) - toDouble(previous.get("highRiskUsers"))));
            current.put("lowHealthDelta", Math.round(toDouble(current.get("lowHealthUsers")) - toDouble(previous.get("lowHealthUsers"))));
            current.put("comparisonLabel", buildComparisonLabel(previousDate, currentDate));
        }
        return trend;
    }

    private String buildComparisonLabel(LocalDate previousDate, LocalDate currentDate) {
        if (previousDate == null || currentDate == null || previousDate.equals(currentDate)) {
            return "较上一分析日";
        }
        return ChronoUnit.DAYS.between(previousDate, currentDate) == 1
                ? "较上一天"
                : "较上一次分析";
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round7(double value) {
        return Math.round(value * 10_000_000.0) / 10_000_000.0;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "成功");
        result.put("data", data);
        return result;
    }
}
