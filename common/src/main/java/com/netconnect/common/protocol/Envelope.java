package com.netconnect.common.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.UUID;

/** Immutable JSON control-plane envelope defined by SRS section 8.1. */
public record Envelope(int v, EventType type, UUID requestId, String sessionId, ObjectNode payload) {
    public static final int VERSION = 1;
    public static final int MAX_UTF8_BYTES = 16 * 1024;

    public Envelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payload, "payload");
    }

    public static Envelope request(EventType type, UUID requestId, String sessionId, ObjectNode payload) {
        return new Envelope(VERSION, type, requestId, sessionId, payload);
    }
}
