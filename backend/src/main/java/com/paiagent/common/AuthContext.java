package com.paiagent.common;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthContext {

    private AuthContext() {
    }

    public static Long getUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId instanceof Long value) {
            return value;
        }
        if (userId instanceof Number value) {
            return value.longValue();
        }
        if (userId instanceof String value && !value.isBlank()) {
            return Long.parseLong(value);
        }
        return null;
    }

    public static String getRole(HttpServletRequest request) {
        Object role = request.getAttribute("role");
        return role == null ? UserRole.USER.name() : UserRole.normalize(String.valueOf(role));
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return UserRole.isAdmin(getRole(request));
    }
}
