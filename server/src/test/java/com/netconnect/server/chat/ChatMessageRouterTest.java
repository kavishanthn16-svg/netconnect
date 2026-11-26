package com.netconnect.server.chat;

import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.ErrorCode;
import com.netconnect.common.protocol.EventType;
import com.netconnect.server.chat.ChatTestSupport.RecordingGateway;
import com.netconnect.server.chat.ChatTestSupport.TestSession;
import com.netconnect.server.session.ConcurrentSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.netconnect.server.chat.ChatTestSupport.login;
import static org.junit.jupiter.api.Assertions.*;

class ChatMessageRouterTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:15:30Z");

    private ConcurrentSessionRegistry registry;
    private RecordingGateway gateway;
    private ChatMessageRouter router;
    private TestSession alice;
    private TestSession bobby;
    private TestSession carol;

    @BeforeEach
    void setUp() {
        registry = new ConcurrentSessionRegistry();
        gateway = new RecordingGateway();
        router = new ChatMessageRouter(registry, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
        alice = login(registry, "Alice");
        bobby = login(registry, "Bobby");
        carol = login(registry, "Carol");
    }

    @Test
    void groupMessageReachesEveryUserExactlyOnceIncludingSender() {
        router.sendGroup(alice, "Hello team");
        for (TestSession user : List.of(alice, bobby, carol)) {
            List<Envelope> delivered = gateway.ofType(user, EventType.CHAT_DELIVER);
            assertEquals(1, delivered.size(), user.displayName());
            Envelope envelope = delivered.get(0);
            assertEquals(user.sessionId(), envelope.sessionId());
            assertEquals("GROUP", envelope.payload().get("scope").asText());
            assertEquals("Alice", envelope.payload().get("fromUser").asText());
            assertEquals("Hello team", envelope.payload().get("text").asText());
            assertFalse(envelope.payload().has("toUser"));
        }
    }

    @Test
    void deliverCarriesUuidMessageIdAndUtcSentAt() {
        router.sendGroup(alice, "hi");
        var payload = gateway.ofType(bobby, EventType.CHAT_DELIVER).get(0).payload();
        assertDoesNotThrow(() -> UUID.fromString(payload.get("messageId").asText()));
        assertEquals(NOW, Instant.parse(payload.get("sentAt").asText()));
        assertTrue(payload.get("sentAt").asText().endsWith("Z"));
    }

    @Test
    void allRecipientsShareOneMessageIdButEachMessageGetsANewOne() {
        router.sendGroup(alice, "one");
        router.sendGroup(alice, "two");
        var aliceMsgs = gateway.ofType(alice, EventType.CHAT_DELIVER);
        var bobbyMsgs = gateway.ofType(bobby, EventType.CHAT_DELIVER);
        assertEquals(aliceMsgs.get(0).payload().get("messageId"), bobbyMsgs.get(0).payload().get("messageId"));
        assertNotEquals(aliceMsgs.get(0).payload().get("messageId"), aliceMsgs.get(1).payload().get("messageId"));
    }

    @Test
    void privateMessageReachesSenderAndRecipientOnlyWithNoLeak() {
        router.sendPrivate(alice, "Bobby", "secret");
        assertEquals(1, gateway.ofType(alice, EventType.CHAT_DELIVER).size());
        assertEquals(1, gateway.ofType(bobby, EventType.CHAT_DELIVER).size());
        assertTrue(gateway.all(carol).isEmpty(), "third user must receive nothing");
        var payload = gateway.ofType(bobby, EventType.CHAT_DELIVER).get(0).payload();
        assertEquals("PRIVATE", payload.get("scope").asText());
        assertEquals("Bobby", payload.get("toUser").asText());
        assertEquals("Alice", payload.get("fromUser").asText());
    }

    @Test
    void privateRecipientLookupIsCaseInsensitiveAndReportsCanonicalName() {
        router.sendPrivate(alice, "bObBy", "hey");
        assertEquals("Bobby", gateway.ofType(bobby, EventType.CHAT_DELIVER).get(0).payload().get("toUser").asText());
    }

    @Test
    void privateMessageToSelfIsDeliveredOnce() {
        router.sendPrivate(alice, "Alice", "note to self");
        assertEquals(1, gateway.ofType(alice, EventType.CHAT_DELIVER).size());
        assertTrue(gateway.all(bobby).isEmpty());
    }

    @Test
    void offlineOrUnknownRecipientGetsUserOfflineErrorToSenderOnly() {
        UUID requestId = UUID.randomUUID();
        router.sendPrivate(alice, "Nobody", "hello?", requestId);
        assertEquals(1, gateway.all(alice).size());
        Envelope error = gateway.all(alice).get(0);
        assertEquals(EventType.ERROR, error.type());
        assertEquals(requestId, error.requestId());
        assertEquals(ErrorCode.USER_OFFLINE.name(), error.payload().get("code").asText());
        assertTrue(gateway.all(bobby).isEmpty());
        assertTrue(gateway.all(carol).isEmpty());
    }

    @Test
    void recipientWhoLeftIsOffline() {
        registry.remove(bobby.sessionId());
        router.sendPrivate(alice, "Bobby", "still there?");
        assertEquals(ErrorCode.USER_OFFLINE.name(),
                gateway.ofType(alice, EventType.ERROR).get(0).payload().get("code").asText());
        assertTrue(gateway.all(bobby).isEmpty());
    }

    @Test
    void invalidTextIsRejectedWithInvalidRequestAndNothingDelivered() {
        for (String bad : new String[]{null, "", "   ", "x".repeat(2001)}) {
            router.sendGroup(alice, bad);
            router.sendPrivate(alice, "Bobby", bad);
        }
        List<Envelope> all = gateway.all(alice);
        assertEquals(8, all.size());
        assertTrue(all.stream().allMatch(e -> e.type() == EventType.ERROR
                && ErrorCode.INVALID_REQUEST.name().equals(e.payload().get("code").asText())));
        assertTrue(gateway.all(bobby).isEmpty());
        assertTrue(gateway.all(carol).isEmpty());
    }

    @Test
    void boundaryLengthsOneAndTwoThousandAreAccepted() {
        router.sendGroup(alice, "x");
        router.sendGroup(alice, "y".repeat(2000));
        assertEquals(2, gateway.ofType(bobby, EventType.CHAT_DELIVER).size());
        assertTrue(gateway.ofType(alice, EventType.ERROR).isEmpty());
    }

    @Test
    void maxLengthDeliveryFitsInsideTheSixteenKibControlLine() {
        router.sendGroup(alice, "\u0001".repeat(2000)); // worst case JSON escaping (6 bytes each)
        Envelope delivered = gateway.ofType(bobby, EventType.CHAT_DELIVER).get(0);
        assertDoesNotThrow(() -> com.netconnect.common.protocol.EnvelopeCodec.encode(delivered));
    }

    @Test
    void privateMessageWithMissingRecipientIsInvalid() {
        router.sendPrivate(alice, " ", "hi");
        assertEquals(ErrorCode.INVALID_REQUEST.name(),
                gateway.ofType(alice, EventType.ERROR).get(0).payload().get("code").asText());
    }

    @Test
    void unregisteredSenderIsNotAuthenticatedAndNothingIsDelivered() {
        TestSession ghost = TestSession.of("Ghost");
        router.sendGroup(ghost, "boo");
        router.sendPrivate(ghost, "Bobby", "boo");
        assertEquals(2, gateway.all(ghost).size());
        assertTrue(gateway.all(ghost).stream().allMatch(e -> e.type() == EventType.ERROR
                && ErrorCode.NOT_AUTHENTICATED.name().equals(e.payload().get("code").asText())));
        assertTrue(gateway.all(alice).isEmpty() && gateway.all(bobby).isEmpty());
    }

    @Test
    void staleSessionReusingARegisteredNameCannotSend() {
        TestSession impostor = new TestSession("other-sid", "Alice");
        router.sendGroup(impostor, "spoof");
        assertTrue(gateway.ofType(bobby, EventType.CHAT_DELIVER).isEmpty());
        assertEquals(ErrorCode.NOT_AUTHENTICATED.name(),
                gateway.ofType(impostor, EventType.ERROR).get(0).payload().get("code").asText());
    }

    @Test
    void oneFailingRecipientDoesNotBlockOthers() {
        gateway.failFor = s -> s.sessionId().equals(bobby.sessionId());
        assertDoesNotThrow(() -> router.sendGroup(alice, "resilient"));
        assertEquals(1, gateway.ofType(alice, EventType.CHAT_DELIVER).size());
        assertEquals(1, gateway.ofType(carol, EventType.CHAT_DELIVER).size());
        assertTrue(gateway.all(bobby).isEmpty());
    }

    @Test
    void errorEchoesRequestIdAndCarriesBoundedMessage() {
        UUID requestId = UUID.randomUUID();
        router.sendGroup(alice, "", requestId);
        Envelope error = gateway.all(alice).get(0);
        assertEquals(requestId, error.requestId());
        assertEquals(alice.sessionId(), error.sessionId());
        assertTrue(error.payload().get("message").asText().length() < 200);
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(NullPointerException.class, () -> new ChatMessageRouter(null, gateway));
        assertThrows(NullPointerException.class, () -> new ChatMessageRouter(registry, null));
    }
}
