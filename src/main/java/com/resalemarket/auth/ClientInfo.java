package com.resalemarket.auth;

/** Where a sign-in came from, kept with the refresh token so a person (or support) can recognise a session. */
public record ClientInfo(String ipAddress, String userAgent) {

    /** Column widths of {@code refresh_tokens.ip_address} and {@code user_agent}. */
    private static final int MAX_IP_LENGTH = 45;

    private static final int MAX_USER_AGENT_LENGTH = 300;

    public ClientInfo {
        ipAddress = truncate(ipAddress, MAX_IP_LENGTH);
        userAgent = truncate(userAgent, MAX_USER_AGENT_LENGTH);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
