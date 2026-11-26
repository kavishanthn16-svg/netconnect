package com.netconnect.client.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EventType;

import java.time.Instant;
import java.util.UUID;

/** Builds CHAT_*_SEND envelopes and parses CHAT_DELIVER exactly as frozen in SRS 8.2. */
public final class ChatProtocol {
    public static final int MAX_TEXT_LENGTH = 2000;

    private ChatProtocol() {
    }

    /** @return null if valid, otherwise a short user-facing reason. */
    public static String validateText(String text) {
        if (text == null || text.isBlank()) {
            return "Type a message first";
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return "Message is longer than " + MAX_TEXT_LENGTH + " characters";
        }
        return null;
    }

    public static Envelope groupSend(String sessionId, String text) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("text", text);
        return Envelope.request(EventType.CHAT_GROUP_SEND, UUID.randomUUID(), sessionId, payload);
    }

    public static Envelope privateSend(String sessionId, String toUser, String text) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("toUser", toUser);
        payload.put("text", text);
        return Envelope.request(EventType.CHAT_PRIVATE_SEND, UUID.randomUUID(), sessionId, payload);
    }

    /** @throws IllegalArgumentException if the envelope is not a well-formed CHAT_DELIVER. */
    public static ChatMessage parseDeliver(Envelope envelope) {
        if (envelope == null || envelope.type() != EventType.CHAT_DELIVER) {
            throw new IllegalArgumentException("Not a CHAT_DELIVER envelope");
        }
        ObjectNode p = envelope.payload();
        try {
            ChatMessage.Scope scope = ChatMessage.Scope.valueOf(text(p, "scope", true));
            String toUser = text(p, "toUser", scope == ChatMessage.Scope.PRIVATE);
            return new ChatMessage(UUID.fromString(text(p, "messageId", true)), text(p, "fromUser", true),
                    toUser, scope, text(p, "text", true), Instant.parse(text(p, "sentAt", true)));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Malformed CHAT_DELIVER payload", malformed);
        }
    }

    private static String text(ObjectNode payload, String field, boolean required) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + field);
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return node.asText();
    }
}
