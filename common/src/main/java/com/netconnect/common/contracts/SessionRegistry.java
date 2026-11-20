package com.netconnect.common.contracts;

import java.util.List;
import java.util.Optional;

public interface SessionRegistry {
    SessionResult register(String displayName, Session connection);

    Optional<Session> getByDisplayName(String name);

    List<String> listUsers();

    void remove(String sessionId);
}
