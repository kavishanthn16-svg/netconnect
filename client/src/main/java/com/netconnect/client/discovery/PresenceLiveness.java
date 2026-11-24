package com.netconnect.client.discovery;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe UDP liveness independent of TLS roster membership.
 * Identity is pinned on the first accepted hint, or supplied by trusted integration.
 * Call reset only when establishing a new authenticated connection, including restarts.
 * UDP identity pinning alone is not authentication; source validation belongs to the receiver.
 */
public final class PresenceLiveness {
    public enum State { WAITING, FRESH, EXPIRED }

    private static final long MAX_AGE_MILLIS = 15_000;
    private static final long FUTURE_SKEW_MILLIS = 5_000;
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(15).toNanos();
    private final Clock wallClock;
    private final LongSupplier nanoTime;
    private String serverId;
    private long revision = -1;
    private long timestamp = -1;
    private long lastAcceptedNanos;
    private boolean received;

    public PresenceLiveness() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    public PresenceLiveness(Clock wallClock, LongSupplier nanoTime) {
        this.wallClock = Objects.requireNonNull(wallClock);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    /** Rejected packets never refresh the timeout or change the pinned identity. */
    public synchronized boolean accept(byte[] packet, int offset, int length) {
        var parsed = PresenceDatagramDecoder.decode(packet, offset, length);
        if (parsed.isEmpty()) {
            return false;
        }
        PresenceHint hint = parsed.get();
        long now = wallClock.millis();
        // Both timestamps are nonnegative, so these directional subtractions cannot overflow.
        if (now < 0 || (hint.unixMillis() <= now && now - hint.unixMillis() > MAX_AGE_MILLIS)
                || (hint.unixMillis() > now && hint.unixMillis() - now > FUTURE_SKEW_MILLIS)
                || (serverId != null && !serverId.equals(hint.serverId()))
                || hint.rosterRevision() < revision || hint.unixMillis() <= timestamp) {
            return false;
        }
        serverId = hint.serverId();
        revision = hint.rosterRevision();
        timestamp = hint.unixMillis();
        lastAcceptedNanos = nanoTime.getAsLong();
        received = true;
        return true;
    }

    public synchronized State state() {
        if (!received) {
            return State.WAITING;
        }
        return nanoTime.getAsLong() - lastAcceptedNanos >= TIMEOUT_NANOS
                ? State.EXPIRED : State.FRESH;
    }

    /** Null pins the first accepted hint; a non-null ID must come from trusted integration. */
    public synchronized void reset(String expectedServerId) {
        if (expectedServerId != null && (expectedServerId.isBlank()
                || expectedServerId.chars().anyMatch(c -> c == '|' || Character.isWhitespace(c)
                        || Character.isISOControl(c)))) {
            throw new IllegalArgumentException("Invalid server identity");
        }
        serverId = expectedServerId;
        revision = -1;
        timestamp = -1;
        received = false;
    }
}
