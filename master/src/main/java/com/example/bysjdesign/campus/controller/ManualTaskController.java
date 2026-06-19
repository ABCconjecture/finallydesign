package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.service.ManualFullTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/campus/manual-tasks")
public class ManualTaskController {

    private final ManualFullTaskService manualFullTaskService;

    public ManualTaskController(ManualFullTaskService manualFullTaskService) {
        this.manualFullTaskService = manualFullTaskService;
    }

    @GetMapping("/status")
    public Map<String, Object> getManualTaskStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", manualFullTaskService.getTaskStatusSummary());
        return result;
    }
}