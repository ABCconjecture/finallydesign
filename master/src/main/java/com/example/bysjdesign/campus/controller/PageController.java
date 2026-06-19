package com.example.bysjdesign.campus.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/campus")
public class PageController {

    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/dashboard.html";
    }

    @GetMapping("/users")
    public String users() {
        return "redirect:/users.html";
    }

    @GetMapping("/profile/{userId}")
    public String profile(@PathVariable Integer userId) {
        return "redirect:/analysis.html#user=" + userId;
    }

    @GetMapping("/warning")
    public String warning() {
        return "redirect:/warning.html";
    }

    @GetMapping("/account")
    public String account() {
        return "redirect:/account.html";
    }

    @GetMapping("/tasks")
    public String tasks() {
        return "redirect:/tasks.html";
    }

    @GetMapping("/cluster/{clusterId}")
    public String clusterDetail(@PathVariable Integer clusterId) {
        return "redirect:/profile.html#cluster=" + clusterId;
    }

    @GetMapping("/")
    public String redirectToDashboard() {
        return "redirect:/dashboard.html";
    }
}
