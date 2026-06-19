package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WarningDashboardService {

    private static final int OPEN_STATUS = 0;
    private static final int HANDLED_STATUS = 1;
    private static final List<String> LEVEL_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final RiskWarningRepository riskWarningRepository;
    private final CampusUserRepository campusUserRepository;
    private final HealthAttentionService healthAttentionService;

    public WarningDashboardService(RiskWarningRepository riskWarningRepository,
                                   CampusUserRepository campusUserRepository,
                                   HealthAttentionService healthAttentionService) {
        this.riskWarningRepository = riskWarningRepository;
        this.campusUserRepository = campusUserRepository;
        this.healthAttentionService = healthAttentionService;
    }

    public Map<String, Object> buildDashboard() {
        List<RiskWarning> allWarnings = riskWarningRepository.findAll();
        List<RiskWarning> openWarnings = allWarnings.stream()
                .filter(warning -> Objects.equals(warning.getStatus(), OPEN_STATUS))
                .toList();
        List<RiskWarning> handledWarnings = allWarnings.stream()
                .filter(warning -> Objects.equals(warning.getStatus(), HANDLED_STATUS))
                .toList();

        long totalUsers = campusUserRepository.count();
        Set<Integer> warnedUsers = openWarnings.stream()
                .map(RiskWarning::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        long openHighRiskUsers = groupWarningsByUser(openWarnings).values().stream()
                .filter(this::isHighRiskWarningGroup)
                .count();

        List<Map<String, Object>> lowHealthReviewUsers = healthAttentionService.getLowHealthUnwarnedUsers(0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("funnel", buildFunnel(allWarnings.size(), openWarnings.size(), handledWarnings.size(), openHighRiskUsers, warnedUsers.size(), totalUsers));
        data.put("levelDistribution", buildLevelDistribution(openWarnings));
        data.put("ruleRanking", buildRuleRanking(openWarnings));
        data.put("lowHealthReview", buildLowHealthReview(lowHealthReviewUsers));
        return data;
    }

    private List<Map<String, Object>> buildFunnel(int totalWarnings,
                                                  int openWarnings,
                                                  int handledWarnings,
                                                  long highRiskUsers,
                                                  int warnedUsers,
                                                  long totalUsers) {
        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(buildFunnelStage("totalWarnings", "累计预警记录", totalWarnings, "系统历史累计生成的全部预警记录。"));
        funnel.add(buildFunnelStage("openWarnings", "当前开放预警", openWarnings, "仍待处理、待复核的开放预警。"));
        funnel.add(buildFunnelStage("handledWarnings", "已处理预警", handledWarnings, "已由管理员人工处理的预警记录。"));
        funnel.add(buildFunnelStage("warnedUsers", "涉及预警用户", warnedUsers, totalUsers == 0
                ? "当前暂无用户数据。"
                : String.format(Locale.ROOT, "当前开放预警覆盖 %.2f%% 用户。", percentage(warnedUsers, totalUsers))));
        funnel.add(buildFunnelStage("highRiskUsers", "重点风险用户", highRiskUsers, "命中高等级预警、风险分高或多预警叠加的重点关注用户。"));
        return funnel;
    }

    private Map<String, Object> buildFunnelStage(String key, String label, long value, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("value", value);
        item.put("description", description);
        return item;
    }

    private List<Map<String, Object>> buildLevelDistribution(List<RiskWarning> openWarnings) {
        Map<String, Long> grouped = openWarnings.stream()
                .collect(Collectors.groupingBy(
                        warning -> normalizeLevel(warning.getWarningLevel()),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        List<Map<String, Object>> levels = new ArrayList<>();
        for (String level : LEVEL_ORDER) {
            long count = grouped.getOrDefault(level, 0L);
            if (count == 0L && !grouped.containsKey(level)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", level);
            item.put("label", translateLevel(level));
            item.put("count", count);
            item.put("percentage", openWarnings.isEmpty() ? 0.0 : round2((double) count / openWarnings.size() * 100.0));
            levels.add(item);
        }

        grouped.entrySet().stream()
                .filter(entry -> !LEVEL_ORDER.contains(entry.getKey()))
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("level", entry.getKey());
                    item.put("label", translateLevel(entry.getKey()));
                    item.put("count", entry.getValue());
                    item.put("percentage", openWarnings.isEmpty() ? 0.0 : round2((double) entry.getValue() / openWarnings.size() * 100.0));
                    levels.add(item);
                });

        return levels;
    }

    private List<Map<String, Object>> buildRuleRanking(List<RiskWarning> openWarnings) {
        return openWarnings.stream()
                .collect(Collectors.groupingBy(this::resolveRuleLabel, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> {
                    List<RiskWarning> warnings = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rule", entry.getKey());
                    item.put("count", warnings.size());
                    item.put("userCount", warnings.stream().map(RiskWarning::getUserId).filter(Objects::nonNull).distinct().count());
                    item.put("avgRiskScore", round2(warnings.stream()
                            .map(RiskWarning::getRiskScore)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0.0)));
                    item.put("topLevel", warnings.stream()
                            .map(RiskWarning::getWarningLevel)
                            .map(this::normalizeLevel)
                            .min(Comparator.comparingInt(this::levelPriority))
                            .orElse("LOW"));
                    item.put("latestTime", warnings.stream()
                            .map(RiskWarning::getCreateTime)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null));
                    return item;
                })
                .sorted(Comparator
                        .comparingLong((Map<String, Object> item) -> toLong(item.get("count"))).reversed()
                        .thenComparing(Comparator.comparingLong((Map<String, Object> item) -> toLong(item.get("userCount"))).reversed())
                        .thenComparing(Comparator.comparingDouble((Map<String, Object> item) -> toDouble(item.get("avgRiskScore"))).reversed()))
                .limit(6)
                .toList();
    }

    private Map<String, Object> buildLowHealthReview(List<Map<String, Object>> users) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", users.size());
        summary.put("avgHealthScore", round7(users.stream()
                .mapToDouble(item -> toDouble(item.get("healthScore")))
                .average()
                .orElse(0.0)));
        summary.put("avgRiskScore", round2(users.stream()
                .mapToDouble(item -> toDouble(item.get("riskScore")))
                .average()
                .orElse(0.0)));

        Map<String, Object> lowestHealthUser = users.stream()
                .min(Comparator.comparingDouble(item -> toDouble(item.get("healthScore"))))
                .map(this::copyMap)
                .orElseGet(LinkedHashMap::new);
        summary.put("lowestHealthUser", lowestHealthUser);

        String topClusterLabel = users.stream()
                .map(item -> String.valueOf(item.getOrDefault("clusterLabel", "未聚类")))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse("暂无");
        summary.put("topClusterLabel", topClusterLabel);

        summary.put("statusBreakdown", users.stream()
                .map(item -> String.valueOf(item.getOrDefault("statusLabel", "待分析")))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting())));
        return summary;
    }

    private Map<Integer, List<RiskWarning>> groupWarningsByUser(Collection<RiskWarning> warnings) {
        return warnings.stream()
                .filter(warning -> warning.getUserId() != null)
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));
    }

    private boolean isHighRiskWarningGroup(List<RiskWarning> warnings) {
        return warnings.size() >= 2 || warnings.stream().anyMatch(warning ->
                "CRITICAL".equalsIgnoreCase(normalizeLevel(warning.getWarningLevel()))
                        || "HIGH".equalsIgnoreCase(normalizeLevel(warning.getWarningLevel()))
                        || (warning.getRiskScore() != null && warning.getRiskScore() >= 65));
    }

    private String resolveRuleLabel(RiskWarning warning) {
        if (warning.getTriggerRule() != null && !warning.getTriggerRule().isBlank()) {
            return warning.getTriggerRule().trim();
        }
        if (warning.getWarningType() != null && !warning.getWarningType().isBlank()) {
            return warning.getWarningType().trim();
        }
        return "系统综合规则";
    }

    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "LOW";
        }
        return level.trim().toUpperCase(Locale.ROOT);
    }

    private String translateLevel(String level) {
        return switch (normalizeLevel(level)) {
            case "CRITICAL" -> "紧急";
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> normalizeLevel(level);
        };
    }

    private int levelPriority(String level) {
        return switch (normalizeLevel(level)) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double percentage(long value, long total) {
        return total == 0 ? 0.0 : round2((double) value / total * 100.0);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round7(double value) {
        return Math.round(value * 10_000_000.0) / 10_000_000.0;
    }
}
