package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.campus.entity.AnalysisData;
import com.example.bysjdesign.repository.AnalysisDataRepository;
import com.example.bysjdesign.repository.CampusUserRepository;
import com.example.bysjdesign.service.AnalysisInsightService;
import com.example.bysjdesign.service.AuthService;
import com.example.bysjdesign.service.ManualFullTaskService;
import com.example.bysjdesign.service.MultiDimensionalAnalysisService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/campus/analysis")
public class AnalysisRestController {

    private final AnalysisDataRepository analysisDataRepository;
    private final CampusUserRepository userRepository;
    private final MultiDimensionalAnalysisService multiDimensionalAnalysisService;
    private final ManualFullTaskService manualFullTaskService;
    private final AuthService authService;
    private final AnalysisInsightService analysisInsightService;

    public AnalysisRestController(AnalysisDataRepository analysisDataRepository,
                                  CampusUserRepository userRepository,
                                  MultiDimensionalAnalysisService multiDimensionalAnalysisService,
                                  ManualFullTaskService manualFullTaskService,
                                  AuthService authService,
                                  AnalysisInsightService analysisInsightService) {
        this.analysisDataRepository = analysisDataRepository;
        this.userRepository = userRepository;
        this.multiDimensionalAnalysisService = multiDimensionalAnalysisService;
        this.manualFullTaskService = manualFullTaskService;
        this.authService = authService;
        this.analysisInsightService = analysisInsightService;
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getUserAnalysis(@PathVariable("userId") Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!userRepository.existsById(userId)) {
            result.put("code", 404);
            result.put("message", "未找到对应用户");
            return result;
        }

        Optional<AnalysisData> latest = analysisDataRepository.findFirstByUserIdOrderByAnalysisDateDescIdDesc(userId);
        if (latest.isEmpty()) {
            try {
                AnalysisData freshData = multiDimensionalAnalysisService.analyzeUser(userId);
                result.put("code", 200);
                result.put("data", freshData);
                return result;
            } catch (Exception e) {
                result.put("code", 500);
                result.put("message", "自动分析失败: " + e.getMessage());
                return result;
            }
        }

        result.put("code", 200);
        result.put("data", latest.get());
        return result;
    }

    @GetMapping("/{userId}/history")
    public Map<String, Object> getHistory(@PathVariable("userId") Integer userId) {
        List<AnalysisData> list = analysisDataRepository.findByUserId(
                userId,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "analysisDate", "id"))
        );
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/{userId}/insight")
    public Map<String, Object> getInsight(@PathVariable("userId") Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!userRepository.existsById(userId)) {
            result.put("code", 404);
            result.put("message", "鏈壘鍒板搴旂敤鎴?");
            return result;
        }
        result.put("code", 200);
        result.put("data", analysisInsightService.buildInsight(userId));
        return result;
    }

    @PostMapping("/{userId}/trigger")
    public Map<String, Object> triggerUserAnalysis(@PathVariable("userId") Integer userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            AnalysisData data = multiDimensionalAnalysisService.analyzeUser(userId);
            result.put("code", 200);
            result.put("message", "指标更新成功");
            result.put("data", data);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "分析失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/trigger-all")
    public Map<String, Object> triggerAllAnalysis(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("code", 200);
            result.put("data", manualFullTaskService.submitAnalysisTask(authService.getCurrentUser(session)));
        } catch (IllegalStateException e) {
            result.put("code", 409);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "提交全量分析任务失败: " + e.getMessage());
        }
        return result;
    }
}
