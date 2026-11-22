package com.netconnect.server.transfer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Handshake model and codec for the NIO file data channel (SRS Section 8.5).
 * Framed with 4-byte big-endian header length (max 2048 bytes) followed by UTF-8 JSON.
 */
public record NioHandshake(
        @JsonProperty("v") int v,
        @JsonProperty("transferId") UUID transferId,
        @JsonProperty("ticket") String ticket,
        @JsonProperty("role") String role
) {
    public static final int VERSION = 1;
    public static final int MAX_HANDSHAKE_BYTES = 2048;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public NioHandshake {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(role, "role");
        if (v != VERSION) {
            throw new IllegalArgumentException("Unsupported handshake version: " + v);
        }
        if (!"SEND".equals(role) && !"RECEIVE".equals(role)) {
            throw new IllegalArgumentException("Handshake role must be SEND or RECEIVE: " + role);
        }
    }

    public static NioHandshake of(UUID transferId, String ticket, String role) {
        return new NioHandshake(VERSION, transferId, ticket, role);
    }

    public byte[] encode() {
        try {
            byte[] jsonBytes = MAPPER.writeValueAsString(this).getBytes(StandardCharsets.UTF_8);
            if (jsonBytes.length > MAX_HANDSHAKE_BYTES) {
                throw new IllegalStateException("Handshake payload exceeds maximum " + MAX_HANDSHAKE_BYTES + " bytes");
            }
            ByteBuffer buffer = ByteBuffer.allocate(4 + jsonBytes.length);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            return buffer.array();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize handshake", e);
        }
    }

    public static NioHandshake decode(byte[] jsonBytes) {
        Objects.requireNonNull(jsonBytes, "jsonBytes");
        if (jsonBytes.length > MAX_HANDSHAKE_BYTES) {
            throw new IllegalArgumentException("Handshake length exceeds 2048 bytes");
        }
        try {
            NioHandshake handshake = MAPPER.readValue(jsonBytes, NioHandshake.class);
            if (handshake.v() != VERSION || handshake.transferId() == null
                    || handshake.ticket() == null || handshake.role() == null) {
                throw new IllegalArgumentException("Invalid handshake payload");
            }
            return handshake;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode handshake JSON", e);
        }
    }
}
