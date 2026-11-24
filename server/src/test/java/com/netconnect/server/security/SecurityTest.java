package com.netconnect.server.security;

import com.netconnect.common.contracts.*;
import com.netconnect.common.protocol.*;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {
    private final SessionRegistry registry = new ConcurrentSessionRegistry();
    private final AuthService auth = new EnvironmentAuthService(registry, () -> "test-only-access-code");

    @Test void correctCodeRegistersIdentity() {
        AuthResult result = auth.authenticate("Alice", "test-only-access-code");
        assertTrue(result.authenticated());
        assertEquals(result.sessionId(), registry.getByDisplayName("Alice").orElseThrow().sessionId());
    }

    @Test void incorrectOrMissingCredentialsFailWithoutRegistration() {
        assertEquals("ACCESS_DENIED", auth.authenticate("Alice", "wrong").code());
        assertFalse(auth.authenticate("Alice", null).authenticated());
        assertFalse(auth.authenticate("Alice", "   ").authenticated());
        assertFalse(new EnvironmentAuthService(registry, () -> " ").authenticate("Alice", " ").authenticated());
        assertFalse(new EnvironmentAuthService(registry, () -> null).authenticate("Alice", "anything").authenticated());
        assertTrue(registry.listUsers().isEmpty());
    }

    @Test void invalidNamesFail() {
        for (String name : new String[]{"", "ab", "   ", "Al\nice", "x".repeat(25)})
            assertEquals("INVALID_REQUEST", auth.authenticate(name, "test-only-access-code").code());
        assertFalse(auth.authenticate(null, "test-only-access-code").authenticated());
        assertTrue(auth.authenticate("x".repeat(24), "test-only-access-code").authenticated());
    }

    @Test void duplicateNamesUseAtomicRegistryRegistration() {
        assertTrue(auth.authenticate("Alice", "test-only-access-code").authenticated());
        assertEquals("NAME_TAKEN", auth.authenticate("ALICE", "test-only-access-code").code());
    }

    @Test void authenticationStateRejectsSpoofedAndRemovedSessions() {
        AuthResult result = auth.authenticate("Alice", "test-only-access-code");
        Envelope request = Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(), result.sessionId(),
                JsonNodeFactory.instance.objectNode());
        assertFalse(AuthenticationState.permits(null, null, request, registry));
        assertFalse(AuthenticationState.permits(result.sessionId(), "Alice", null, registry));
        assertFalse(AuthenticationState.permits(result.sessionId(), "Alice", request, null));
        assertFalse(AuthenticationState.permits("other-session", "Alice", request, registry));
        assertTrue(AuthenticationState.permits(result.sessionId(), "Alice", request, registry));
        Envelope fileRequest = Envelope.request(EventType.FILE_OFFER, UUID.randomUUID(), result.sessionId(),
                JsonNodeFactory.instance.objectNode());
        assertFalse(AuthenticationState.permits(null, null, fileRequest, registry));
        assertTrue(AuthenticationState.permits(result.sessionId(), "Alice", fileRequest, registry));
        registry.remove(result.sessionId());
        assertFalse(AuthenticationState.permits(result.sessionId(), "Alice", request, registry));
        assertFalse(AuthenticationState.permits(result.sessionId(), "Alice", fileRequest, registry));
    }

    @Test void ticketRequiresMatchingTransferOwnerRoleAndUnexpiredActiveSession() {
        auth.authenticate("Alice", "test-only-access-code");
        auth.authenticate("Bobby", "test-only-access-code");
        Session alice = registry.getByDisplayName("Alice").orElseThrow();
        Session bob = registry.getByDisplayName("Bobby").orElseThrow();
        UUID transfer = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var role = TransferTicketValidation.Role.SEND;
        var ticket = new TransferTicketValidation.Ticket(transfer, alice.sessionId(), role, now.plusSeconds(60));
        assertTrue(TransferTicketValidation.permits(ticket, transfer, alice, role, now, registry));
        assertFalse(TransferTicketValidation.permits(ticket, transfer, alice, role, now.plusSeconds(60), registry));
        assertFalse(TransferTicketValidation.permits(ticket, transfer, bob, role, now, registry));
        assertFalse(TransferTicketValidation.permits(ticket, UUID.randomUUID(), alice, role, now, registry));
        assertFalse(TransferTicketValidation.permits(ticket, transfer, alice, TransferTicketValidation.Role.RECEIVE, now, registry));
        assertFalse(TransferTicketValidation.permits(null, transfer, alice, role, now, registry));
        registry.remove(alice.sessionId());
        assertFalse(TransferTicketValidation.permits(ticket, transfer, alice, role, now, registry));
    }
}
