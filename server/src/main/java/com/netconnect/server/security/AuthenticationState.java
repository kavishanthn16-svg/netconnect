package com.netconnect.server.security;

import com.netconnect.common.contracts.SessionRegistry;
import com.netconnect.common.protocol.Envelope;
import java.util.Objects;

/** Connection-bound authorization, never trusting a client-supplied session ID alone. */
public final class AuthenticationState {
    public static boolean permits(String connectionId, String connectionName, Envelope request, SessionRegistry registry) {
        return connectionId != null && !connectionId.isBlank() && connectionName != null
                && request != null && registry != null && Objects.equals(connectionId, request.sessionId())
                && registry.getByDisplayName(connectionName)
                .filter(active -> Objects.equals(active.sessionId(), connectionId)).isPresent();
    }

    private AuthenticationState() { }
}
