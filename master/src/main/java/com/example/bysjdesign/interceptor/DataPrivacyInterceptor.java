package com.example.bysjdesign.interceptor;

import com.example.bysjdesign.service.AuthService;
import com.example.bysjdesign.service.SessionUser;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Component
public class DataPrivacyInterceptor implements HandlerInterceptor {
    private static final Logger logger = Logger.getLogger(DataPrivacyInterceptor.class.getName());
    private static final String CAMPUS_API_PREFIX = "/api/campus/";
    private static final String CAMPUS_HEALTH_URI = "/api/campus/health";

    private final AuthService authService;
    private final List<Pattern> loginRequiredPatterns = List.of(
            Pattern.compile("PUT:.*/api/auth/me/profile$"),
            Pattern.compile("POST:.*/api/auth/me/password$"),
            Pattern.compile("POST:.*/api/auth/logout$")
    );
    private final List<Pattern> adminOnlyPatterns = List.of(
            Pattern.compile("POST:.*/api/campus/analysis/trigger-all$"),
            Pattern.compile("POST:.*/api/campus/analysis/\\d+/trigger$"),
            Pattern.compile("POST:.*/api/campus/cluster/trigger$"),
            Pattern.compile("POST:.*/api/campus/warning/trigger$"),
            Pattern.compile("POST:.*/api/campus/warning/\\d+/handle$"),
            Pattern.compile("POST:.*/api/campus/tasks/[^/]+/trigger$"),
            Pattern.compile("POST:.*/api/campus/tasks/[^/]+/retry$"),
            Pattern.compile("POST:.*/api/campus/tasks/[^/]+/pause$"),
            Pattern.compile("POST:.*/api/campus/tasks/[^/]+/resume$"),
            Pattern.compile("GET:.*/api/auth/users$"),
            Pattern.compile("POST:.*/api/auth/users$"),
            Pattern.compile("PATCH:.*/api/auth/users/\\d+/status$"),
            Pattern.compile("PATCH:.*/api/auth/users/\\d+/role$"),
            Pattern.compile("POST:.*/api/auth/users/\\d+/password/reset$")
    );

    public DataPrivacyInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        String signature = buildSignature(request.getMethod(), uri);
        boolean campusReadOrWrite = isProtectedCampusApi(uri);
        boolean loginRequired = campusReadOrWrite || requiresLogin(signature) || requiresAdmin(signature);

        if (!loginRequired) {
            return true;
        }

        SessionUser currentUser = authService.getCurrentUser(request.getSession(false));
        if (currentUser == null) {
            logger.info("Anonymous access blocked for protected endpoint -> " + uri);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "Please log in before accessing system data");
            return false;
        }

        if (requiresAdmin(signature) && !authService.isAdmin(currentUser)) {
            logger.info("Insufficient role blocked for protected endpoint -> " + uri + ", user=" + currentUser.getUsername());
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, 403, "Current account is read-only. Please use an administrator account.");
            return false;
        }

        return true;
    }

    private String buildSignature(String method, String uri) {
        return (method == null ? "" : method.toUpperCase()) + ":" + (uri == null ? "" : uri);
    }

    private boolean isProtectedCampusApi(String uri) {
        return uri != null && uri.startsWith(CAMPUS_API_PREFIX) && !CAMPUS_HEALTH_URI.equals(uri);
    }

    private boolean requiresLogin(String signature) {
        return loginRequiredPatterns.stream().anyMatch(pattern -> pattern.matcher(signature).matches());
    }

    private boolean requiresAdmin(String signature) {
        return adminOnlyPatterns.stream().anyMatch(pattern -> pattern.matcher(signature).matches());
    }

    private void writeJson(HttpServletResponse response, int httpStatus, int code, String message) throws Exception {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\"}", code, message));
    }
}
