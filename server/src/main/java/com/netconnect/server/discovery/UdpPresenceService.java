package com.netconnect.server.discovery;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.PresenceService;
import com.netconnect.common.contracts.Session;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UDP hints only. Security must register endpoints only after authentication and
 * resolve addresses from the authenticated transport, never a client-supplied IP.
 * Lifecycle integration must unregister each disconnected session.
 * start/stop are idempotent; stop is terminal and releases all owned resources.
 */
public final class UdpPresenceService implements PresenceService, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(UdpPresenceService.class.getName());
    private final int port;
    private final String serverId;
    private final Function<Session, InetAddress> remoteAddressOf;
    private final LongSupplier rosterRevision;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final SocketFactory socketFactory;
    private final Map<String, InetSocketAddress> endpoints = new HashMap<>();
    private DatagramSocket socket;
    private boolean stopped;

    public UdpPresenceService(NetConnectConfig config, String serverId,
                              Function<Session, InetAddress> remoteAddressOf,
                              LongSupplier rosterRevision) {
        this(config, serverId, remoteAddressOf, rosterRevision, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "netconnect-presence");
                    thread.setDaemon(true);
                    return thread;
                }), DatagramSocket::new);
    }

    /** Package-local injection for deterministic tests; the service owns both resources. */
    UdpPresenceService(NetConnectConfig config, String serverId,
                       Function<Session, InetAddress> remoteAddressOf,
                       LongSupplier rosterRevision, Clock clock,
                       ScheduledExecutorService scheduler, SocketFactory socketFactory) {
        this.port = Objects.requireNonNull(config).udpPort();
        PresenceDatagramEncoder.encode(serverId, 0, Long.MAX_VALUE);
        this.serverId = serverId;
        this.remoteAddressOf = Objects.requireNonNull(remoteAddressOf);
        this.rosterRevision = Objects.requireNonNull(rosterRevision);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.socketFactory = Objects.requireNonNull(socketFactory);
    }

    @Override
    public synchronized void start() {
        requireOpen();
        if (socket != null) {
            return;
        }
        try {
            socket = socketFactory.bind(port);
        } catch (SocketException failure) {
            throw new IllegalStateException("Cannot bind presence UDP port " + port, failure);
        }
        try {
            scheduler.scheduleAtFixedRate(this::sendHints, 5, 5, TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
            stop();
            throw new IllegalStateException("Cannot schedule presence hints", failure);
        }
    }

    @Override
    public synchronized void registerEndpoint(Session session, int udpPort) {
        requireOpen();
        Objects.requireNonNull(session, "session");
        if (session.sessionId() == null || session.sessionId().isBlank()
                || udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("Valid session ID and UDP receive port required");
        }
        InetAddress address = Objects.requireNonNull(remoteAddressOf.apply(session),
                "Authenticated remote IP unavailable");
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("Authenticated remote IP must be unicast");
        }
        endpoints.put(session.sessionId(), new InetSocketAddress(address, udpPort));
    }

    /** Called by the session lifecycle owner on graceful or abrupt disconnect. */
    public synchronized void unregisterEndpoint(String sessionId) {
        endpoints.remove(sessionId);
    }

    private synchronized void sendHints() {
        if (stopped || socket == null) {
            return;
        }
        final byte[] bytes;
        try {
            bytes = PresenceDatagramEncoder.encode(serverId, rosterRevision.getAsLong(), clock.millis());
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Cannot construct presence hint", failure);
            return; // A failed tick must not cancel later scheduled ticks.
        }
        for (InetSocketAddress endpoint : endpoints.values()) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, endpoint));
            } catch (IOException | RuntimeException failure) {
                LOGGER.log(Level.WARNING, "Presence datagram send failed", failure);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        endpoints.clear();
        scheduler.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    private void requireOpen() {
        if (stopped) {
            throw new IllegalStateException("Presence service has been stopped");
        }
    }

    @FunctionalInterface
    interface SocketFactory {
        DatagramSocket bind(int port) throws SocketException;
    }
}
