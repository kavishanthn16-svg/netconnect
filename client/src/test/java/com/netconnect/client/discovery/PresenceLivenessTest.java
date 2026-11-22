package com.netconnect.client.discovery;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceLivenessTest {
    private final AtomicLong millis = new AtomicLong(100_000);
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    };
    private final PresenceLiveness model = new PresenceLiveness(clock, nanos::get);

    private boolean accept(String id, long revision, long timestamp) {
        byte[] bytes = ("PRESENCE_V1|" + id + "|" + revision + "|" + timestamp)
                .getBytes(StandardCharsets.UTF_8);
        return model.accept(bytes, 0, bytes.length);
    }

    @Test void waitsThenExpiresAtExactlyFifteenSecondsAndRestores() {
        assertEquals(PresenceLiveness.State.WAITING, model.state());
        assertTrue(accept("s", 1, 100_000));
        nanos.set(14_999_999_999L);
        assertEquals(PresenceLiveness.State.FRESH, model.state());
        nanos.incrementAndGet();
        assertEquals(PresenceLiveness.State.EXPIRED, model.state());
        millis.set(115_000);
        assertTrue(accept("s", 1, 115_000));
        assertEquals(PresenceLiveness.State.FRESH, model.state());
    }

    @Test void enforcesWallTimeAgeAndFutureSkewBoundaries() {
        assertFalse(accept("s", 0, 84_999));
        assertFalse(accept("s", 0, 105_001));
        assertFalse(accept("s", 0, Long.MAX_VALUE));
        assertEquals(PresenceLiveness.State.WAITING, model.state());
        assertTrue(accept("s", 0, 85_000));
        assertTrue(accept("s", 0, 105_000));
    }

    @Test void rejectsReplaysAndDecreasingRevisionsWithoutRefreshingExpiry() {
        assertTrue(accept("s", 5, 100_000));
        nanos.set(14_000_000_000L);
        millis.set(114_000);
        assertFalse(accept("s", 5, 100_000));
        assertFalse(accept("s", 6, 99_999));
        assertFalse(accept("s", 4, 114_000));
        assertFalse(model.accept(new byte[]{0}, 0, 1));
        nanos.set(15_000_000_000L);
        assertEquals(PresenceLiveness.State.EXPIRED, model.state());
        assertTrue(accept("s", 5, 114_000));
        assertTrue(accept("s", 6, 114_001));
    }

    @Test void pinsIdentityAndResetsOnlyForNewAuthenticatedConnection() {
        assertTrue(accept("first", 50, 100_000));
        assertFalse(accept("other", 51, 100_001));
        model.reset("restart");
        assertEquals(PresenceLiveness.State.WAITING, model.state());
        assertFalse(accept("first", 51, 100_001));
        assertTrue(accept("restart", 0, 100_001));
        model.reset(null);
        assertTrue(accept("new", 0, 100_000));
    }

    @Test void wallClockChangesDoNotAffectOngoingMonotonicExpiry() {
        assertTrue(accept("s", 1, 100_000));
        millis.set(0);
        nanos.set(14_000_000_000L);
        assertEquals(PresenceLiveness.State.FRESH, model.state());
        millis.set(Long.MAX_VALUE);
        nanos.set(15_000_000_000L);
        assertEquals(PresenceLiveness.State.EXPIRED, model.state());
    }
}
