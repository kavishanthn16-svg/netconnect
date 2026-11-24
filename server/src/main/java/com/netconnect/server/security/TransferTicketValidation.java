package com.netconnect.server.security;

import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal preparation only: no ticket issuance, wire format or transfer integration. */
final class TransferTicketValidation {
    enum Role { SEND, RECEIVE }
    record Ticket(UUID transferId, String sessionId, Role role, Instant expiresAt) { }

    static boolean permits(Ticket ticket, UUID transferId, Session session, Role role,
                           Instant now, SessionRegistry registry) {
        return ticket != null && transferId != null && session != null && role != null && now != null
                && registry != null && ticket.expiresAt() != null && now.isBefore(ticket.expiresAt())
                && transferId.equals(ticket.transferId()) && role == ticket.role()
                && session.sessionId() != null && Objects.equals(session.sessionId(), ticket.sessionId())
                && registry.getByDisplayName(session.displayName())
                .filter(active -> Objects.equals(active.sessionId(), session.sessionId())).isPresent();
    }

    private TransferTicketValidation() { }
}
