package com.example.bysjdesign.service;

import com.example.bysjdesign.campus.entity.SystemUser;
import com.example.bysjdesign.repository.SystemUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpSession;
import java.util.Date;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class AuthService {

    public static final String SESSION_USER_KEY = "CURRENT_SYSTEM_USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_VIEWER = "VIEWER";

    private static final int ACTIVE_STATUS = 1;
    private static final int DISABLED_STATUS = 0;
    private static final int SESSION_TIMEOUT_SECONDS = 4 * 60 * 60;

    private final SystemUserRepository systemUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.auth.bootstrap.username:\u5f6d\u4e8e\u664f}")
    private String bootstrapUsername;

    @Value("${app.auth.bootstrap.password:123456}")
    private String bootstrapPassword;

    @Value("${app.auth.bootstrap.display-name:\u5f6d\u4e8e\u664f}")
    private String bootstrapDisplayName;

    public AuthService(SystemUserRepository systemUserRepository,
                       PasswordEncoder passwordEncoder) {
        this.systemUserRepository = systemUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void ensureBootstrapUser() {
        if (systemUserRepository.count() > 0) {
            return;
        }

        Date now = new Date();
        SystemUser user = new SystemUser();
        user.setUsername(defaultIfBlank(normalize(bootstrapUsername), "\u5f6d\u4e8e\u664f"));
        user.setPasswordHash(passwordEncoder.encode(defaultIfBlank(bootstrapPassword, "123456")));
        user.setDisplayName(defaultIfBlank(normalize(bootstrapDisplayName), "\u5f6d\u4e8e\u664f"));
        user.setRole(ROLE_ADMIN);
        user.setStatus(ACTIVE_STATUS);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        systemUserRepository.save(user);
    }

    @Transactional
    public SessionUser login(String username, String password, HttpSession session) {
        if (session == null) {
            throw new IllegalArgumentException("登录会话不可用");
        }

        String normalizedUsername = normalize(username);
        String rawPassword = password == null ? "" : password;
        if (normalizedUsername.isEmpty() || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        SystemUser user = systemUserRepository.findByUsername(normalizedUsername)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!Integer.valueOf(ACTIVE_STATUS).equals(user.getStatus())) {
            throw new IllegalStateException("当前账号已被禁用");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        Date now = new Date();
        user.setLastLoginTime(now);
        user.setUpdateTime(now);
        systemUserRepository.save(user);

        SessionUser sessionUser = toSessionUser(user);
        session.setAttribute(SESSION_USER_KEY, sessionUser);
        session.setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS);
        return sessionUser;
    }

    public SessionUser getCurrentUser(HttpSession session) {
        if (session == null) {
            return null;
        }

        Object currentUser = session.getAttribute(SESSION_USER_KEY);
        if (!(currentUser instanceof SessionUser sessionUser)) {
            return null;
        }

        SystemUser user = systemUserRepository.findById(sessionUser.getUserId()).orElse(null);
        if (user == null || !Integer.valueOf(ACTIVE_STATUS).equals(user.getStatus())) {
            session.removeAttribute(SESSION_USER_KEY);
            return null;
        }

        SessionUser refreshed = toSessionUser(user);
        if (!sameSessionUser(sessionUser, refreshed)) {
            session.setAttribute(SESSION_USER_KEY, refreshed);
        }
        return refreshed;
    }

    public boolean isLoggedIn(HttpSession session) {
        return getCurrentUser(session) != null;
    }

    public boolean isAdmin(SessionUser currentUser) {
        return currentUser != null && ROLE_ADMIN.equalsIgnoreCase(currentUser.getRole());
    }

    public void requireAdminRole(SessionUser currentUser) {
        ensureAdmin(currentUser);
    }

    public void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    public List<SystemUser> listUsers(SessionUser currentUser) {
        ensureAdmin(currentUser);
        return systemUserRepository.findAllByOrderByStatusDescUserIdAsc();
    }

    @Transactional
    public SystemUser createUser(SessionUser currentUser,
                                 String username,
                                 String displayName,
                                 String rawPassword,
                                 String role) {
        ensureAdmin(currentUser);

        String normalizedUsername = normalize(username);
        String normalizedDisplayName = normalize(displayName);
        String normalizedRole = normalizeRole(role);
        validateUsername(normalizedUsername);
        validatePassword(rawPassword);
        validateRole(normalizedRole);

        if (systemUserRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("用户名已存在，请更换后再试");
        }

        Date now = new Date();
        SystemUser user = new SystemUser();
        user.setUsername(normalizedUsername);
        user.setDisplayName(defaultIfBlank(normalizedDisplayName, normalizedUsername));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(normalizedRole);
        user.setStatus(ACTIVE_STATUS);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        return systemUserRepository.save(user);
    }

    @Transactional
    public SessionUser updateCurrentProfile(SessionUser currentUser,
                                            String username,
                                            String displayName,
                                            HttpSession session) {
        SessionUser sessionUser = requireCurrentUser(currentUser);
        SystemUser user = findUser(sessionUser.getUserId());

        String normalizedUsername = normalize(username);
        String normalizedDisplayName = normalize(displayName);
        validateUsername(normalizedUsername);

        if (!normalizedUsername.equals(user.getUsername())
                && systemUserRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("用户名已存在，请更换后再试");
        }

        user.setUsername(normalizedUsername);
        user.setDisplayName(defaultIfBlank(normalizedDisplayName, normalizedUsername));
        user.setUpdateTime(new Date());
        SystemUser savedUser = systemUserRepository.save(user);

        SessionUser refreshed = toSessionUser(savedUser);
        if (session != null) {
            session.setAttribute(SESSION_USER_KEY, refreshed);
        }
        return refreshed;
    }

    @Transactional
    public void changeCurrentPassword(SessionUser currentUser,
                                      String currentPassword,
                                      String newPassword) {
        SessionUser sessionUser = requireCurrentUser(currentUser);
        SystemUser user = findUser(sessionUser.getUserId());

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("当前密码不能为空");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码校验失败");
        }

        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdateTime(new Date());
        systemUserRepository.save(user);
    }

    @Transactional
    public SystemUser updateUserStatus(SessionUser currentUser, Integer targetUserId, Integer status) {
        ensureAdmin(currentUser);
        if (!Integer.valueOf(ACTIVE_STATUS).equals(status) && !Integer.valueOf(DISABLED_STATUS).equals(status)) {
            throw new IllegalArgumentException("账号状态非法");
        }

        SystemUser targetUser = findUser(targetUserId);
        if (currentUser.getUserId().equals(targetUserId) && Integer.valueOf(DISABLED_STATUS).equals(status)) {
            throw new IllegalArgumentException("不能停用当前登录账号");
        }

        if (Integer.valueOf(ACTIVE_STATUS).equals(targetUser.getStatus())
                && Integer.valueOf(DISABLED_STATUS).equals(status)
                && ROLE_ADMIN.equalsIgnoreCase(targetUser.getRole())
                && countActiveAdmins() <= 1) {
            throw new IllegalStateException("系统至少需要保留一个可用管理员账号");
        }

        targetUser.setStatus(status);
        targetUser.setUpdateTime(new Date());
        return systemUserRepository.save(targetUser);
    }

    @Transactional
    public SystemUser updateUserRole(SessionUser currentUser,
                                     Integer targetUserId,
                                     String role,
                                     HttpSession session) {
        ensureAdmin(currentUser);
        String normalizedRole = normalizeRole(role);
        validateRole(normalizedRole);

        SystemUser targetUser = findUser(targetUserId);
        String currentRole = normalizeRole(targetUser.getRole());
        if (currentRole.equals(normalizedRole)) {
            return targetUser;
        }

        if (ROLE_ADMIN.equals(currentRole)
                && !ROLE_ADMIN.equals(normalizedRole)
                && Integer.valueOf(ACTIVE_STATUS).equals(targetUser.getStatus())
                && countActiveAdmins() <= 1) {
            throw new IllegalStateException("系统至少需要保留一个可用管理员账号");
        }

        targetUser.setRole(normalizedRole);
        targetUser.setUpdateTime(new Date());
        SystemUser savedUser = systemUserRepository.save(targetUser);

        if (session != null && currentUser.getUserId().equals(targetUserId)) {
            session.setAttribute(SESSION_USER_KEY, toSessionUser(savedUser));
        }
        return savedUser;
    }

    @Transactional
    public SystemUser resetUserPassword(SessionUser currentUser, Integer targetUserId, String newPassword) {
        ensureAdmin(currentUser);
        validatePassword(newPassword);

        SystemUser targetUser = findUser(targetUserId);
        targetUser.setPasswordHash(passwordEncoder.encode(newPassword));
        targetUser.setUpdateTime(new Date());
        return systemUserRepository.save(targetUser);
    }

    private SessionUser requireCurrentUser(SessionUser currentUser) {
        if (currentUser == null) {
            throw new IllegalStateException("请先登录");
        }
        return currentUser;
    }

    private void ensureAdmin(SessionUser currentUser) {
        SessionUser sessionUser = requireCurrentUser(currentUser);
        if (!ROLE_ADMIN.equalsIgnoreCase(sessionUser.getRole())) {
            throw new IllegalStateException("当前账号无权执行该操作");
        }
    }

    private SystemUser findUser(Integer userId) {
        return systemUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应账号"));
    }

    private SessionUser toSessionUser(SystemUser user) {
        return new SessionUser(
                user.getUserId(),
                user.getUsername(),
                defaultIfBlank(user.getDisplayName(), user.getUsername()),
                normalizeRole(user.getRole())
        );
    }

    private boolean sameSessionUser(SessionUser left, SessionUser right) {
        return left.getUserId().equals(right.getUserId())
                && left.getUsername().equals(right.getUsername())
                && left.getDisplayName().equals(right.getDisplayName())
                && left.getRole().equals(right.getRole());
    }

    private long countActiveAdmins() {
        return systemUserRepository.countByRoleAndStatus(ROLE_ADMIN, ACTIVE_STATUS);
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.length() < 2 || username.length() > 32) {
            throw new IllegalArgumentException("用户名长度需保持在 2 到 32 位之间");
        }
        if (!username.matches("[\\p{L}\\p{N}._-]+")) {
            throw new IllegalArgumentException("用户名仅支持中英文、数字、点、下划线和中划线");
        }
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw new IllegalArgumentException("密码长度需保持在 6 到 64 位之间");
        }
    }

    private void validateRole(String role) {
        if (!ROLE_ADMIN.equals(role) && !ROLE_VIEWER.equals(role)) {
            throw new IllegalArgumentException("角色仅支持 ADMIN 或 VIEWER");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeRole(String role) {
        String normalized = normalize(role).toUpperCase();
        return normalized.isEmpty() ? ROLE_VIEWER : normalized;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}