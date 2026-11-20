package com.netconnect.server.session;

import java.util.List;

public record RosterSnapshot(long revision, List<String> users) {
    public RosterSnapshot {
        users = List.copyOf(users);
    }
}
