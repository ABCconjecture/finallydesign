package com.example.bysjdesign.interceptor;

import com.example.bysjdesign.service.AuthService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PageAccessInterceptor implements HandlerInterceptor {

    private static final Pattern USER_PROFILE_PATTERN = Pattern.compile("^/campus/profile/(\\d+)$");
    private static final Pattern CLUSTER_PATTERN = Pattern.compile("^/campus/cluster/(\\d+)$");

    private final AuthService authService;

    public PageAccessInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        if (!isProtectedPage(uri)) {
            return true;
        }

        if (authService.getCurrentUser(request.getSession(false)) != null) {
            return true;
        }

        String target = resolveRedirectTarget(request);
        String encodedTarget = URLEncoder.encode(target, StandardCharsets.UTF_8);
        response.sendRedirect(request.getContextPath() + "/login.html?redirect=" + encodedTarget);
        return false;
    }

    private boolean isProtectedPage(String uri) {
        return "/".equals(uri)
                || "/index.html".equals(uri)
                || "/dashboard.html".equals(uri)
                || "/analysis.html".equals(uri)
                || "/users.html".equals(uri)
                || "/warning.html".equals(uri)
                || "/profile.html".equals(uri)
                || "/account.html".equals(uri)
                || "/tasks.html".equals(uri)
                || (uri != null && uri.startsWith("/campus/"));
    }

    private String resolveRedirectTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();

        if (uri == null || "/".equals(uri) || "/index.html".equals(uri) || "/campus/".equals(uri) || "/campus/dashboard".equals(uri)) {
            return "dashboard.html";
        }
        if ("/dashboard.html".equals(uri)) {
            return appendQuery("dashboard.html", query);
        }
        if ("/analysis.html".equals(uri)) {
            return appendQuery("analysis.html", query);
        }
        if ("/users.html".equals(uri) || "/campus/users".equals(uri)) {
            return appendQuery("users.html", query);
        }
        if ("/warning.html".equals(uri) || "/campus/warning".equals(uri)) {
            return appendQuery("warning.html", query);
        }
        if ("/profile.html".equals(uri)) {
            return appendQuery("profile.html", query);
        }
        if ("/account.html".equals(uri) || "/campus/account".equals(uri)) {
            return appendQuery("account.html", query);
        }
        if ("/tasks.html".equals(uri) || "/campus/tasks".equals(uri)) {
            return appendQuery("tasks.html", query);
        }

        Matcher userProfileMatcher = USER_PROFILE_PATTERN.matcher(uri);
        if (userProfileMatcher.matches()) {
            return "analysis.html#user=" + userProfileMatcher.group(1);
        }

        Matcher clusterMatcher = CLUSTER_PATTERN.matcher(uri);
        if (clusterMatcher.matches()) {
            return "profile.html#cluster=" + clusterMatcher.group(1);
        }

        if (uri.startsWith("/campus/")) {
            return "dashboard.html";
        }

        return appendQuery(uri.startsWith("/") ? uri.substring(1) : uri, query);
    }

    private String appendQuery(String page, String query) {
        if (query == null || query.isBlank()) {
            return page;
        }
        return page + "?" + query;
    }
}