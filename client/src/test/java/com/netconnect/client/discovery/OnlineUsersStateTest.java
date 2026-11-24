package com.netconnect.client.discovery;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineUsersStateTest {
    @Test void timeoutAndRecoveryPreserveAuthoritativeRoster() {
        var initial = OnlineUsersState.initial();
        assertEquals(-1, initial.revision());
        var roster = initial.withRoster(3, List.of("Alice", "Bobby"));
        var expired = roster.withLiveness(PresenceLiveness.State.EXPIRED);
        assertEquals(roster.users(), expired.users());
        assertEquals(3, expired.revision());
        assertEquals("Server UDP hints: missing for 15 seconds or more", expired.livenessText());
        var restored = expired.withLiveness(PresenceLiveness.State.FRESH);
        assertEquals(roster.users(), restored.users());
        assertEquals("Server UDP hints: received recently", restored.livenessText());
    }

    @Test void snapshotsRosterAndRejectsStaleRevisions() {
        var names = new ArrayList<>(List.of("Alice"));
        var state = OnlineUsersState.initial().withRoster(4, names);
        names.clear();
        assertEquals(List.of("Alice"), state.users());
        assertSame(state, state.withRoster(3, List.of()));
        assertSame(state, state.withRoster(4, List.of()));
        assertEquals(List.of(), state.withRoster(5, List.of()).users());
        assertThrows(UnsupportedOperationException.class, () -> state.users().clear());
        assertThrows(IllegalArgumentException.class, () -> state.withRoster(-1, List.of()));
    }
}
