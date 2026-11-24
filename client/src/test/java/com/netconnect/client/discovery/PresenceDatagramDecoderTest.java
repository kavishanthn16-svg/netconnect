package com.netconnect.client.discovery;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceDatagramDecoderTest {
    @Test void decodesOnlyTheSpecifiedSlice() {
        byte[] bytes = "xxPRESENCE_V1|serveur-é|42|123yy".getBytes(StandardCharsets.UTF_8);
        assertEquals(new PresenceHint("serveur-é", 42, 123),
                PresenceDatagramDecoder.decode(bytes, 2, bytes.length - 4).orElseThrow());
    }

    @Test void rejectsMalformedAndInvalidNumbers() {
        for (String text : new String[]{"", "PRESENCE_V2|s|1|2", "PRESENCE_V1|s|1",
                "PRESENCE_V1|s|1|2|extra", "PRESENCE_V1||1|2", "PRESENCE_V1|a b|1|2",
                "PRESENCE_V1|s|-1|2", "PRESENCE_V1|s|+1|2", "PRESENCE_V1|s|1|-2",
                "PRESENCE_V1|s|1|2\n", "PRESENCE_V1|s|1.0|2", "PRESENCE_V1|s|x|2",
                "PRESENCE_V1|s|9223372036854775808|2", "PRESENCE_V1|s|1|9223372036854775808"}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            assertTrue(PresenceDatagramDecoder.decode(bytes, 0, bytes.length).isEmpty(), text);
        }
    }

    @Test void rejectsInvalidUtf8BoundsAndOversize() {
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        assertTrue(PresenceDatagramDecoder.decode(malformed, 0, 2).isEmpty());
        assertTrue(PresenceDatagramDecoder.decode(null, 0, 1).isEmpty());
        assertTrue(PresenceDatagramDecoder.decode(new byte[513], 0, 513).isEmpty());
        assertTrue(PresenceDatagramDecoder.decode(new byte[10], -1, 1).isEmpty());
        assertTrue(PresenceDatagramDecoder.decode(new byte[10], 9, 2).isEmpty());
        assertTrue(PresenceDatagramDecoder.decode(new byte[10], Integer.MAX_VALUE, 1).isEmpty());
    }
}
