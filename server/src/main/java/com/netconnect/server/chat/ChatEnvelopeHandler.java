package com.netconnect.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.ErrorCode;
import com.netconnect.common.protocol.EventType;

import java.util.Objects;

/**
 * Entry point for the session owner's read loop: hand every decoded envelope here and, when it
 * returns {@code true}, the chat module consumed it. Never throws for malformed client input.
 */
public final class ChatEnvelopeHandler {
    private final ChatMessageRouter router;

    public ChatEnvelopeHandler(ChatMessageRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /** @return true if the envelope was a chat request (handled or rejected), false if not ours. */
    public boolean handle(Session session, Envelope envelope) {
        if (envelope == null) {
            return false;
        }
        EventType type = envelope.type();
        if (type != EventType.CHAT_GROUP_SEND && type != EventType.CHAT_PRIVATE_SEND) {
            return false;
        }
        try {
            dispatch(session, envelope);
        } catch (RuntimeException unexpected) {
            router.sendError(session, envelope.requestId(), ErrorCode.INTERNAL_ERROR, "Chat request failed");
        }
        return true;
    }

    private void dispatch(Session session, Envelope envelope) {
        if (!router.isAuthenticated(session)
                || (envelope.sessionId() != null && !envelope.sessionId().equals(session.sessionId()))) {
            router.sendError(session, envelope.requestId(), ErrorCode.NOT_AUTHENTICATED, "Login is required");
            return;
        }
        String text = textField(envelope, "text");
        if (envelope.type() == EventType.CHAT_GROUP_SEND) {
            if (text == null) {
                invalid(session, envelope);
                return;
            }
            router.sendGroup(session, text, envelope.requestId());
            return;
        }
        String toUser = textField(envelope, "toUser");
        if (text == null || toUser == null) {
            invalid(session, envelope);
            return;
        }
        router.sendPrivate(session, toUser, text, envelope.requestId());
    }

    private void invalid(Session session, Envelope envelope) {
        router.sendError(session, envelope.requestId(), ErrorCode.INVALID_REQUEST, "Malformed chat request");
    }

    private static String textField(Envelope envelope, String name) {
        JsonNode node = envelope.payload().get(name);
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
