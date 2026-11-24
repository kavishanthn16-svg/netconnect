package com.netconnect.server.security;

import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Security-local API pending the transfer owner's wire contract. Never accept client-created metadata. */
public final class TransferTicketService {
    public enum Role { SEND, RECEIVE }
    private final SessionRegistry registry;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, TransferTicketValidation.Ticket> tickets = new HashMap<>();

    public TransferTicketService(SessionRegistry registry) { this(registry, Clock.systemUTC()); }
    public TransferTicketService(SessionRegistry registry, Clock clock) {
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized String issue(UUID transferId, Session session, Role role) {
        Objects.requireNonNull(transferId);
        Objects.requireNonNull(session);
        Objects.requireNonNull(role);
        var ticket = new TransferTicketValidation.Ticket(transferId, session.sessionId(),
                TransferTicketValidation.Role.valueOf(role.name()), clock.instant().plusSeconds(60));
        if (!TransferTicketValidation.permits(ticket, transferId, session, ticket.role(), clock.instant(), registry))
            throw new IllegalStateException("An active authenticated session is required");
        purge();
        if (tickets.size() >= 10000) throw new IllegalStateException("Ticket capacity reached");
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tickets.containsKey(token));
        tickets.put(token, ticket);
        return token;
    }

    /** Atomically validates and consumes; invalid attempts cannot consume another session's ticket. */
    public synchronized boolean consume(String token, UUID transferId, Session session, Role role) {
        purge();
        var ticket = tickets.get(token);
        if (role == null || !TransferTicketValidation.permits(ticket, transferId, session,
                TransferTicketValidation.Role.valueOf(role.name()), clock.instant(), registry)) return false;
        tickets.remove(token);
        return true;
    }

    public synchronized void revokeSession(String sessionId) {
        tickets.values().removeIf(ticket -> Objects.equals(ticket.sessionId(), sessionId));
    }

    private void purge() {
        tickets.values().removeIf(ticket -> !clock.instant().isBefore(ticket.expiresAt()));
    }
}
