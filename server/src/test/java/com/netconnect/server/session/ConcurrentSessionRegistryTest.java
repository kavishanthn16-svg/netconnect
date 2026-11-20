package com.netconnect.server.session;

import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentSessionRegistryTest {
    @Test
    @Timeout(5)
    void registersFiftyDifferentUsersConcurrently() throws Exception {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SessionResult>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String name = "User" + String.format("%02d", i);
                results.add(callers.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    return registry.register(name, new TestSession("session-" + name, name));
                }));
            }
            start.countDown();
            for (Future<SessionResult> result : results) {
                assertTrue(result.get(2, TimeUnit.SECONDS).registered());
            }
            assertEquals(50, registry.size());
            assertEquals(50, registry.listUsers().size());
            assertEquals(50, registry.rosterRevision());
        } finally {
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void rejectsDuplicateNamesCaseInsensitively() throws Exception {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SessionResult>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String name = i % 2 == 0 ? "Alice" : "ALICE";
                results.add(callers.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    return registry.register(name, new TestSession("id-" + name + System.nanoTime(), name));
                }));
            }
            start.countDown();
            long accepted = 0;
            for (Future<SessionResult> result : results) {
                if (result.get(2, TimeUnit.SECONDS).registered()) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(1, registry.size());
            assertNotNull(registry.getByDisplayName("aLiCe").orElse(null));
        } finally {
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void concurrentRemovalIsIdempotentAndLeavesNoGhosts() throws Exception {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Session session = new TestSession("id-" + i, "User" + i);
            sessions.add(session);
            assertTrue(registry.register(session.displayName(), session).registered());
        }

        ExecutorService removers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> operations = new ArrayList<>();
            for (Session session : sessions) {
                operations.add(removers.submit(() -> {
                    registry.remove(session.sessionId());
                    registry.remove(session.sessionId());
                }));
            }
            for (Future<?> operation : operations) {
                operation.get(2, TimeUnit.SECONDS);
            }
            assertEquals(0, registry.size());
            assertTrue(registry.listUsers().isEmpty());
            assertEquals(100, registry.rosterRevision());
        } finally {
            removers.shutdownNow();
            removers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void staleDisconnectCannotRemoveReplacementSession() {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        TestSession first = new TestSession("same-id", "Alice");
        TestSession replacement = new TestSession("same-id", "Alice");
        assertTrue(registry.register("Alice", first).registered());
        registry.remove(first.sessionId());
        assertTrue(registry.register("Alice", replacement).registered());

        assertFalse(registry.removeIfSame(first.sessionId(), first));
        assertEquals(replacement, registry.getByDisplayName("alice").orElseThrow());
    }

    @Test
    @Timeout(5)
    void revisionsAndListenersTrackAuthoritativeRosterChanges() {
        ConcurrentSessionRegistry registry = new ConcurrentSessionRegistry();
        AtomicLong observedRevision = new AtomicLong();
        AtomicInteger notifications = new AtomicInteger();
        registry.addRosterChangeListener((revision, users) -> {
            assertTrue(revision > observedRevision.get());
            observedRevision.set(revision);
            notifications.incrementAndGet();
            assertThrowsOnMutation(users);
        });

        TestSession session = new TestSession("one", "Alice");
        registry.register("Alice", session);
        registry.remove("one");

        assertEquals(2, notifications.get());
        assertEquals(2, registry.rosterRevision());
        assertEquals(2, observedRevision.get());
    }

    private static void assertThrowsOnMutation(List<String> users) {
        try {
            users.add("should-fail");
            throw new AssertionError("Roster listener received a mutable list");
        } catch (UnsupportedOperationException expected) {
            // Immutable snapshot is required.
        }
    }

    private record TestSession(String sessionId, String displayName) implements Session {
    }
}
