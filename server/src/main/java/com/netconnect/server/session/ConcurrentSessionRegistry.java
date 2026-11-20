package com.netconnect.server.session;

import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import com.netconnect.common.contracts.SessionResult;
import com.netconnect.common.protocol.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe authoritative registry of active authenticated sessions.
 *
 * A write lock keeps the session-id and case-insensitive display-name indexes consistent while
 * concurrent readers remain cheap. Roster notifications receive immutable snapshots.
 */
public final class ConcurrentSessionRegistry implements SessionRegistry {
    private static final Logger LOGGER = Logger.getLogger(ConcurrentSessionRegistry.class.getName());
    private final ConcurrentHashMap<String, Session> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idsByNormalizedName = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RosterChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock mutationLock = new ReentrantReadWriteLock();
    private final AtomicLong rosterRevision = new AtomicLong();

    @Override
    public SessionResult register(String displayName, Session connection) {
        if (!validDisplayName(displayName) || connection == null || connection.sessionId() == null
                || connection.sessionId().isBlank()) {
            return SessionResult.rejected(ErrorCode.INVALID_REQUEST.name());
        }
        if (!displayName.equals(connection.displayName())) {
            return SessionResult.rejected(ErrorCode.INVALID_REQUEST.name());
        }

        String normalizedName = normalize(displayName);
        mutationLock.writeLock().lock();
        try {
            if (sessionsById.containsKey(connection.sessionId())
                    || idsByNormalizedName.containsKey(normalizedName)) {
                return SessionResult.rejected(ErrorCode.NAME_TAKEN.name());
            }
            sessionsById.put(connection.sessionId(), connection);
            idsByNormalizedName.put(normalizedName, connection.sessionId());
            notifyRosterChanged();
            return SessionResult.accepted(connection);
        } finally {
            mutationLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Session> getByDisplayName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String id = idsByNormalizedName.get(normalize(name));
        return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
    }

    @Override
    public List<String> listUsers() {
        mutationLock.readLock().lock();
        try {
            return snapshotUsers();
        } finally {
            mutationLock.readLock().unlock();
        }
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId == null) {
            return;
        }
        mutationLock.writeLock().lock();
        try {
            Session removed = sessionsById.remove(sessionId);
            if (removed == null) {
                return;
            }
            idsByNormalizedName.remove(normalize(removed.displayName()), sessionId);
            notifyRosterChanged();
        } finally {
            mutationLock.writeLock().unlock();
        }
    }

    /** Removes a session only if the current registry entry is the expected connection. */
    public boolean removeIfSame(String sessionId, Session expected) {
        if (sessionId == null || expected == null) {
            return false;
        }
        mutationLock.writeLock().lock();
        try {
            if (sessionsById.get(sessionId) != expected) {
                return false;
            }
            sessionsById.remove(sessionId);
            idsByNormalizedName.remove(normalize(expected.displayName()), sessionId);
            notifyRosterChanged();
            return true;
        } finally {
            mutationLock.writeLock().unlock();
        }
    }

    /** Removes entries that no longer satisfy the caller's liveness predicate. */
    public int removeIf(Predicate<Session> stale) {
        if (stale == null) {
            throw new NullPointerException("stale");
        }
        int removedCount = 0;
        mutationLock.writeLock().lock();
        try {
            for (Session session : new ArrayList<>(sessionsById.values())) {
                if (stale.test(session) && sessionsById.remove(session.sessionId(), session)) {
                    idsByNormalizedName.remove(normalize(session.displayName()), session.sessionId());
                    removedCount++;
                }
            }
            if (removedCount > 0) {
                notifyRosterChanged();
            }
            return removedCount;
        } finally {
            mutationLock.writeLock().unlock();
        }
    }

    public long rosterRevision() {
        return rosterRevision.get();
    }

    public RosterSnapshot snapshot() {
        mutationLock.readLock().lock();
        try {
            return new RosterSnapshot(rosterRevision.get(), snapshotUsers());
        } finally {
            mutationLock.readLock().unlock();
        }
    }

    public void addRosterChangeListener(RosterChangeListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeRosterChangeListener(RosterChangeListener listener) {
        listeners.remove(listener);
    }

    public int size() {
        return sessionsById.size();
    }

    private void notifyRosterChanged() {
        long revision = rosterRevision.incrementAndGet();
        List<String> users = snapshotUsers();
        for (RosterChangeListener listener : listeners) {
            try {
                listener.onRosterChanged(revision, users);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(Level.WARNING, "Roster change listener failed", listenerFailure);
            }
        }
    }

    private List<String> snapshotUsers() {
        return sessionsById.values().stream()
                .map(Session::displayName)
                .sorted(Comparator.comparing(name -> name.toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    private static boolean validDisplayName(String name) {
        return name != null && name.length() >= 3 && name.length() <= 24 && !name.isBlank();
    }

    private static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
