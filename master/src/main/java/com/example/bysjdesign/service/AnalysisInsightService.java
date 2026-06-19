package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AnalysisInsightService {

    private final AnalysisDataRepository analysisDataRepository;
    private final UserProfileRepository userProfileRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final KMeansService kMeansService;

    public AnalysisInsightService(AnalysisDataRepository analysisDataRepository,
                                  UserProfileRepository userProfileRepository,
                                  RiskWarningRepository riskWarningRepository,
                                  KMeansService kMeansService) {
        this.analysisDataRepository = analysisDataRepository;
        this.userProfileRepository = userProfileRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.kMeansService = kMeansService;
    }

    public Map<String, Object> buildInsight(Integer userId) {
        List<AnalysisData> history = analysisDataRepository.findByUserId(
                userId,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "analysisDate", "id"))
        );
        AnalysisData latest = history.isEmpty() ? null : history.get(0);
        AnalysisData previous = history.size() > 1 ? history.get(1) : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparison", buildComparison(latest, previous));
        data.put("dimensions", buildDimensions(latest));
        data.put("timeline", buildTimeline(latest, previous, riskWarningRepository.findByUserIdAndStatus(userId, 0)));
        data.put("clusterBenchmark", buildClusterBenchmark(userId, latest));
        return data;
    }

    private Map<String, Object> buildComparison(AnalysisData latest, AnalysisData previous) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        String label = buildComparisonLabel(
                previous == null ? null : previous.getAnalysisDate(),
                latest == null ? null : latest.getAnalysisDate()
        );
        comparison.put("label", label);
        comparison.put("currentDate", latest == null ? null : latest.getAnalysisDate());
        comparison.put("previousDate", previous == null ? null : previous.getAnalysisDate());
        comparison.put("healthDelta", round7(value(latest == null ? null : latest.getHealthScore()) - value(previous == null ? null : previous.getHealthScore())));
        comparison.put("riskDelta", round2(value(latest == null ? null : latest.getRiskScore()) - value(previous == null ? null : previous.getRiskScore())));
        comparison.put("onlineDelta", round2(value(latest == null ? null : latest.getAvgOnlineHours()) - value(previous == null ? null : previous.getAvgOnlineHours())));
        comparison.put("lateReturnDelta", Math.round(value(latest == null ? null : latest.getLateReturnCount()) - value(previous == null ? null : previous.getLateReturnCount())));
        return comparison;
    }

    private List<Map<String, Object>> buildDimensions(AnalysisData latest) {
        if (latest == null) {
            return List.of();
        }

        double studyScore = weightedScore(List.of(
                new ScoreFactor(scoreByRange(value(latest.getStudyTrafficRatio()), 0.08, 0.45), 0.35),
                new ScoreFactor(scoreByRange(value(latest.getClassroomAccessCount()), 4.0, 36.0), 0.30),
                new ScoreFactor(scoreByRange(value(latest.getLibraryAccessCount()), 0.0, 45.0), 0.20),
                new ScoreFactor(scoreBorrowEngagement(value(latest.getBorrowCount())), 0.15)
        ));

        double routineScore = weightedScore(List.of(
                new ScoreFactor(scoreByInverseRange(value(latest.getLateReturnCount()), 1.0, 8.0), 0.45),
                new ScoreFactor(scoreByRange(value(latest.getAvgAccessFrequency()), 2.0, 12.0), 0.35),
                new ScoreFactor(Boolean.TRUE.equals(latest.getAbsenteeFlag()) ? 20.0 : 100.0, 0.20)
        ));

        double networkScore = weightedScore(List.of(
                new ScoreFactor(scoreByInverseRange(value(latest.getAvgOnlineHours()), 2.5, 8.0), 0.45),
                new ScoreFactor(Boolean.TRUE.equals(latest.getAbnormalTrafficFlag()) ? 10.0 : 100.0, 0.35),
                new ScoreFactor(100.0 - value(latest.getNetworkRisk()), 0.20)
        ));

        double creditScore = weightedScore(List.of(
                new ScoreFactor(scoreBorrowCredit(value(latest.getAvgBorrowDays()), value(latest.getBorrowCount())), 0.35),
                new ScoreFactor(scoreByInverseRange(value(latest.getUnreturnedCount()), 0.0, 3.0), 0.65)
        ));

        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(buildDimension(
                "study",
                "学业投入",
                studyScore,
                String.format(Locale.ROOT, "学习流量占比 %.2f%%，课堂通行 %d 次，图书馆通行 %d 次。",
                        value(latest.getStudyTrafficRatio()) * 100.0,
                        safeInt(latest.getClassroomAccessCount()),
                        safeInt(latest.getLibraryAccessCount())),
                studyScore >= 75.0 ? "当前学习投入稳定，可继续保持。" : "建议结合课堂参与和图书馆使用情况，重点提升学习投入。"
        ));
        dimensions.add(buildDimension(
                "routine",
                "作息规律",
                routineScore,
                String.format(Locale.ROOT, "晚归 %d 次，门禁频率 %.2f，考勤异常标记 %s。",
                        safeInt(latest.getLateReturnCount()),
                        value(latest.getAvgAccessFrequency()),
                        Boolean.TRUE.equals(latest.getAbsenteeFlag()) ? "有" : "无"),
                routineScore >= 75.0 ? "作息规律总体稳定。" : "建议重点关注晚归和课堂参与变化。"
        ));
        dimensions.add(buildDimension(
                "network",
                "网络健康",
                networkScore,
                String.format(Locale.ROOT, "日均在线 %.2f 小时，网络风险 %.2f，异常流量标记 %s。",
                        value(latest.getAvgOnlineHours()),
                        value(latest.getNetworkRisk()),
                        Boolean.TRUE.equals(latest.getAbnormalTrafficFlag()) ? "有" : "无"),
                networkScore >= 75.0 ? "网络行为整体平稳。" : "建议结合异常流量和在线时长继续复核。"
        ));
        dimensions.add(buildDimension(
                "credit",
                "履约信用",
                creditScore,
                String.format(Locale.ROOT, "借阅 %d 本，平均借阅 %.2f 天，未归还 %d 本。",
                        safeInt(latest.getBorrowCount()),
                        value(latest.getAvgBorrowDays()),
                        safeInt(latest.getUnreturnedCount())),
                creditScore >= 75.0 ? "借阅履约情况较好。" : "建议重点核查未归还图书与借阅周期。"
        ));
        return dimensions;
    }

    private Map<String, Object> buildDimension(String key, String label, double score, String detail, String suggestion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("score", round2(clamp(score, 0.0, 100.0)));
        item.put("detail", detail);
        item.put("suggestion", suggestion);
        return item;
    }

    private List<Map<String, Object>> buildTimeline(AnalysisData latest, AnalysisData previous, List<RiskWarning> warnings) {
        List<TimelineEvent> events = new ArrayList<>();
        LocalDateTime latestTime = resolveTime(latest);
        if (latest != null) {
            events.add(new TimelineEvent(
                    latestTime,
                    "ANALYSIS",
                    "INFO",
                    "完成最新分析快照",
                    String.format(Locale.ROOT, "健康度 %.7f，风险分 %.2f。", value(latest.getHealthScore()), value(latest.getRiskScore()))
            ));
        }
        if (latest != null && previous != null) {
            double healthDelta = value(latest.getHealthScore()) - value(previous.getHealthScore());
            if (Math.abs(healthDelta) > 0.0000001) {
                events.add(new TimelineEvent(
                        latestTime,
                        "COMPARISON",
                        healthDelta > 0 ? "SUCCESS" : "WARNING",
                        healthDelta > 0 ? "健康度较上一周期上升" : "健康度较上一周期下降",
                        String.format(Locale.ROOT, "%s %.7f，对比日期 %s -> %s。",
                                healthDelta > 0 ? "变化值 +" : "变化值 ",
                                round7(healthDelta),
                                previous.getAnalysisDate(),
                                latest.getAnalysisDate())
                ));
            }
        }
        if (latest != null && Boolean.TRUE.equals(latest.getAbnormalTrafficFlag())) {
            events.add(new TimelineEvent(
                    latestTime,
                    "NETWORK",
                    "DANGER",
                    "命中异常流量标记",
                    String.format(Locale.ROOT, "网络风险 %.2f，建议优先检查最近网络访问异常。", value(latest.getNetworkRisk()))
            ));
        }
        if (latest != null && value(latest.getLateReturnCount()) >= 5.0) {
            events.add(new TimelineEvent(
                    latestTime,
                    "ROUTINE",
                    "WARNING",
                    "夜间作息波动明显",
                    String.format(Locale.ROOT, "近一个分析窗口内晚归 %d 次，建议结合宿舍门禁继续复核。", safeInt(latest.getLateReturnCount()))
            ));
        }
        if (latest != null && Boolean.TRUE.equals(latest.getAbsenteeFlag())) {
            events.add(new TimelineEvent(
                    latestTime,
                    "ATTENDANCE",
                    "WARNING",
                    "课堂参与不足",
                    String.format(Locale.ROOT, "课堂相关通行仅 %d 次，存在考勤异常标记。", safeInt(latest.getClassroomAccessCount()))
            ));
        }
        if (latest != null && value(latest.getUnreturnedCount()) > 0.0) {
            events.add(new TimelineEvent(
                    latestTime,
                    "BORROW",
                    "INFO",
                    "借阅履约待关注",
                    String.format(Locale.ROOT, "当前仍有 %d 本图书未归还。", safeInt(latest.getUnreturnedCount()))
            ));
        }
        warnings.stream()
                .limit(4)
                .forEach(warning -> events.add(new TimelineEvent(
                        toLocalDateTime(warning.getCreateTime()),
                        "WARNING",
                        normalizeSeverity(warning.getWarningLevel()),
                        warning.getWarningType(),
                        firstNonBlank(warning.getTriggerRule(), warning.getRiskDescription(), "触发了新的风险预警。")
                )));

        return events.stream()
                .sorted(Comparator.comparing(TimelineEvent::time, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(8)
                .map(this::toTimelineMap)
                .toList();
    }

    private Map<String, Object> buildClusterBenchmark(Integer userId, AnalysisData latest) {
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null || profile.getClusterId() == null || latest == null) {
            return Map.of(
                    "clusterLabel", "未聚类",
                    "clusterFocus", "当前用户暂无可对比的群体画像。",
                    "metrics", List.of()
            );
        }

        Map<String, Object> clusterOverview = kMeansService.getClusterOverview().stream()
                .filter(item -> Objects.equals(((Number) item.getOrDefault("clusterId", -1)).intValue(), profile.getClusterId()))
                .findFirst()
                .orElseGet(LinkedHashMap::new);
        Map<String, Double> centroid = readCentroid(clusterOverview.get("centroid"));

        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(buildBenchmarkMetric("健康度", value(latest.getHealthScore()), value(centroid.get("healthScore")), "higher"));
        metrics.add(buildBenchmarkMetric("风险分", value(latest.getRiskScore()), value(centroid.get("riskScore")), "lower"));
        metrics.add(buildBenchmarkMetric("日均在线", value(latest.getAvgOnlineHours()), value(centroid.get("avgOnlineHours")), "lower"));
        metrics.add(buildBenchmarkMetric("学习流量占比", value(latest.getStudyTrafficRatio()) * 100.0, value(centroid.get("studyTrafficRatio")) * 100.0, "higher"));
        metrics.add(buildBenchmarkMetric("晚归次数", value(latest.getLateReturnCount()), value(centroid.get("lateReturnCount")), "lower"));
        metrics.add(buildBenchmarkMetric("课堂通行", value(latest.getClassroomAccessCount()), value(centroid.get("classroomAccessCount")), "higher"));

        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("clusterLabel", clusterOverview.getOrDefault("label", kMeansService.getClusterLabel(profile.getClusterId())));
        benchmark.put("clusterFocus", clusterOverview.getOrDefault("focus", kMeansService.getClusterFocus(profile.getClusterId())));
        benchmark.put("metrics", metrics);
        return benchmark;
    }

    private Map<String, Object> buildBenchmarkMetric(String label, double userValue, double clusterValue, String preferredDirection) {
        double delta = userValue - clusterValue;
        String state;
        if (Math.abs(delta) < 0.0000001) {
            state = "flat";
        } else if ("higher".equals(preferredDirection)) {
            state = delta > 0 ? "up" : "down";
        } else {
            state = delta < 0 ? "up" : "down";
        }

        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("label", label);
        metric.put("userValue", round2(userValue));
        metric.put("clusterValue", round2(clusterValue));
        metric.put("delta", round2(delta));
        metric.put("state", state);
        metric.put("preferredDirection", preferredDirection);
        return metric;
    }

    private Map<String, Object> toTimelineMap(TimelineEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", event.time() == null ? null : event.time().toString());
        item.put("category", event.category());
        item.put("severity", event.severity());
        item.put("title", event.title());
        item.put("description", event.description());
        return item;
    }

    private String buildComparisonLabel(LocalDate previousDate, LocalDate currentDate) {
        if (previousDate == null || currentDate == null || previousDate.equals(currentDate)) {
            return "较上一分析周期";
        }
        return ChronoUnit.DAYS.between(previousDate, currentDate) == 1 ? "较上一天" : "较上一次分析";
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

    private LocalDateTime resolveTime(AnalysisData analysisData) {
        if (analysisData == null) {
            return null;
        }
        if (analysisData.getUpdateTime() != null) {
            return analysisData.getUpdateTime();
        }
        if (analysisData.getCreateTime() != null) {
            return analysisData.getCreateTime();
        }
        return analysisData.getAnalysisDate() == null ? null : analysisData.getAnalysisDate().atStartOfDay();
    }

    private LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
    }

    private String normalizeSeverity(String warningLevel) {
        String normalized = String.valueOf(warningLevel == null ? "" : warningLevel).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL", "HIGH" -> "DANGER";
            case "MEDIUM" -> "WARNING";
            default -> "INFO";
        };
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
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

    private double scoreBorrowEngagement(double borrowCount) {
        if (borrowCount <= 0.0) {
            return 60.0;
        }
        return clamp(60.0 + borrowCount * 16.0, 60.0, 100.0);
    }

    private double scoreBorrowCredit(double avgBorrowDays, double borrowCount) {
        if (borrowCount <= 0.0) {
            return 85.0;
        }
        return scoreByInverseRange(avgBorrowDays, 18.0, 60.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double value(Number value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private double value(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private int safeInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round7(double value) {
        return Math.round(value * 10_000_000.0) / 10_000_000.0;
    }

    private record ScoreFactor(double score, double weight) {
    }

    private record TimelineEvent(LocalDateTime time,
                                 String category,
                                 String severity,
                                 String title,
                                 String description) {
    }
}
