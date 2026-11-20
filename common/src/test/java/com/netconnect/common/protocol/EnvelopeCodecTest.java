package com.netconnect.common.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeCodecTest {
    @Test
    void roundTripsTheFrozenEnvelopeShape() {
        UUID requestId = UUID.randomUUID();
        Envelope source = Envelope.request(EventType.AUTH_LOGIN, requestId, null,
                JsonNodeFactory.instance.objectNode().put("displayName", "Alice").put("accessCode", "demo"));

        Envelope decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(source));

        assertEquals(1, decoded.v());
        assertEquals(EventType.AUTH_LOGIN, decoded.type());
        assertEquals(requestId, decoded.requestId());
        assertEquals("Alice", decoded.payload().get("displayName").asText());
    }

    @Test
    void rejectsOversizedControlLines() {
        String line = "x".repeat(Envelope.MAX_UTF8_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCodec.decode(line));
    }
}
