package com.netconnect.server.discovery;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceDatagramEncoderTest {
    @Test void preservesExactFormatAndUtf8() {
        assertEquals("PRESENCE_V1|serveur-é|42|123456",
                new String(PresenceDatagramEncoder.encode("serveur-é", 42, 123456),
                        StandardCharsets.UTF_8));
    }

    @Test void rejectsInvalidFieldsAndOversizedPackets() {
        for (String id : new String[]{"", " ", "a|b", "a\nb", "a b", "x".repeat(512)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PresenceDatagramEncoder.encode(id, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> PresenceDatagramEncoder.encode(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PresenceDatagramEncoder.encode("s", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> PresenceDatagramEncoder.encode("s", 0, -1));
    }
}
