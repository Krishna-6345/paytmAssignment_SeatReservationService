package com.paytm.reservation.security;

public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_ADMIN = new ThreadLocal<>();

    private UserContext() {}

    public static void set(String userId, boolean isAdmin) {
        CURRENT_USER.set(userId);
        IS_ADMIN.set(isAdmin);
    }

    public static String getUserId() {
        return CURRENT_USER.get();
    }

    public static boolean isAdmin() {
        Boolean admin = IS_ADMIN.get();
        return admin != null && admin;
    }

    public static void clear() {
        CURRENT_USER.remove();
        IS_ADMIN.remove();
    }
}
