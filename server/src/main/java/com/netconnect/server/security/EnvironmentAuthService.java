package com.netconnect.server.security;

import com.netconnect.common.contracts.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Credentials are supplied at runtime; registration atomically reserves the name. */
public final class EnvironmentAuthService implements AuthService {
    private final SessionRegistry registry;
    private final Supplier<String> accessCode;

    public EnvironmentAuthService(SessionRegistry registry) {
        this(registry, () -> System.getenv("NETCONNECT_ACCESS_CODE"));
    }

    EnvironmentAuthService(SessionRegistry registry, Supplier<String> accessCode) {
        this.registry = Objects.requireNonNull(registry);
        this.accessCode = Objects.requireNonNull(accessCode);
    }

    @Override
    public AuthResult authenticate(String displayName, String suppliedCode) {
        if (displayName == null || displayName.isBlank() || displayName.length() < 3
                || displayName.length() > 24 || displayName.codePoints().anyMatch(Character::isISOControl))
            return AuthResult.denied("INVALID_REQUEST");
        String expected = accessCode.get();
        if (expected == null || expected.isBlank() || suppliedCode == null || suppliedCode.isBlank()
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                suppliedCode.getBytes(StandardCharsets.UTF_8))) return AuthResult.denied("ACCESS_DENIED");
        Session identity = new Identity(UUID.randomUUID().toString(), displayName);
        SessionResult result = registry.register(displayName, identity);
        return result.registered() ? AuthResult.accepted(identity.sessionId(), displayName)
                : AuthResult.denied(result.code());
    }

    private record Identity(String sessionId, String displayName) implements Session { }
}
