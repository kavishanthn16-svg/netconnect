package com.netconnect.server.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedSessionExecutorTest {
    @Test
    @Timeout(5)
    void rejectsWhenWorkerAndQueueAreSaturated() throws Exception {
        BoundedSessionExecutor executor = new BoundedSessionExecutor(
                new SessionExecutorConfig(1, 1, 1, "test-session-"));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(executor.tryExecute(() -> {
                running.countDown();
                await(release);
            }));
            assertTrue(running.await(2, TimeUnit.SECONDS));
            assertTrue(executor.tryExecute(() -> {
            }));
            assertFalse(executor.tryExecute(() -> {
            }));
            assertTrue(executor.activeTaskCount() <= 1);
            assertTrue(executor.queuedTaskCount() <= 1);
        } finally {
            release.countDown();
            executor.shutdownGracefully(Duration.ofSeconds(2));
        }
        assertTrue(executor.isTerminated());
    }

    @Test
    @Timeout(5)
    void gracefulShutdownDrainsAcceptedWorkAndRejectsNewWork() throws Exception {
        BoundedSessionExecutor executor = new BoundedSessionExecutor(
                new SessionExecutorConfig(2, 2, 8, "test-session-"));
        CountDownLatch completed = new CountDownLatch(10);
        try {
            for (int i = 0; i < 10; i++) {
                assertTrue(executor.tryExecute(completed::countDown));
            }
            executor.shutdownGracefully(Duration.ofSeconds(2));
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertFalse(executor.tryExecute(() -> {
            }));
            assertTrue(executor.isTerminated());
        } finally {
            executor.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
