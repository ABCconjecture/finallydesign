package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

@Service
public class AnalysisService {

    @Autowired
    private AnalysisDataRepository repository;

    /**
     * 获取用户在指定时间段内的分析报告
     * ✅ 细节纠正：确保 userId 使用 Long，与 Repository 匹配
     */
    public List<AnalysisData> getUserAnalysisReport(Integer userId, LocalDate start, LocalDate end) {
        return repository.findByUserIdAndAnalysisDateBetween(userId, start, end);
    }
}