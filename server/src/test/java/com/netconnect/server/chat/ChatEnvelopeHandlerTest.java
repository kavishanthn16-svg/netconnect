package com.netconnect.server.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.ErrorCode;
import com.netconnect.common.protocol.EventType;
import com.netconnect.server.chat.ChatTestSupport.RecordingGateway;
import com.netconnect.server.chat.ChatTestSupport.TestSession;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.netconnect.server.chat.ChatTestSupport.login;
import static com.netconnect.server.chat.ChatTestSupport.payload;
import static com.netconnect.server.chat.ChatTestSupport.request;
import static org.junit.jupiter.api.Assertions.*;

class ChatEnvelopeHandlerTest {
    private RecordingGateway gateway;
    private ChatEnvelopeHandler handler;
    private TestSession alice;
    private TestSession bobby;

    @BeforeEach
    void setUp() {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        gateway = new RecordingGateway();
        handler = new ChatEnvelopeHandler(new ChatMessageRouter(registry, gateway));
        alice = login(registry, "Alice");
        bobby = login(registry, "Bobby");
    }

    private String errorCode(TestSession session) {
        return gateway.ofType(session, EventType.ERROR).get(0).payload().get("code").asText();
    }

    @Test
    void groupSendEnvelopeIsRoutedAndConsumed() {
        assertTrue(handler.handle(alice, request(EventType.CHAT_GROUP_SEND, alice.sessionId(), payload("text", "hi"))));
        assertEquals(1, gateway.ofType(bobby, EventType.CHAT_DELIVER).size());
        assertEquals(1, gateway.ofType(alice, EventType.CHAT_DELIVER).size());
    }

    @Test
    void privateSendEnvelopeIsRouted() {
        assertTrue(handler.handle(alice, request(EventType.CHAT_PRIVATE_SEND, alice.sessionId(),
                payload("toUser", "Bobby", "text", "psst"))));
        assertEquals(1, gateway.ofType(bobby, EventType.CHAT_DELIVER).size());
    }

    @Test
    void nonChatEnvelopesAreLeftForOtherModules() {
        assertFalse(handler.handle(alice, request(EventType.FILE_OFFER, alice.sessionId(), payload())));
        assertFalse(handler.handle(alice, request(EventType.DISCONNECT, alice.sessionId(), payload("reason", "x"))));
        assertFalse(handler.handle(alice, null));
        assertTrue(gateway.all(alice).isEmpty());
    }

    @Test
    void errorRepliesEchoTheRequestId() {
        Envelope bad = request(EventType.CHAT_GROUP_SEND, alice.sessionId(), payload());
        handler.handle(alice, bad);
        assertEquals(bad.requestId(), gateway.ofType(alice, EventType.ERROR).get(0).requestId());
    }

    @Test
    void missingOrNonTextPayloadFieldsAreInvalidRequestNotCrashes() {
        handler.handle(alice, request(EventType.CHAT_GROUP_SEND, alice.sessionId(), payload()));
        var numeric = JsonNodeFactory.instance.objectNode().put("text", 42);
        handler.handle(alice, request(EventType.CHAT_GROUP_SEND, alice.sessionId(), numeric));
        handler.handle(alice, request(EventType.CHAT_PRIVATE_SEND, alice.sessionId(), payload("text", "no recipient")));
        handler.handle(alice, request(EventType.CHAT_PRIVATE_SEND, alice.sessionId(), payload("toUser", "Bobby")));
        var errors = gateway.ofType(alice, EventType.ERROR);
        assertEquals(4, errors.size());
        assertTrue(errors.stream().allMatch(e -> ErrorCode.INVALID_REQUEST.name().equals(e.payload().get("code").asText())));
        assertTrue(gateway.ofType(bobby, EventType.CHAT_DELIVER).isEmpty());
    }

    @Test
    void unauthenticatedSessionGetsNotAuthenticated() {
        TestSession ghost = TestSession.of("Ghost");
        assertTrue(handler.handle(ghost, request(EventType.CHAT_GROUP_SEND, null, payload("text", "hi"))));
        assertEquals(ErrorCode.NOT_AUTHENTICATED.name(), errorCode(ghost));
        assertTrue(gateway.all(alice).isEmpty());
    }

    @Test
    void envelopeCarryingSomeoneElsesSessionIdIsRejected() {
        handler.handle(alice, request(EventType.CHAT_GROUP_SEND, bobby.sessionId(), payload("text", "forged")));
        assertEquals(ErrorCode.NOT_AUTHENTICATED.name(), errorCode(alice));
        assertTrue(gateway.ofType(bobby, EventType.CHAT_DELIVER).isEmpty());
    }

    @Test
    void nullSessionIdOnAnAuthenticatedConnectionIsTolerated() {
        handler.handle(alice, request(EventType.CHAT_GROUP_SEND, null, payload("text", "ok")));
        assertEquals(1, gateway.ofType(bobby, EventType.CHAT_DELIVER).size());
    }
}
