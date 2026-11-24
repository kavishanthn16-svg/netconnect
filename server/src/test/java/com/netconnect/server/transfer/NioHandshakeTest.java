package com.netconnect.server.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NioHandshakeTest {

    @Test
    @DisplayName("Handshake encodes and decodes accurately")
    void encodeAndDecode() {
        UUID transferId = UUID.randomUUID();
        String ticket = "secret-ticket-token";
        NioHandshake handshake = NioHandshake.of(transferId, ticket, "SEND");

        byte[] framedBytes = handshake.encode();

        // 4-byte header
        ByteBuffer buffer = ByteBuffer.wrap(framedBytes);
        int length = buffer.getInt();
        assertEquals(framedBytes.length - 4, length);

        byte[] jsonBytes = new byte[length];
        buffer.get(jsonBytes);

        NioHandshake decoded = NioHandshake.decode(jsonBytes);
        assertEquals(1, decoded.v());
        assertEquals(transferId, decoded.transferId());
        assertEquals(ticket, decoded.ticket());
        assertEquals("SEND", decoded.role());
    }

    @Test
    @DisplayName("Invalid role or version throws IllegalArgumentException")
    void invalidParameters() {
        UUID transferId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                new NioHandshake(2, transferId, "ticket", "SEND"));
        assertThrows(IllegalArgumentException.class, () ->
                new NioHandshake(1, transferId, "ticket", "INVALID_ROLE"));
    }
}
