package com.netconnect.common.contracts;

public record AuthResult(boolean authenticated, String code, String sessionId, String displayName) {
    public static AuthResult accepted(String sessionId, String displayName) {
        return new AuthResult(true, null, sessionId, displayName);
    }

    public static AuthResult denied(String code) {
        return new AuthResult(false, code, null, null);
    }
}
