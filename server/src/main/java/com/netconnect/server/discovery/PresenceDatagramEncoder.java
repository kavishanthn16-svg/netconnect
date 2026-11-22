package com.netconnect.server.discovery;

import java.nio.charset.StandardCharsets;

/** Encodes the frozen presence wire format without owning a socket. */
public final class PresenceDatagramEncoder {
    public static final int MAX_PACKET_BYTES = 512;

    private PresenceDatagramEncoder() { }

    public static byte[] encode(String serverId, long rosterRevision, long unixMillis) {
        if (serverId == null || serverId.isBlank()
                || serverId.chars().anyMatch(c -> c == '|' || Character.isWhitespace(c)
                        || Character.isISOControl(c))
                || rosterRevision < 0 || unixMillis < 0) {
            throw new IllegalArgumentException("Invalid presence fields");
        }
        byte[] packet = ("PRESENCE_V1|" + serverId + "|" + rosterRevision + "|" + unixMillis)
                .getBytes(StandardCharsets.UTF_8);
        if (packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Presence packet exceeds 512 bytes");
        }
        return packet;
    }
}
