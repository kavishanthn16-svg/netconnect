package com.netconnect.server.session;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool for session-related work.
 *
 * Rejected work is reported to the caller instead of creating unbounded threads or silently dropping it.
 */
public final class BoundedSessionExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    public BoundedSessionExecutor(SessionExecutorConfig config) {
        ThreadFactory threadFactory = namedThreadFactory(config.threadNamePrefix());
        this.executor = new ThreadPoolExecutor(
                config.coreThreads(),
                config.maximumThreads(),
                30L,
                java.util.concurrent.TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean tryExecute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    public <T> Optional<Future<T>> trySubmit(Callable<T> task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        try {
            return Optional.of(executor.submit(task));
        } catch (RejectedExecutionException rejected) {
            return Optional.empty();
        }
    }

    public int activeTaskCount() {
        return executor.getActiveCount();
    }

    public int queuedTaskCount() {
        return executor.getQueue().size();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    public void shutdownGracefully(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        executor.shutdown();
        if (!executor.awaitTermination(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
            executor.shutdownNow();
            executor.awaitTermination(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
