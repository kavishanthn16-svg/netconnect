package com.netconnect.server.session;

/** Bounds all server session work submitted to the foundation executor. */
public record SessionExecutorConfig(int coreThreads, int maximumThreads, int queueCapacity,
                                    String threadNamePrefix) {
    public SessionExecutorConfig {
        if (coreThreads < 1 || maximumThreads < coreThreads || queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid session executor bounds");
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
    }

    public static SessionExecutorConfig defaults() {
        int processors = Runtime.getRuntime().availableProcessors();
        int maximum = Math.max(2, Math.min(processors, 16));
        return new SessionExecutorConfig(Math.min(2, maximum), maximum, 128, "netconnect-session-");
    }
}
