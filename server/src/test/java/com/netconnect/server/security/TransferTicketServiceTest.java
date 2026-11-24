package com.netconnect.server.security;

import com.netconnect.server.session.ConcurrentSessionRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferTicketServiceTest {
    private final ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final MutableClock clock = new MutableClock();
    private final TransferTicketService tickets = new TransferTicketService(registry, clock);
    private final UUID transfer = UUID.randomUUID();
    private com.netconnect.common.contracts.Session login(String name) {
        new EnvironmentAuthService(registry, () -> "test-code").authenticate(name, "test-code");
        return registry.getByDisplayName(name).orElseThrow();
    }
    @Test void bindsSessionTransferAndRoleAndConsumesOnlyOnce() {
        var alice = login("Alice");
        var bob = login("Bobby");
        String token = tickets.issue(transfer, alice, TransferTicketService.Role.SEND);
        assertFalse(tickets.consume("unknown", transfer, alice, TransferTicketService.Role.SEND));
        assertFalse(tickets.consume(token, transfer, bob, TransferTicketService.Role.SEND));
        assertFalse(tickets.consume(token, UUID.randomUUID(), alice, TransferTicketService.Role.SEND));
        assertFalse(tickets.consume(token, transfer, alice, TransferTicketService.Role.RECEIVE));
        assertTrue(tickets.consume(token, transfer, alice, TransferTicketService.Role.SEND));
        assertFalse(tickets.consume(token, transfer, alice, TransferTicketService.Role.SEND));
    }
    @Test void expiresAtExactlySixtySeconds() {
        var alice = login("Alice");
        String token = tickets.issue(transfer, alice, TransferTicketService.Role.RECEIVE);
        clock.value = now.plusSeconds(60);
        assertFalse(tickets.consume(token, transfer, alice, TransferTicketService.Role.RECEIVE));
    }
    @Test void revokedAndDisconnectedSessionsCannotUseTickets() {
        var alice = login("Alice");
        String revoked = tickets.issue(transfer, alice, TransferTicketService.Role.SEND);
        tickets.revokeSession(alice.sessionId());
        assertFalse(tickets.consume(revoked, transfer, alice, TransferTicketService.Role.SEND));
        String disconnected = tickets.issue(transfer, alice, TransferTicketService.Role.SEND);
        registry.remove(alice.sessionId());
        assertFalse(tickets.consume(disconnected, transfer, alice, TransferTicketService.Role.SEND));
        assertThrows(IllegalStateException.class, () -> tickets.issue(transfer, alice, TransferTicketService.Role.SEND));
    }
    @Test void concurrentReplayHasExactlyOneWinner() throws Exception {
        var alice = login("Alice");
        String token = tickets.issue(transfer, alice, TransferTicketService.Role.SEND);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> { start.await(); return tickets.consume(token, transfer, alice, TransferTicketService.Role.SEND); };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
    }
    private class MutableClock extends Clock {
        Instant value = now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
}
