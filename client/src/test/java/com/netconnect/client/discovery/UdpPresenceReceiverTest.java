package com.netconnect.client.discovery;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UdpPresenceReceiverTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private static DatagramSocket sender() throws SocketException {
        return new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
    }

    private static void send(DatagramSocket sender, int port, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sender.send(new DatagramPacket(data, data.length, LOOPBACK, port));
    }

    private static String hint(long revision, long timestamp) {
        return "PRESENCE_V1|server|" + revision + "|" + timestamp;
    }

    private static PresenceLiveness model(AtomicLong nanos) {
        return new PresenceLiveness(Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC),
                nanos::get);
    }

    @Test void twoClientsBindDistinctPortsBeforeLoginAndReceiveOnBackgroundWorkers() throws Exception {
        var firstStates = new LinkedBlockingQueue<PresenceLiveness.State>();
        var secondStates = new LinkedBlockingQueue<PresenceLiveness.State>();
        try (DatagramSocket server = sender();
             var first = new UdpPresenceReceiver(model(new AtomicLong()), firstStates::add);
             var second = new UdpPresenceReceiver(model(new AtomicLong()), secondStates::add)) {
            int firstPort = first.localUdpPort();
            int secondPort = second.localUdpPort();
            assertNotEquals(firstPort, secondPort);
            assertEquals(PresenceLiveness.State.WAITING, first.state());
            var source = new InetSocketAddress(LOOPBACK, server.getLocalPort());
            first.onAuthenticated(source, "server");
            second.onAuthenticated(source, "server");
            assertEquals(PresenceLiveness.State.WAITING, firstStates.poll(2, TimeUnit.SECONDS));
            assertEquals(PresenceLiveness.State.WAITING, secondStates.poll(2, TimeUnit.SECONDS));
            send(server, firstPort, hint(1, 100_000));
            send(server, secondPort, hint(1, 100_000));
            assertEquals(PresenceLiveness.State.FRESH, firstStates.poll(2, TimeUnit.SECONDS));
            assertEquals(PresenceLiveness.State.FRESH, secondStates.poll(2, TimeUnit.SECONDS));
            first.onDisconnected();
            second.close();
            assertTrue(first.awaitTermination(1000));
            assertTrue(second.awaitTermination(1000));
        }
    }

    @Test void packetLossExpiresWithoutTrafficAndResumedHeartbeatRestores() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var states = new LinkedBlockingQueue<PresenceLiveness.State>();
        try (DatagramSocket server = sender();
             var receiver = new UdpPresenceReceiver(model(nanos), states::add)) {
            receiver.onAuthenticated(new InetSocketAddress(LOOPBACK, server.getLocalPort()));
            assertEquals(PresenceLiveness.State.WAITING, states.poll(2, TimeUnit.SECONDS));
            send(server, receiver.localUdpPort(), hint(5, 100_000));
            assertEquals(PresenceLiveness.State.FRESH, states.poll(2, TimeUnit.SECONDS));
            nanos.set(15_000_000_000L);
            assertEquals(PresenceLiveness.State.EXPIRED, states.poll(2, TimeUnit.SECONDS));
            // Replayed, out-of-order, stale, malformed and oversized hints cannot restore freshness.
            send(server, receiver.localUdpPort(), hint(5, 100_000));
            send(server, receiver.localUdpPort(), hint(4, 100_001));
            send(server, receiver.localUdpPort(), hint(6, 84_999));
            send(server, receiver.localUdpPort(), "garbage");
            send(server, receiver.localUdpPort(), "PRESENCE_V1|" + "x".repeat(600) + "|6|100001");
            assertNull(states.poll(250, TimeUnit.MILLISECONDS));
            assertEquals(PresenceLiveness.State.EXPIRED, receiver.state());
            send(server, receiver.localUdpPort(), hint(5, 100_001));
            assertEquals(PresenceLiveness.State.FRESH, states.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test void rejectsWrongSourceAndIdentity() throws Exception {
        var states = new LinkedBlockingQueue<PresenceLiveness.State>();
        try (DatagramSocket server = sender(); DatagramSocket stranger = sender();
             var receiver = new UdpPresenceReceiver(model(new AtomicLong()), states::add)) {
            receiver.onAuthenticated(new InetSocketAddress(LOOPBACK, server.getLocalPort()), "server");
            assertEquals(PresenceLiveness.State.WAITING, states.poll(2, TimeUnit.SECONDS));
            send(stranger, receiver.localUdpPort(), hint(1, 100_000));
            send(server, receiver.localUdpPort(), "PRESENCE_V1|wrong|1|100000");
            assertNull(states.poll(250, TimeUnit.MILLISECONDS));
            assertEquals(PresenceLiveness.State.WAITING, receiver.state());
            send(server, receiver.localUdpPort(), hint(1, 100_000));
            assertEquals(PresenceLiveness.State.FRESH, states.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test void loginFailureReleasesBoundPortAndCloseIsIdempotent() throws Exception {
        var receiver = new UdpPresenceReceiver(state -> { });
        int port = receiver.localUdpPort();
        receiver.onLoginFailed();
        receiver.close();
        assertTrue(receiver.awaitTermination(1000));
        assertThrows(IllegalStateException.class, receiver::localUdpPort);
        try (DatagramSocket rebound = new DatagramSocket(port)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test void validatesSetupAndShutsDownBlockedWorker() throws Exception {
        var states = new LinkedBlockingQueue<PresenceLiveness.State>();
        try (DatagramSocket server = sender(); var receiver = new UdpPresenceReceiver(states::add)) {
            assertThrows(IllegalArgumentException.class,
                    () -> receiver.onAuthenticated(InetSocketAddress.createUnresolved("server", 5051)));
            var source = new InetSocketAddress(LOOPBACK, server.getLocalPort());
            receiver.onAuthenticated(source);
            assertEquals(PresenceLiveness.State.WAITING, states.poll(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> receiver.onAuthenticated(source));
            receiver.onDisconnected();
            assertTrue(receiver.awaitTermination(1000));
            assertEquals(PresenceLiveness.State.WAITING, receiver.state());
        }
    }
}
