package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.repository.RiskWarningRepository;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class AcademicWarningService {

    private final CampusUserRepository userRepository;
    private final AnalysisDataRepository analysisRepository;
    private final RiskWarningRepository warningRepository;

    public AcademicWarningService(CampusUserRepository userRepository,
                                  AnalysisDataRepository analysisRepository,
                                  RiskWarningRepository warningRepository) {
        this.userRepository = userRepository;
        this.analysisRepository = analysisRepository;
        this.warningRepository = warningRepository;
    }

    public void generateAcademicWarnings() {
        userRepository.findAll().forEach(user -> {
            Optional<AnalysisData> latest = analysisRepository.findFirstByUserIdOrderByAnalysisDateDescIdDesc(user.getId());
            latest.ifPresent(data -> {
                if (data.getRiskScore() != null && data.getRiskScore() >= 70.0) {
                    saveWarningIfNotExists(user.getId(), "ACADEMIC_RISK", data.getRiskScore());
                }
            });
        });
    }

    private void saveWarningIfNotExists(Integer userId, String type, Double score) {
        List<RiskWarning> existing = warningRepository.findByUserIdAndStatus(userId, 0);
        boolean exists = existing.stream().anyMatch(warning -> type.equals(warning.getWarningType()));

        if (!exists) {
            RiskWarning warning = new RiskWarning();
            warning.setUserId(userId);
            warning.setWarningType(type);
            warning.setRiskScore(score.intValue());
            warning.setStatus(0);
            warning.setWarningLevel("HIGH");
            warning.setCreateTime(new Date());
            warning.setRiskDescription("学业风险自动检测命中阈值");
            warningRepository.save(warning);
        }
    }
}
