package com.netconnect.client.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EnvelopeCodec;
import com.netconnect.common.protocol.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatProtocolAndModelTest {
    private static ChatMessage group(String from, String text) {
        return new ChatMessage(UUID.randomUUID(), from, null, ChatMessage.Scope.GROUP, text, Instant.now());
    }

    private static ChatMessage priv(String from, String to, String text) {
        return new ChatMessage(UUID.randomUUID(), from, to, ChatMessage.Scope.PRIVATE, text, Instant.now());
    }

    private static Envelope deliver(ObjectNode payload) {
        return new Envelope(1, EventType.CHAT_DELIVER, UUID.randomUUID(), "sid", payload);
    }

    private static ObjectNode validPrivatePayload() {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("messageId", UUID.randomUUID().toString());
        p.put("fromUser", "Alice");
        p.put("toUser", "Bobby");
        p.put("scope", "PRIVATE");
        p.put("text", "hi");
        p.put("sentAt", "2026-11-24T10:15:30Z");
        return p;
    }

    // ---- ChatProtocol ----

    @Test
    void sendEnvelopesUseExactFrozenFieldNamesAndSurviveTheCodec() {
        Envelope g = EnvelopeCodec.decode(EnvelopeCodec.encode(ChatProtocol.groupSend("sid", "Hello")));
        assertEquals(EventType.CHAT_GROUP_SEND, g.type());
        assertEquals("sid", g.sessionId());
        assertEquals(List.of("text"), g.payload().properties().stream().map(java.util.Map.Entry::getKey).toList());

        Envelope p = EnvelopeCodec.decode(EnvelopeCodec.encode(ChatProtocol.privateSend("sid", "Bobby", "Psst")));
        assertEquals(EventType.CHAT_PRIVATE_SEND, p.type());
        assertEquals("Bobby", p.payload().get("toUser").asText());
        assertEquals("Psst", p.payload().get("text").asText());
    }

    @Test
    void parsesGroupAndPrivateDeliveries() {
        ChatMessage m = ChatProtocol.parseDeliver(deliver(validPrivatePayload()));
        assertEquals(ChatMessage.Scope.PRIVATE, m.scope());
        assertEquals("Bobby", m.toUser());
        assertEquals(Instant.parse("2026-11-24T10:15:30Z"), m.sentAt());

        ObjectNode g = validPrivatePayload();
        g.remove("toUser");
        g.put("scope", "GROUP");
        assertNull(ChatProtocol.parseDeliver(deliver(g)).toUser());
    }

    @Test
    void rejectsMalformedDeliveries() {
        for (String field : new String[]{"messageId", "fromUser", "scope", "text", "sentAt", "toUser"}) {
            ObjectNode p = validPrivatePayload();
            p.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(deliver(p)), field);
        }
        ObjectNode badId = validPrivatePayload();
        badId.put("messageId", "nope");
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(deliver(badId)));
        ObjectNode badScope = validPrivatePayload();
        badScope.put("scope", "BROADCAST");
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(deliver(badScope)));
        ObjectNode badTime = validPrivatePayload();
        badTime.put("sentAt", "yesterday");
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(deliver(badTime)));
        ObjectNode numeric = validPrivatePayload();
        numeric.put("text", 5);
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(deliver(numeric)));
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(null));
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.parseDeliver(
                new Envelope(1, EventType.ERROR, UUID.randomUUID(), null, validPrivatePayload())));
    }

    @Test
    void validatesTextLength() {
        assertNotNull(ChatProtocol.validateText(null));
        assertNotNull(ChatProtocol.validateText(""));
        assertNotNull(ChatProtocol.validateText("   "));
        assertNull(ChatProtocol.validateText("a"));
        assertNull(ChatProtocol.validateText("a".repeat(2000)));
        assertNotNull(ChatProtocol.validateText("a".repeat(2001)));
    }

    @Test
    void privateMessageWithoutRecipientIsNotConstructible() {
        assertThrows(IllegalArgumentException.class, () -> priv("Alice", null, "x"));
    }

    // ---- ChatConversationModel ----

    @Test
    void duplicatesByMessageIdAreIgnored() {
        ChatConversationModel model = new ChatConversationModel("Alice");
        ChatMessage m = group("Bobby", "hi");
        assertTrue(model.add(m));
        assertFalse(model.add(m));
        assertEquals(1, model.group().size());
    }

    @Test
    void groupAndPrivateConversationsAreSeparated() {
        ChatConversationModel model = new ChatConversationModel("Alice");
        model.add(group("Bobby", "to all"));
        model.add(priv("Bobby", "Alice", "from bobby"));
        model.add(priv("Alice", "Bobby", "to bobby"));
        model.add(priv("Carol", "Alice", "from carol"));
        assertEquals(1, model.group().size());
        assertEquals(List.of("from bobby", "to bobby"), model.privateWith("bobby").stream().map(ChatMessage::text).toList());
        assertEquals(List.of("from carol"), model.privateWith("Carol").stream().map(ChatMessage::text).toList());
        assertTrue(model.privateWith(null).isEmpty());
        assertEquals(List.of("Bobby", "Carol"), List.copyOf(model.privatePeers()));
    }

    @Test
    void privateMessageNotInvolvingSelfIsRejectedDefensively() {
        ChatConversationModel model = new ChatConversationModel("Alice");
        assertFalse(model.add(priv("Bobby", "Carol", "not for Alice")));
        assertTrue(model.privatePeers().isEmpty());
    }

    @Test
    void historyIsBounded() {
        ChatConversationModel model = new ChatConversationModel("Alice");
        for (int i = 0; i < ChatConversationModel.MAX_HISTORY + 50; i++) {
            model.add(group("Bobby", "m" + i));
        }
        assertEquals(ChatConversationModel.MAX_HISTORY, model.group().size());
        assertEquals("m50", model.group().get(0).text());
    }
}
