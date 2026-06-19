package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.UserProfile;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.UserProfileRepository;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserProfileService {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileService.class);
    private static final Gson GSON = new Gson();

    private final UserProfileRepository userProfileRepository;
    private final CampusUserRepository campusUserRepository;
    private final KMeansService kMeansService;

    public UserProfileService(UserProfileRepository userProfileRepository,
                              CampusUserRepository campusUserRepository,
                              KMeansService kMeansService) {
        this.userProfileRepository = userProfileRepository;
        this.campusUserRepository = campusUserRepository;
        this.kMeansService = kMeansService;
    }

    public void generateUserProfiles() {
        generateUserProfiles(true);
    }

    public void generateUserProfiles(boolean refreshClusters) {
        logger.info("开始执行用户画像批量更新...");
        try {
            if (refreshClusters || !kMeansService.hasClusterResult()) {
                kMeansService.performClustering();
            }

            Map<Integer, Integer> clusterMap = kMeansService.getUserClusterMap();
            if (clusterMap.isEmpty()) {
                logger.warn("聚类结果为空，跳过画像更新");
                return;
            }

            Map<Integer, UserProfile> existingProfiles = loadExistingProfiles();
            List<UserProfile> profilesToSave = new ArrayList<>();
            Date now = new Date();

            for (CampusUser user : campusUserRepository.findAll()) {
                UserProfile profile = buildProfile(user.getUserId(), clusterMap, existingProfiles, now);
                if (profile != null) {
                    profilesToSave.add(profile);
                }
            }

            userProfileRepository.saveAll(profilesToSave);
            logger.info("用户画像更新完成，写入 {} 条记录", profilesToSave.size());
        } catch (Exception ex) {
            logger.error("生成用户画像失败", ex);
            throw new IllegalStateException("生成用户画像失败", ex);
        }
    }

    private Map<Integer, UserProfile> loadExistingProfiles() {
        return userProfileRepository.findAll().stream()
                .filter(profile -> profile.getUserId() != null)
                .collect(Collectors.toMap(UserProfile::getUserId, profile -> profile, (left, right) -> left, LinkedHashMap::new));
    }

    private UserProfile buildProfile(Integer userId,
                                     Map<Integer, Integer> clusterMap,
                                     Map<Integer, UserProfile> existingProfiles,
                                     Date now) {
        Integer clusterId = clusterMap.get(userId);
        if (userId == null || clusterId == null) {
            return null;
        }

        UserProfile profile = existingProfiles.getOrDefault(userId, new UserProfile());
        profile.setUserId(userId);
        profile.setClusterId(clusterId);
        profile.setTags(encodeTags(
                defaultIfBlank(kMeansService.getClusterLabel(clusterId), "校园参与型"),
                defaultIfBlank(kMeansService.getClusterFocus(clusterId), "行为平稳，持续观察"),
                defaultIfBlank(kMeansService.getUserTags(userId), "校园参与型：行为平稳，持续观察")
        ));
        profile.setFeatureVector(kMeansService.getUserFeatureVector(userId));
        profile.setUpdateTime(now);
        return profile;
    }

    private String encodeTags(String label, String focus, String summary) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("focus", focus);
        payload.put("summary", summary);
        return GSON.toJson(payload);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
