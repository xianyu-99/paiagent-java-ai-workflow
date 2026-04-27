package com.paiagent.common;

public enum UserRole {
    ADMIN,
    USER;

    public static boolean isAdmin(String role) {
        return ADMIN.name().equalsIgnoreCase(role);
    }

    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return USER.name();
        }

        String normalized = role.trim().toUpperCase();
        return ADMIN.name().equals(normalized) ? ADMIN.name() : USER.name();
    }
}
