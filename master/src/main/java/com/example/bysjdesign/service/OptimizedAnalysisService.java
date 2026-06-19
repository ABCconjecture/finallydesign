package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class OptimizedAnalysisService {

    @Autowired
    private AnalysisDataRepository analysisDataRepository;

    /**
     * 获取指定用户列表的最新分析结果
     */
    public List<AnalysisData> getLatestAnalysisForUsers(List<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyList();
        return analysisDataRepository.findLatestAnalysisForUsers(userIds);
    }

    public Page<AnalysisData> getUserAnalysisHistory(Integer userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.Direction.DESC, "analysisDate");
        List<AnalysisData> content = analysisDataRepository.findByUserId(userId, pageable);
        return new PageImpl<>(content, pageable, content.size());
    }
}