package com.example.bysjdesign.util;

import com.example.bysjdesign.campus.entity.AccessLog;
import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.BorrowLog;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.NetworkLog;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.repository.AccessLogRepository;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.BorrowLogRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.NetworkLogRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import com.example.bysjdesign.service.AnalysisComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);
    private static final String USER_RESOURCE = "data/campus_user.csv";
    private static final String ACCESS_RESOURCE = "data/access_log.csv";
    private static final String BORROW_RESOURCE = "data/borrow_log.csv";
    private static final String NETWORK_RESOURCE = "data/network_log.csv";

    private final CampusUserRepository userRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final RiskWarningRepository riskWarningRepository;
    private final AccessLogRepository accessLogRepository;
    private final BorrowLogRepository borrowLogRepository;
    private final NetworkLogRepository networkLogRepository;
    private final DataCleaningUtil dataCleaningUtil;
    private final AnalysisComputationService analysisComputationService;

    public DataInitializer(CampusUserRepository userRepository,
                           AnalysisDataRepository analysisDataRepository,
                           RiskWarningRepository riskWarningRepository,
                           AccessLogRepository accessLogRepository,
                           BorrowLogRepository borrowLogRepository,
                           NetworkLogRepository networkLogRepository,
                           DataCleaningUtil dataCleaningUtil,
                           AnalysisComputationService analysisComputationService) {
        this.userRepository = userRepository;
        this.analysisDataRepository = analysisDataRepository;
        this.riskWarningRepository = riskWarningRepository;
        this.accessLogRepository = accessLogRepository;
        this.borrowLogRepository = borrowLogRepository;
        this.networkLogRepository = networkLogRepository;
        this.dataCleaningUtil = dataCleaningUtil;
        this.analysisComputationService = analysisComputationService;
    }

    @Override
    public void run(String... args) {
        logger.info("========== 开始检查基础数据 ==========");

        Map<Integer, Integer> userIdMapping = initializeUserIdMapping();
        initializeAccessLogs(userIdMapping);
        initializeBorrowLogs(userIdMapping);
        initializeNetworkLogs(userIdMapping);
        initializeAnalysisSnapshots();
        initializeRiskWarnings();

        logger.info("========== 基础数据检查完成 ==========");
    }

    private Map<Integer, Integer> initializeUserIdMapping() {
        if (userRepository.count() > 0) {
            logger.info("检测到已有校园用户 {} 条，跳过用户导入", userRepository.count());
            return resolveExistingUserMapping();
        }

        List<DataCleaningUtil.ImportedCampusUser> importedUsers = dataCleaningUtil.loadCampusUsers(USER_RESOURCE);
        if (importedUsers.isEmpty()) {
            logger.warn("未从 {} 读取到可导入的校园用户数据", USER_RESOURCE);
            return Collections.emptyMap();
        }

        List<CampusUser> usersToSave = importedUsers.stream()
                .map(DataCleaningUtil.ImportedCampusUser::user)
                .collect(Collectors.toList());
        List<CampusUser> savedUsers = userRepository.saveAll(usersToSave);

        Map<String, Integer> studentIdToActualId = savedUsers.stream()
                .filter(user -> user.getStudentId() != null && user.getUserId() != null)
                .collect(Collectors.toMap(CampusUser::getStudentId, CampusUser::getUserId, (left, right) -> left));

        Map<Integer, Integer> userIdMapping = new LinkedHashMap<>();
        for (DataCleaningUtil.ImportedCampusUser importedUser : importedUsers) {
            Integer actualUserId = studentIdToActualId.get(importedUser.user().getStudentId());
            if (actualUserId != null) {
                userIdMapping.put(importedUser.sourceUserId(), actualUserId);
            }
        }

        logger.info("已从 {} 导入校园用户 {} 条", USER_RESOURCE, savedUsers.size());
        return userIdMapping;
    }

    private Map<Integer, Integer> resolveExistingUserMapping() {
        Set<Integer> sourceUserIds = dataCleaningUtil.loadCampusUserSourceIds(USER_RESOURCE);
        if (sourceUserIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Integer> existingUserIds = userRepository.findAll().stream()
                .map(CampusUser::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (!existingUserIds.containsAll(sourceUserIds)) {
            logger.warn("现有用户 ID 与 CSV 数据集不兼容，为避免日志关联错误，将跳过依赖 user_id 的日志导入");
            return Collections.emptyMap();
        }

        logger.info("现有用户 ID 与 CSV 数据集兼容，将沿用原有用户映射");
        return sourceUserIds.stream()
                .collect(Collectors.toMap(Function.identity(), Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private void initializeAccessLogs(Map<Integer, Integer> userIdMapping) {
        if (accessLogRepository.count() > 0) {
            logger.info("检测到已有门禁日志 {} 条，跳过门禁数据导入", accessLogRepository.count());
            return;
        }
        if (userIdMapping.isEmpty()) {
            logger.warn("缺少可用的用户 ID 映射，跳过门禁日志导入");
            return;
        }

        List<AccessLog> accessLogs = dataCleaningUtil.loadAccessLogs(ACCESS_RESOURCE, userIdMapping);
        accessLogRepository.saveAll(accessLogs);
        logger.info("已从 {} 导入门禁日志 {} 条", ACCESS_RESOURCE, accessLogs.size());
    }

    private void initializeBorrowLogs(Map<Integer, Integer> userIdMapping) {
        if (borrowLogRepository.count() > 0) {
            logger.info("检测到已有借阅日志 {} 条，跳过借阅数据导入", borrowLogRepository.count());
            return;
        }
        if (userIdMapping.isEmpty()) {
            logger.warn("缺少可用的用户 ID 映射，跳过借阅日志导入");
            return;
        }

        List<BorrowLog> borrowLogs = dataCleaningUtil.loadBorrowLogs(BORROW_RESOURCE, userIdMapping);
        borrowLogRepository.saveAll(borrowLogs);
        logger.info("已从 {} 导入借阅日志 {} 条", BORROW_RESOURCE, borrowLogs.size());
    }

    private void initializeNetworkLogs(Map<Integer, Integer> userIdMapping) {
        if (networkLogRepository.count() > 0) {
            logger.info("检测到已有网络日志 {} 条，跳过网络数据导入", networkLogRepository.count());
            return;
        }
        if (userIdMapping.isEmpty()) {
            logger.warn("缺少可用的用户 ID 映射，跳过网络日志导入");
            return;
        }

        List<NetworkLog> networkLogs = dataCleaningUtil.loadNetworkLogs(NETWORK_RESOURCE, userIdMapping);
        networkLogRepository.saveAll(networkLogs);
        logger.info("已从 {} 导入网络日志 {} 条", NETWORK_RESOURCE, networkLogs.size());
    }

    private void initializeAnalysisSnapshots() {
        if (analysisDataRepository.count() > 0) {
            logger.info("检测到已有分析快照 {} 条，跳过基础分析初始化", analysisDataRepository.count());
            return;
        }

        long rawEventCount = accessLogRepository.count() + borrowLogRepository.count() + networkLogRepository.count();
        if (rawEventCount == 0) {
            logger.warn("未检测到可用于分析的原始日志，跳过基础分析快照生成");
            return;
        }

        List<CampusUser> users = userRepository.findAll();
        if (users.isEmpty()) {
            logger.warn("校园用户为空，无法生成基础分析快照");
            return;
        }

        Map<Integer, List<AccessLog>> accessLogsByUser = accessLogRepository.findAll().stream()
                .filter(log -> log.getUserId() != null)
                .collect(Collectors.groupingBy(AccessLog::getUserId));
        Map<Integer, List<BorrowLog>> borrowLogsByUser = borrowLogRepository.findAll().stream()
                .filter(log -> log.getUserId() != null)
                .collect(Collectors.groupingBy(BorrowLog::getUserId));
        Map<Integer, List<NetworkLog>> networkLogsByUser = networkLogRepository.findAll().stream()
                .filter(log -> log.getUserId() != null)
                .collect(Collectors.groupingBy(NetworkLog::getUserId));

        List<AnalysisData> snapshots = users.stream()
                .map(user -> analysisComputationService.buildSnapshot(
                        user.getUserId(),
                        networkLogsByUser.get(user.getUserId()),
                        accessLogsByUser.get(user.getUserId()),
                        borrowLogsByUser.get(user.getUserId())
                ))
                .collect(Collectors.toList());

        analysisDataRepository.saveAll(snapshots);
        logger.info("已生成基础分析快照 {} 条", snapshots.size());
    }

    private void initializeRiskWarnings() {
        if (riskWarningRepository.count() > 0) {
            logger.info("检测到已有风险预警 {} 条，跳过基础预警初始化", riskWarningRepository.count());
            return;
        }

        List<AnalysisData> analysisData = analysisDataRepository.findAll();
        if (analysisData.isEmpty()) {
            logger.warn("分析快照为空，跳过风险预警初始化");
            return;
        }

        List<RiskWarning> warnings = analysisData.stream()
                .map(analysisComputationService::buildWarning)
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

        if (warnings.isEmpty()) {
            logger.info("当前基础分析未命中高风险阈值，未生成初始预警记录");
            return;
        }

        riskWarningRepository.saveAll(warnings);
        logger.info("已生成基础风险预警 {} 条", warnings.size());
    }
}

