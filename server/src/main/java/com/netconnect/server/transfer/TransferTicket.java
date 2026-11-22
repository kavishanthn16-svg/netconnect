package com.netconnect.server.transfer;

import java.util.Objects;
import java.util.UUID;

/**
 * Short-lived one-time ticket binding transferId, session, and role with a 60-second expiry.
 */
public record TransferTicket(
        String ticket,
        UUID transferId,
        String sessionId,
        String role,
        long expiresAtMillis
) {
    public TransferTicket {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(role, "role");
        if (!"SEND".equals(role) && !"RECEIVE".equals(role)) {
            throw new IllegalArgumentException("Role must be SEND or RECEIVE: " + role);
        }
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
