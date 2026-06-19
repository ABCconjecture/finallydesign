package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.*;
import com.example.bysjdesign.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 特征提取服务
 * 从原始数据中提取关键特征用于K-means聚类
 */
@Service
public class FeatureExtractorService {

    private static final Logger logger = LoggerFactory.getLogger(FeatureExtractorService.class);

    @Autowired
    private NetworkLogRepository networkLogRepository;

    @Autowired
    private AccessLogRepository accessLogRepository;

    @Autowired
    private BorrowLogRepository borrowLogRepository;

    @Autowired
    private AnalysisDataRepository analysisDataRepository;

    @Autowired
    private CampusUserRepository userRepository;

    /**
     * 为所有用户提取特征
     */
    public void extractAllUserFeatures() {
        logger.info("开始提取所有用户特征...");
        try {
            List<CampusUser> users = userRepository.findAll();
            int total = users.size();
            int processed = 0;

            for (CampusUser user : users) {
                try {
                    // 修正：使用 CampusUser 的 getId() (返回Long) 或将 Integer 转换为 Long
                    extractUserFeatures((int) user.getUserId().longValue());
                    processed++;

                    if (processed % 100 == 0) {
                        logger.info("特征提取进度: {}/{}", processed, total);
                    }
                } catch (Exception e) {
                    logger.error("提取用户 {} 特征失败", user.getUserId(), e);
                }
            }

            logger.info("特征提取完成！共处理 {} 个用户", total);
        } catch (Exception e) {
            logger.error("特征提取异常", e);
            throw new RuntimeException("特征提取失败", e);
        }
    }

    /**
     * 为指定用户提取特征
     * 修正：参数类型由 Integer 改为 Long
     */
    public Map<String, Double> extractUserFeatures(Integer userId) {
        logger.debug("提取用户 {} 的特征", userId);

        Map<String, Double> features = new HashMap<>();

        try {
            // 1. 网络行为特征
            extractNetworkFeatures(userId, features);

            // 2. 门禁行为特征
            extractAccessFeatures(userId, features);

            // 3. 借阅行为特征
            extractBorrowFeatures(userId, features);

            // 4. 综合特征
            extractCompositeFeatures(userId, features);

            return features;
        } catch (Exception e) {
            logger.error("提取用户特征失败", e);
            return features;
        }
    }

    /**
     * 提取网络行为特征
     * 修正：参数类型由 Integer 改为 Long
     */
    private void extractNetworkFeatures(Integer userId, Map<String, Double> features) {
        // 获取最近30天的网络日志
        LocalDate startDate = LocalDate.now().minusDays(30);
        List<NetworkLog> logs = networkLogRepository.findByUserIdAndSessionStartAfter(
                userId,
                startDate.atStartOfDay()
        );

        if (logs.isEmpty()) {
            features.put("avgDailyOnlineHours", 0.0);
            features.put("studyTrafficRatio", 0.0);
            features.put("peakHour", 12.0);
            return;
        }

        // 计算日均上网时长
        double totalHours = logs.stream()
                .mapToDouble(log -> {
                    if (log.getSessionEnd() != null && log.getSessionStart() != null) {
                        return (log.getSessionEnd().getTime() - log.getSessionStart().getTime()) / 3600000.0;
                    }
                    return 0.0;
                })
                .sum();
        double avgDailyHours = totalHours / 30;
        features.put("avgDailyOnlineHours", avgDailyHours);

        // 计算学习流量占比
        long studyTraffic = logs.stream()
                .filter(log -> "学习".equals(log.getCategory()))
                .mapToLong(log -> log.getDataVolume() != 0 ? log.getDataVolume() : 0)
                .sum();
        long totalTraffic = logs.stream()
                .mapToLong(log -> log.getDataVolume() != 0 ? log.getDataVolume() : 0)
                .sum();
        double studyRatio = totalTraffic > 0 ? (double) studyTraffic / totalTraffic : 0.0;
        features.put("studyTrafficRatio", studyRatio);

        // 计算高峰时段
        OptionalDouble peakHour = logs.stream()
                .filter(log -> log.getSessionStart() != null)
                .mapToDouble(log -> {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(log.getSessionStart());
                    return cal.get(Calendar.HOUR_OF_DAY);
                })
                .average();
        features.put("peakHour", peakHour.orElse(12.0));
    }

    /**
     * 提取门禁行为特征
     * 修正：参数类型由 Integer 改为 Long
     */
    private void extractAccessFeatures(Integer userId, Map<String, Double> features) {
        LocalDate startDate = LocalDate.now().minusDays(30);
        List<AccessLog> logs = accessLogRepository.findByUserIdAndEntryTimeAfter(
                userId,
                startDate.atStartOfDay()
        );

        // 计算教学楼进出次数
        long classroomCount = logs.stream()
                .filter(log -> "教学楼".equals(log.getLocationType()))
                .count();
        features.put("classroomAccessCount", (double) classroomCount);

        // 计算晚归次数（23点后进入）
        long lateReturnCount = logs.stream()
                .filter(log -> {
                    if (log.getEntryTime() == null) return false;
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(log.getEntryTime());
                    return cal.get(Calendar.HOUR_OF_DAY) >= 23;
                })
                .count();
        features.put("lateReturnCount", (double) lateReturnCount);

        // 计算外出频率
        double accessFrequency = logs.size() / 30.0;
        features.put("accessFrequency", accessFrequency);
    }

    /**
     * 提取借阅行为特征
     * 修正：参数类型由 Integer 改为 Long
     */
    private void extractBorrowFeatures(Integer userId, Map<String, Double> features) {
        LocalDate startDate = LocalDate.now().minusDays(30);
        List<BorrowLog> logs = borrowLogRepository.findByUserIdAndBorrowDateAfter(
                userId,
                startDate
        );

        // 计算借阅活跃度
        double borrowActivity = logs.size() / 1.0; // 简化计算
        features.put("borrowActivityScore", borrowActivity);

        // 计算平均借阅周期
        double avgBorrowDays = logs.stream()
                .filter(log -> log.getBorrowDate() != null && log.getReturnDate() != null)
                .mapToLong(log -> {
                    // 修正：使用 Instant 转换以安全计算日期差
                    return ChronoUnit.DAYS.between(
                            log.getBorrowDate().toInstant(),
                            log.getReturnDate().toInstant()
                    );
                })
                .average()
                .orElse(0.0);

        features.put("avgBorrowDays", avgBorrowDays);
    }

    /**
     * 提取综合特征
     * 修正：参数类型由 Integer 改为 Long
     */
    private void extractCompositeFeatures(Integer userId, Map<String, Double> features) {
        // 获取最新的分析数据
        Pageable pageable = PageRequest.of(0, 1);
        // findByUserId 现在接收 Long 类型的参数
        AnalysisData analysisData = analysisDataRepository.findByUserId(userId, pageable)
                .stream()
                .max(Comparator.comparing(AnalysisData::getAnalysisDate))
                .orElse(null);

        if (analysisData != null) {
            // 添加健康度
            features.put("overallHealthScore", analysisData.getOverallHealthScore());

            // 添加异常流量标志
            features.put("abnormalTrafficFlag", (analysisData.getAbnormalTrafficFlag() != null && analysisData.getAbnormalTrafficFlag()) ? 1.0 : 0.0);

            // 添加缺勤标志
            features.put("absenteeFlag", analysisData.getAbsenteeFlag() ? 1.0 : 0.0);
        } else {
            features.put("overallHealthScore", 50.0);
            features.put("abnormalTrafficFlag", 0.0);
            features.put("absenteeFlag", 0.0);
        }
    }

    /**
     * 标准化特征（用于K-means）
     */
    public double[][] normalizeFeatures(List<Map<String, Double>> featuresList) {
        if (featuresList.isEmpty()) return new double[0][0];

        int numFeatures = featuresList.get(0).size();
        int numUsers = featuresList.size();

        double[][] matrix = new double[numUsers][numFeatures];
        // 修正：确保特征列顺序一致
        List<String> featureNames = new ArrayList<>(featuresList.get(0).keySet());

        // 填充矩阵
        for (int i = 0; i < numUsers; i++) {
            Map<String, Double> features = featuresList.get(i);
            for (int j = 0; j < numFeatures; j++) {
                matrix[i][j] = features.getOrDefault(featureNames.get(j), 0.0);
            }
        }

        // 标准化每一列
        for (int j = 0; j < numFeatures; j++) {
            double sum = 0;
            for (int i = 0; i < numUsers; i++) {
                sum += matrix[i][j];
            }
            double mean = sum / numUsers;

            double sumSqDiff = 0;
            for (int i = 0; i < numUsers; i++) {
                sumSqDiff += Math.pow(matrix[i][j] - mean, 2);
            }
            double stdDev = Math.sqrt(sumSqDiff / numUsers);

            if (stdDev > 0) {
                for (int i = 0; i < numUsers; i++) {
                    matrix[i][j] = (matrix[i][j] - mean) / stdDev;
                }
            }
        }

        return matrix;
    }
}