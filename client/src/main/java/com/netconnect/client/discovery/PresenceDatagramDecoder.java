package com.netconnect.client.discovery;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Strict, bounded UTF-8 parsing. Timestamp freshness belongs to the liveness model. */
public final class PresenceDatagramDecoder {
    public static final int MAX_PACKET_BYTES = 512;

    private PresenceDatagramDecoder() { }

    public static Optional<PresenceHint> decode(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length <= 0 || length > MAX_PACKET_BYTES
                || offset > bytes.length - length) {
            return Optional.empty();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
            String[] fields = text.split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("PRESENCE_V1")
                    || fields[1].isBlank()
                    || fields[1].chars().anyMatch(c -> Character.isWhitespace(c)
                            || Character.isISOControl(c))
                    || !fields[2].matches("[0-9]+") || !fields[3].matches("[0-9]+")) {
                return Optional.empty();
            }
            return Optional.of(new PresenceHint(fields[1], Long.parseLong(fields[2]),
                    Long.parseLong(fields[3])));
        } catch (CharacterCodingException | NumberFormatException invalid) {
            return Optional.empty();
        }
    }
}
