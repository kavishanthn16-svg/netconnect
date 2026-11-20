package com.netconnect.common.contracts;

public record SessionResult(boolean registered, String code, Session session) {
    public static SessionResult accepted(Session session) {
        return new SessionResult(true, null, session);
    }

    public static SessionResult rejected(String code) {
        return new SessionResult(false, code, null);
    }
}
