package com.netconnect.server.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for single-use transfer tickets with 60-second expiration.
 */
public final class TicketRegistry {

    private final java.util.function.Predicate<TransferTicket> active;
    public TicketRegistry() { this(ticket -> true); }
    public TicketRegistry(java.util.function.Predicate<TransferTicket> active) {
        this.active = Objects.requireNonNull(active);
    }

    private final Map<String, TransferTicket> tickets = new ConcurrentHashMap<>();

    public void register(TransferTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        tickets.put(ticket.ticket(), ticket);
    }

    /**
     * Validates and atomically consumes a one-time ticket.
     * Returns the ticket if valid, or empty if invalid, wrong role/transfer, or expired.
     */
    public synchronized Optional<TransferTicket> validateAndConsume(String ticketStr, UUID transferId, String role, long nowMillis) {
        if (ticketStr == null || transferId == null || role == null) {
            return Optional.empty();
        }
        TransferTicket ticket = tickets.get(ticketStr);
        if (ticket == null) {
            return Optional.empty();
        }
        if (nowMillis >= ticket.expiresAtMillis()) {
            tickets.remove(ticketStr, ticket);
            return Optional.empty();
        }
        if (!ticket.transferId().equals(transferId)) {
            return Optional.empty();
        }
        if (!ticket.role().equals(role)) {
            return Optional.empty();
        }
        if (!active.test(ticket)) {
            tickets.remove(ticketStr, ticket);
            return Optional.empty();
        }
        return tickets.remove(ticketStr, ticket) ? Optional.of(ticket) : Optional.empty();
    }

    public void invalidateForTransfer(UUID transferId) {
        if (transferId == null) {
            return;
        }
        tickets.values().removeIf(t -> transferId.equals(t.transferId()));
    }

    public void invalidateForSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        tickets.values().removeIf(t -> sessionId.equals(t.sessionId()));
    }

    public void purgeExpired(long nowMillis) {
        tickets.values().removeIf(t -> t.isExpired(nowMillis));
    }

    public int size() {
        return tickets.size();
    }
}
