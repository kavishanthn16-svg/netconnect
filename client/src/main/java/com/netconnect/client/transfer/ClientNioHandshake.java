package com.netconnect.client.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Encodes the 4-byte length-prefixed UTF-8 JSON handshake for the client NIO file channel (SRS Section 8.5).
 */
public final class ClientNioHandshake {

    public static final int VERSION = 1;
    public static final int MAX_HANDSHAKE_BYTES = 2048;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientNioHandshake() {
    }

    public static byte[] encode(UUID transferId, String ticket, String role) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(role, "role");

        if (!"SEND".equals(role) && !"RECEIVE".equals(role)) {
            throw new IllegalArgumentException("Role must be SEND or RECEIVE: " + role);
        }

        Map<String, Object> payload = Map.of(
                "v", VERSION,
                "transferId", transferId.toString(),
                "ticket", ticket,
                "role", role
        );

        try {
            byte[] jsonBytes = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            if (jsonBytes.length > MAX_HANDSHAKE_BYTES) {
                throw new IllegalStateException("Handshake JSON exceeds " + MAX_HANDSHAKE_BYTES + " bytes");
            }
            ByteBuffer buffer = ByteBuffer.allocate(4 + jsonBytes.length);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            return buffer.array();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize client handshake", e);
        }
    }
}
