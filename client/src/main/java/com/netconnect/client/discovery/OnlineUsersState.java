package com.netconnect.client.discovery;

import java.util.List;
import java.util.Objects;

/** Immutable presentation state. Only authoritative TLS updates can replace users. */
public record OnlineUsersState(long revision, List<String> users, PresenceLiveness.State liveness) {
    public OnlineUsersState {
        users = List.copyOf(users);
        Objects.requireNonNull(liveness);
    }

    public static OnlineUsersState initial() {
        return new OnlineUsersState(-1, List.of(), PresenceLiveness.State.WAITING);
    }

    public OnlineUsersState withRoster(long nextRevision, List<String> nextUsers) {
        if (nextRevision < 0) {
            throw new IllegalArgumentException("Roster revision must be nonnegative");
        }
        return nextRevision <= revision ? this : new OnlineUsersState(nextRevision, nextUsers, liveness);
    }

    public OnlineUsersState withLiveness(PresenceLiveness.State next) {
        return new OnlineUsersState(revision, users, next);
    }

    public String livenessText() {
        return switch (liveness) {
            case WAITING -> "Server UDP hints: waiting for a hint";
            case FRESH -> "Server UDP hints: received recently";
            case EXPIRED -> "Server UDP hints: missing for 15 seconds or more";
        };
    }
}
