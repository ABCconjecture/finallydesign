package com.example.bysjdesign.campus.controller;

import com.example.bysjdesign.campus.entity.SystemUser;
import com.example.bysjdesign.service.AuthService;
import com.example.bysjdesign.service.SessionUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> payload,
                                     HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = payload == null ? null : payload.get("username");
            String password = payload == null ? null : payload.get("password");
            SessionUser currentUser = authService.login(username, password, session);
            result.put("code", 200);
            result.put("message", "登录成功");
            result.put("data", buildCurrentUser(currentUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 401);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "登录失败: " + fallbackMessage(e));
        }
        return result;
    }

    @GetMapping("/me")
    public Map<String, Object> currentUser(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        SessionUser currentUser = authService.getCurrentUser(session);
        if (currentUser == null) {
            result.put("code", 401);
            result.put("message", "当前未登录");
            return result;
        }

        result.put("code", 200);
        result.put("data", buildCurrentUser(currentUser));
        return result;
    }

    @PutMapping("/me/profile")
    public Map<String, Object> updateProfile(@RequestBody(required = false) Map<String, String> payload,
                                             HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            SessionUser updatedUser = authService.updateCurrentProfile(
                    currentUser,
                    payload == null ? null : payload.get("username"),
                    payload == null ? null : payload.get("displayName"),
                    session
            );
            result.put("code", 200);
            result.put("message", "账号资料更新成功");
            result.put("data", buildCurrentUser(updatedUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 401);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "资料更新失败: " + fallbackMessage(e));
        }
        return result;
    }

    @PostMapping("/me/password")
    public Map<String, Object> changePassword(@RequestBody(required = false) Map<String, String> payload,
                                              HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            authService.changeCurrentPassword(
                    currentUser,
                    payload == null ? null : payload.get("currentPassword"),
                    payload == null ? null : payload.get("newPassword")
            );
            result.put("code", 200);
            result.put("message", "密码修改成功");
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 401);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "密码修改失败: " + fallbackMessage(e));
        }
        return result;
    }

    @GetMapping("/users")
    public Map<String, Object> listUsers(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            List<Map<String, Object>> users = authService.listUsers(currentUser).stream()
                    .map(user -> buildSystemUser(user, currentUser))
                    .collect(Collectors.toList());
            result.put("code", 200);
            result.put("data", users);
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody(required = false) Map<String, String> payload,
                                          HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            SystemUser user = authService.createUser(
                    currentUser,
                    payload == null ? null : payload.get("username"),
                    payload == null ? null : payload.get("displayName"),
                    payload == null ? null : payload.get("password"),
                    payload == null ? null : payload.get("role")
            );
            result.put("code", 200);
            result.put("message", "账号创建成功");
            result.put("data", buildSystemUser(user, currentUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "账号创建失败: " + fallbackMessage(e));
        }
        return result;
    }

    @PatchMapping("/users/{userId}/status")
    public Map<String, Object> updateUserStatus(@PathVariable Integer userId,
                                                @RequestBody(required = false) Map<String, Object> payload,
                                                HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            Integer status = payload == null ? null : parseInteger(payload.get("status"));
            SystemUser updatedUser = authService.updateUserStatus(currentUser, userId, status);
            result.put("code", 200);
            result.put("message", "账号状态更新成功");
            result.put("data", buildSystemUser(updatedUser, currentUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "账号状态更新失败: " + fallbackMessage(e));
        }
        return result;
    }

    @PatchMapping("/users/{userId}/role")
    public Map<String, Object> updateUserRole(@PathVariable Integer userId,
                                              @RequestBody(required = false) Map<String, String> payload,
                                              HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            SystemUser updatedUser = authService.updateUserRole(
                    currentUser,
                    userId,
                    payload == null ? null : payload.get("role"),
                    session
            );
            SessionUser refreshedCurrentUser = authService.getCurrentUser(session);
            result.put("code", 200);
            result.put("message", "账号角色更新成功");
            result.put("data", buildSystemUser(updatedUser, refreshedCurrentUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "账号角色更新失败: " + fallbackMessage(e));
        }
        return result;
    }

    @PostMapping("/users/{userId}/password/reset")
    public Map<String, Object> resetPassword(@PathVariable Integer userId,
                                             @RequestBody(required = false) Map<String, String> payload,
                                             HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            SessionUser currentUser = authService.getCurrentUser(session);
            SystemUser updatedUser = authService.resetUserPassword(
                    currentUser,
                    userId,
                    payload == null ? null : payload.get("newPassword")
            );
            result.put("code", 200);
            result.put("message", "账号密码重置成功");
            result.put("data", buildSystemUser(updatedUser, currentUser));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (IllegalStateException e) {
            result.put("code", 403);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "账号密码重置失败: " + fallbackMessage(e));
        }
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        authService.logout(session);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "已退出登录");
        return result;
    }

    private Map<String, Object> buildCurrentUser(SessionUser currentUser) {
        Map<String, Object> user = new HashMap<>();
        user.put("userId", currentUser.getUserId());
        user.put("username", currentUser.getUsername());
        user.put("displayName", currentUser.getDisplayName());
        user.put("role", currentUser.getRole());
        user.put("admin", authService.isAdmin(currentUser));
        return user;
    }

    private Map<String, Object> buildSystemUser(SystemUser user, SessionUser currentUser) {
        Map<String, Object> item = new HashMap<>();
        item.put("userId", user.getUserId());
        item.put("username", user.getUsername());
        item.put("displayName", user.getDisplayName());
        item.put("role", user.getRole());
        item.put("status", user.getStatus());
        item.put("createTime", user.getCreateTime());
        item.put("updateTime", user.getUpdateTime());
        item.put("lastLoginTime", user.getLastLoginTime());
        item.put("self", currentUser != null && user.getUserId().equals(currentUser.getUserId()));
        item.put("admin", AuthService.ROLE_ADMIN.equalsIgnoreCase(user.getRole()));
        return item;
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        return null;
    }

    private String fallbackMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "内部服务错误" : e.getMessage();
    }
}
