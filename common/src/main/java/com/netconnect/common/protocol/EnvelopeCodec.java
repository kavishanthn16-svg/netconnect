package com.netconnect.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/** Newline-delimited UTF-8 JSON codec with the SRS size and shape checks. */
public final class EnvelopeCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EnvelopeCodec() {
    }

    public static String encode(Envelope envelope) {
        validate(envelope);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Envelope cannot be encoded", exception);
        }
    }

    public static Envelope decode(String line) {
        if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A control message must be one JSON line");
        }
        if (line.getBytes(StandardCharsets.UTF_8).length > Envelope.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Control message exceeds 16 KiB");
        }
        try {
            Envelope envelope = MAPPER.readValue(line, Envelope.class);
            validate(envelope);
            return envelope;
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid control envelope", exception);
        }
    }

    public static void validate(Envelope envelope) {
        if (envelope == null || envelope.v() != Envelope.VERSION || envelope.type() == null
                || envelope.requestId() == null || envelope.payload() == null) {
            throw new IllegalArgumentException("Envelope does not satisfy protocol version 1");
        }
        String encoded;
        try {
            encoded = MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Envelope cannot be validated", exception);
        }
        if (encoded.getBytes(StandardCharsets.UTF_8).length > Envelope.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Control message exceeds 16 KiB");
        }
    }
}
