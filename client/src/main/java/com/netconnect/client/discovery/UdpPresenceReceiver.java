package com.netconnect.client.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One receiver per login attempt. Construction binds an ephemeral port before login.
 * The login adapter advertises localUdpPort(), then calls onAuthenticated with the
 * actual TLS peer IP and configured server UDP source port (default 5051).
 * State callbacks run on the worker; JavaFX consumers must marshal UI updates.
 * Source matching and first-hint identity pinning do not authenticate UDP.
 */
public final class UdpPresenceReceiver implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(UdpPresenceReceiver.class.getName());
    private final DatagramSocket socket;
    private final PresenceLiveness liveness;
    private final Consumer<PresenceLiveness.State> listener;
    private InetSocketAddress expectedSource;
    private PresenceLiveness.State published;
    private Thread worker;
    private volatile boolean closed;

    public UdpPresenceReceiver(Consumer<PresenceLiveness.State> listener) throws SocketException {
        this(new PresenceLiveness(), listener);
    }

    public UdpPresenceReceiver(PresenceLiveness liveness,
                               Consumer<PresenceLiveness.State> listener) throws SocketException {
        this.liveness = Objects.requireNonNull(liveness);
        this.listener = Objects.requireNonNull(listener);
        DatagramSocket bound = new DatagramSocket(null);
        try {
            bound.bind(new InetSocketAddress(0));
            bound.setSoTimeout(100);
        } catch (SocketException failure) {
            bound.close();
            throw failure;
        }
        socket = bound;
    }

    public synchronized int localUdpPort() {
        requireOpen();
        return socket.getLocalPort();
    }

    /** Null serverId pins the first valid hint; otherwise identity comes from trusted integration. */
    public synchronized void onAuthenticated(InetSocketAddress source, String serverId) {
        requireOpen();
        Objects.requireNonNull(source);
        if (source.isUnresolved() || source.getPort() < 1
                || source.getAddress().isAnyLocalAddress() || source.getAddress().isMulticastAddress()) {
            throw new IllegalArgumentException("Resolved unicast server IP and UDP port required");
        }
        if (worker != null) {
            throw new IllegalStateException("Receiver already authenticated; create a new receiver for reconnect");
        }
        liveness.reset(serverId);
        expectedSource = source;
        worker = new Thread(this::receiveLoop, "netconnect-presence-receiver");
        worker.setDaemon(true);
        worker.start();
    }

    public void onAuthenticated(InetSocketAddress source) {
        onAuthenticated(source, null);
    }

    public synchronized PresenceLiveness.State state() {
        return liveness.state();
    }

    private void receiveLoop() {
        // One extra byte detects oversized datagrams instead of accepting a truncated prefix.
        byte[] buffer = new byte[PresenceDatagramDecoder.MAX_PACKET_BYTES + 1];
        try {
            publishState();
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    synchronized (this) {
                        if (!closed && expectedSource.equals(packet.getSocketAddress())) {
                            liveness.accept(packet.getData(), packet.getOffset(), packet.getLength());
                        }
                    }
                } catch (SocketTimeoutException noPacket) {
                    // Still evaluate expiry when UDP traffic is missing.
                }
                publishState();
            }
        } catch (IOException failure) {
            if (!closed) {
                LOGGER.log(Level.WARNING, "Presence UDP receive failed", failure);
            }
        } finally {
            close();
        }
    }

    private void publishState() {
        PresenceLiveness.State next;
        synchronized (this) {
            if (closed || (next = liveness.state()) == published) {
                return;
            }
            published = next;
        }
        try {
            listener.accept(next);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Presence state listener failed", failure);
        }
    }

    public void onLoginFailed() { close(); }

    public void onDisconnected() { close(); }

    /** Nonblocking and idempotent; closing the socket unblocks background receive. */
    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            socket.close();
            liveness.reset(null);
        }
    }

    // For deterministic shutdown verification, never called by a UI integration.
    boolean awaitTermination(long millis) throws InterruptedException {
        Thread current;
        synchronized (this) { current = worker; }
        if (current == null) { return true; }
        current.join(millis);
        return !current.isAlive();
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Presence receiver is closed"); }
    }
}
