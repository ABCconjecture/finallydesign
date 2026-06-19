package com.example.bysjdesign.service;

import java.io.Serializable;

public class SessionUser implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Integer userId;
    private final String username;
    private final String displayName;
    private final String role;

    public SessionUser(Integer userId, String username, String displayName, String role) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }
}