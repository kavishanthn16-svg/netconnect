package com.netconnect.common.contracts;

/** Minimal shared identity contract; transport ownership remains in server/security. */
public interface Session {
    String sessionId();

    String displayName();
}
