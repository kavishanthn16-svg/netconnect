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

    private final Map<String, TransferTicket> tickets = new ConcurrentHashMap<>();

    public void register(TransferTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        tickets.put(ticket.ticket(), ticket);
    }

    /**
     * Validates and atomically consumes a one-time ticket.
     * Returns the ticket if valid, or empty if invalid, wrong role/transfer, or expired.
     */
    public Optional<TransferTicket> validateAndConsume(String ticketStr, UUID transferId, String role, long nowMillis) {
        if (ticketStr == null || transferId == null || role == null) {
            return Optional.empty();
        }
        TransferTicket ticket = tickets.remove(ticketStr);
        if (ticket == null) {
            return Optional.empty();
        }
        if (ticket.isExpired(nowMillis)) {
            return Optional.empty();
        }
        if (!ticket.transferId().equals(transferId)) {
            return Optional.empty();
        }
        if (!ticket.role().equals(role)) {
            return Optional.empty();
        }
        return Optional.of(ticket);
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
