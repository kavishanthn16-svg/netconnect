package com.netconnect.server.session;

import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionResult;

import java.time.Duration;

/** Idempotent lifecycle boundary for future TLS/control connection owners. */
public final class SessionLifecycleManager implements AutoCloseable {
    private final ConcurrentSessionRegistry registry;
    private final BoundedSessionExecutor executor;
    private final Duration shutdownTimeout;

    public SessionLifecycleManager(ConcurrentSessionRegistry registry, BoundedSessionExecutor executor,
                                   Duration shutdownTimeout) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.shutdownTimeout = java.util.Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }

    public SessionResult connected(String displayName, Session session) {
        return registry.register(displayName, session);
    }

    public void disconnected(String sessionId) {
        registry.remove(sessionId);
    }

    public boolean disconnected(String sessionId, Session expected) {
        return registry.removeIfSame(sessionId, expected);
    }

    public BoundedSessionExecutor executor() {
        return executor;
    }

    @Override
    public void close() throws InterruptedException {
        registry.removeIf(session -> true);
        executor.shutdownGracefully(shutdownTimeout);
    }
}
