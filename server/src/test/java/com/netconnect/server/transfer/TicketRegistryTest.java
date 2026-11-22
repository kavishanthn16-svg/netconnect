package com.netconnect.server.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRegistryTest {

    private TicketRegistry registry;
    private final UUID transferId = UUID.randomUUID();
    private final String sessionId = "sess-123";
    private final String ticket = "ticket-xyz-456";

    @BeforeEach
    void setUp() {
        registry = new TicketRegistry();
    }

    @Test
    @DisplayName("Issued ticket can be successfully consumed once")
    void singleUseTicketValidation() {
        long now = 1000L;
        long expiresAt = now + 60_000L;
        registry.register(new TransferTicket(ticket, transferId, sessionId, "SEND", expiresAt));

        assertEquals(1, registry.size());

        // First validation succeeds
        Optional<TransferTicket> consumed = registry.validateAndConsume(ticket, transferId, "SEND", now + 10_000L);
        assertTrue(consumed.isPresent());
        assertEquals(ticket, consumed.get().ticket());

        // Second validation fails because it is single-use
        Optional<TransferTicket> secondAttempt = registry.validateAndConsume(ticket, transferId, "SEND", now + 10_000L);
        assertFalse(secondAttempt.isPresent());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("Expired ticket is rejected")
    void expiredTicketRejected() {
        long now = 1000L;
        long expiresAt = now + 1000L;
        registry.register(new TransferTicket(ticket, transferId, sessionId, "RECEIVE", expiresAt));

        // Attempting to consume after expiry
        Optional<TransferTicket> consumed = registry.validateAndConsume(ticket, transferId, "RECEIVE", now + 5000L);
        assertFalse(consumed.isPresent());
    }

    @Test
    @DisplayName("Role or transferId mismatch is rejected")
    void mismatchedTicketRejected() {
        long now = 1000L;
        long expiresAt = now + 60_000L;
        registry.register(new TransferTicket(ticket, transferId, sessionId, "SEND", expiresAt));

        // Wrong role
        Optional<TransferTicket> wrongRole = registry.validateAndConsume(ticket, transferId, "RECEIVE", now);
        assertFalse(wrongRole.isPresent());

        // Wrong transferId
        Optional<TransferTicket> wrongTransfer = registry.validateAndConsume(ticket, UUID.randomUUID(), "SEND", now);
        assertFalse(wrongTransfer.isPresent());
    }

    @Test
    @DisplayName("Invalidation for session removes tickets")
    void invalidateForSession() {
        long expiresAt = 100_000L;
        registry.register(new TransferTicket("t1", transferId, sessionId, "SEND", expiresAt));
        registry.register(new TransferTicket("t2", UUID.randomUUID(), sessionId, "RECEIVE", expiresAt));
        registry.register(new TransferTicket("t3", UUID.randomUUID(), "other-session", "SEND", expiresAt));

        assertEquals(3, registry.size());
        registry.invalidateForSession(sessionId);
        assertEquals(1, registry.size());
    }
}
