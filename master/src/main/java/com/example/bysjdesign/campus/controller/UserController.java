package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.example.bysjdesign.service.KMeansService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campus/users")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final CampusUserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final KMeansService kMeansService;

    public UserController(CampusUserRepository userRepository,
                          UserProfileRepository userProfileRepository,
                          AnalysisDataRepository analysisDataRepository,
                          RiskWarningRepository riskWarningRepository,
                          KMeansService kMeansService) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.kMeansService = kMeansService;
    }

    @GetMapping
    public Map<String, Object> getUsers(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        @RequestParam(required = false) String search,
                                        @RequestParam(required = false) String college,
                                        @RequestParam(required = false) String major,
                                        @RequestParam(required = false) Integer clusterId,
                                        @RequestParam(required = false) String status) {
        List<CampusUser> allUsers = userRepository.findAll();
        List<Integer> userIds = allUsers.stream().map(CampusUser::getUserId).collect(Collectors.toList());

        Map<Integer, UserProfile> profileMap = userProfileRepository.findAll().stream()
                .filter(profile -> profile.getUserId() != null)
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Integer, AnalysisData> analysisMap = analysisDataRepository.findLatestAnalysisForUsers(userIds).stream()
                .collect(Collectors.toMap(AnalysisData::getUserId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Integer, List<RiskWarning>> warningMap = riskWarningRepository.findByStatusOrderByCreateTimeDesc(0).stream()
                .collect(Collectors.groupingBy(RiskWarning::getUserId, LinkedHashMap::new, Collectors.toList()));

        List<CampusUser> filteredUsers = allUsers.stream()
                .filter(user -> matchesKeyword(user, search))
                .filter(user -> matchesField(user.getCollege(), college))
                .filter(user -> matchesField(user.getMajor(), major))
                .filter(user -> matchesCluster(profileMap.get(user.getUserId()), clusterId))
                .filter(user -> matchesStatus(analysisMap.get(user.getUserId()), warningMap.get(user.getUserId()), status))
                .sorted(Comparator.comparing(CampusUser::getUserId))
                .collect(Collectors.toList());

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.max(size, 1);
        int fromIndex = Math.min(normalizedPage * normalizedSize, filteredUsers.size());
        int toIndex = Math.min(fromIndex + normalizedSize, filteredUsers.size());
        List<CampusUser> pageUsers = filteredUsers.subList(fromIndex, toIndex);

        logger.info("API [/api/campus/users] 返回用户数量: {}", pageUsers.size());

        List<Map<String, Object>> records = pageUsers.stream()
                .map(user -> buildUserListItem(user, profileMap.get(user.getUserId()), analysisMap.get(user.getUserId()), warningMap.get(user.getUserId())))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("records", records);
        data.put("total", filteredUsers.size());
        data.put("page", normalizedPage);
        data.put("size", normalizedSize);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    @GetMapping("/{keyword}/profile")
    public Map<String, Object> getUserProfile(@PathVariable String keyword) {
        CampusUser user = findUserByKeyword(keyword);
        Map<String, Object> result = new HashMap<>();

        if (user == null) {
            result.put("code", 404);
            result.put("message", "未找到匹配的用户");
            return result;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("user", user);
        data.put("profile", userProfileRepository.findByUserId(user.getUserId()));
        data.put("latestAnalysis", analysisDataRepository.findFirstByUserIdOrderByAnalysisDateDescIdDesc(user.getUserId()).orElse(null));
        data.put("openWarnings", riskWarningRepository.findByUserIdAndStatus(user.getUserId(), 0));

        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private Map<String, Object> buildUserListItem(CampusUser user,
                                                  UserProfile profile,
                                                  AnalysisData analysis,
                                                  List<RiskWarning> warnings) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", user.getUserId());
        item.put("studentId", user.getStudentId());
        item.put("name", user.getName());
        item.put("gender", user.getGender());
        item.put("college", user.getCollege());
        item.put("major", user.getMajor());
        item.put("cluster", profile != null && profile.getClusterId() != null ? profile.getClusterId() : null);
        item.put("clusterLabel", resolveClusterLabel(profile));
        item.put("clusterSummary", extractTagField(profile, "summary"));
        item.put("healthScore", analysis == null ? null : analysis.getHealthScore());
        item.put("riskScore", analysis == null ? null : analysis.getRiskScore());
        item.put("status", resolveStatusLabel(analysis, warnings));
        item.put("healthLevel", resolveHealthLevel(analysis));
        item.put("warningCount", warnings == null ? 0 : warnings.size());
        item.put("topWarningType", warnings == null || warnings.isEmpty() ? null : warnings.get(0).getWarningType());
        item.put("avgOnlineHours", analysis == null ? null : analysis.getAvgOnlineHours());
        item.put("studyTrafficRatio", analysis == null ? null : analysis.getStudyTrafficRatio());
        return item;
    }

    private CampusUser findUserByKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }

        String trimmedKeyword = keyword.trim();
        if (trimmedKeyword.matches("\\d+")) {
            CampusUser user = userRepository.findById(Integer.valueOf(trimmedKeyword)).orElse(null);
            if (user != null) {
                return user;
            }

            user = userRepository.findByStudentId(trimmedKeyword).orElse(null);
            if (user != null) {
                return user;
            }
        }

        return userRepository.findByNameContaining(trimmedKeyword).stream().findFirst().orElse(null);
    }

    private boolean matchesKeyword(CampusUser user, String search) {
        if (!StringUtils.hasText(search)) {
            return true;
        }

        String keyword = search.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(user.getStudentId(), keyword)
                || containsIgnoreCase(user.getName(), keyword)
                || String.valueOf(user.getUserId()).equals(keyword);
    }

    private boolean matchesField(String source, String condition) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }
        return containsIgnoreCase(source, condition.trim().toLowerCase(Locale.ROOT));
    }

    private boolean matchesCluster(UserProfile profile, Integer clusterId) {
        if (clusterId == null) {
            return true;
        }
        return profile != null && Objects.equals(profile.getClusterId(), clusterId);
    }

    private boolean matchesStatus(AnalysisData analysis, List<RiskWarning> warnings, String status) {
        if (!StringUtils.hasText(status)) {
            return true;
        }
        return containsIgnoreCase(resolveStatusLabel(analysis, warnings), status.trim().toLowerCase(Locale.ROOT));
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String resolveClusterLabel(UserProfile profile) {
        if (profile == null || profile.getClusterId() == null) {
            return "未聚类";
        }
        String label = extractTagField(profile, "label");
        return StringUtils.hasText(label) ? label : kMeansService.getClusterLabel(profile.getClusterId());
    }

    private String extractTagField(UserProfile profile, String field) {
        if (profile == null || !StringUtils.hasText(profile.getTags())) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(profile.getTags()).getAsJsonObject();
            if (object.has(field)) {
                return object.get(field).getAsString();
            }
        } catch (Exception ignored) {
            return profile.getTags();
        }
        return null;
    }

    private String resolveStatusLabel(AnalysisData analysis, List<RiskWarning> warnings) {
        double riskScore = analysis == null || analysis.getRiskScore() == null ? 0.0 : analysis.getRiskScore();
        int warningCount = warnings == null ? 0 : warnings.size();
        if (warningCount > 0 && riskScore >= 65.0) {
            return "重点预警";
        }
        if (warningCount > 0 || riskScore >= 45.0) {
            return "持续关注";
        }
        if (riskScore >= 25.0) {
            return "轻度波动";
        }
        return "状态稳定";
    }

    private String resolveHealthLevel(AnalysisData analysis) {
        double healthScore = analysis == null || analysis.getHealthScore() == null ? 0.0 : analysis.getHealthScore();
        if (healthScore >= 95.0) {
            return "优秀";
        }
        if (healthScore >= 85.0) {
            return "稳健";
        }
        if (healthScore >= 70.0) {
            return "关注";
        }
        return "预警";
    }
}