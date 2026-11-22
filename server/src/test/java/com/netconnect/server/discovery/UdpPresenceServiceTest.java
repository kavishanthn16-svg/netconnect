package com.netconnect.server.discovery;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.Session;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UdpPresenceServiceTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(123_456), ZoneOffset.UTC);

    private record TestSession(String sessionId, String displayName) implements Session { }

    private static class ManualScheduler extends ScheduledThreadPoolExecutor {
        Runnable tick;
        int schedules;
        ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initial,
                                                                 long period, TimeUnit unit) {
            assertEquals(5, initial);
            assertEquals(5, period);
            assertEquals(TimeUnit.SECONDS, unit);
            tick = task;
            schedules++;
            return super.schedule(() -> { }, 1, TimeUnit.DAYS);
        }
    }

    private static NetConnectConfig config(int port) {
        var defaults = NetConnectConfig.defaults();
        return new NetConnectConfig(defaults.serverHost(), defaults.controlPort(), port,
                defaults.transferPort(), defaults.fileStagingDirectory(), defaults.clientDownloadDirectory());
    }

    private static DatagramSocket receiver() throws SocketException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        socket.setSoTimeout(1000);
        return socket;
    }

    private static DatagramPacket receive(DatagramSocket socket) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[512], 512);
        socket.receive(packet);
        return packet;
    }

    @Test void productionSchedulerSendsTwoLoopbackClientsAtFiveSecondIntervals() throws Exception {
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0)) {
            port = reservation.getLocalPort();
        }
        try (DatagramSocket first = receiver(); DatagramSocket second = receiver();
             var service = new UdpPresenceService(config(port), "timed-server", s -> LOOPBACK, () -> 9)) {
            first.setSoTimeout(8000);
            second.setSoTimeout(8000);
            service.registerEndpoint(new TestSession("timed-one", "Alice"), first.getLocalPort());
            service.registerEndpoint(new TestSession("timed-two", "Bobby"), second.getLocalPort());
            service.start();
            long[] previous = new long[2];
            for (int round = 0; round < 2; round++) {
                int index = 0;
                for (DatagramSocket client : new DatagramSocket[]{first, second}) {
                    DatagramPacket packet = receive(client);
                    long now = System.nanoTime();
                    assertEquals(port, packet.getPort());
                    String[] fields = new String(packet.getData(), 0, packet.getLength(),
                            StandardCharsets.UTF_8).split("\\|");
                    assertEquals(4, fields.length);
                    assertEquals("PRESENCE_V1", fields[0]);
                    assertEquals("timed-server", fields[1]);
                    assertEquals("9", fields[2]);
                    assertTrue(Math.abs(System.currentTimeMillis() - Long.parseLong(fields[3])) < 2000);
                    if (round > 0) {
                        long interval = TimeUnit.NANOSECONDS.toMillis(now - previous[index]);
                        assertTrue(interval >= 3500 && interval <= 6500,
                                "Expected approximately 5 seconds; measured " + interval + " ms");
                    }
                    previous[index++] = now;
                }
            }
        }
    }

    @Test void deliversToTwoPortsWithCurrentRevisionAndConfiguredSourceThenCleansUp() throws Exception {
        try (DatagramSocket first = receiver(); DatagramSocket second = receiver();
             DatagramSocket sender = receiver()) {
            ManualScheduler scheduler = new ManualScheduler();
            AtomicLong revision = new AtomicLong(7);
            int port = sender.getLocalPort();
            try (var service = new UdpPresenceService(config(port), "server", s -> LOOPBACK,
                    revision::get, CLOCK, scheduler, requested -> {
                        assertEquals(port, requested);
                        return sender;
                    })) {
                service.registerEndpoint(new TestSession("one", "Alice"), first.getLocalPort());
                service.registerEndpoint(new TestSession("two", "Bobby"), second.getLocalPort());
                service.start();
                service.start();
                assertEquals(1, scheduler.schedules);
                scheduler.tick.run();
                for (DatagramSocket client : new DatagramSocket[]{first, second}) {
                    DatagramPacket packet = receive(client);
                    assertEquals(port, packet.getPort());
                    assertEquals("PRESENCE_V1|server|7|123456", new String(packet.getData(),
                            packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                }
                service.unregisterEndpoint("one");
                revision.set(8);
                scheduler.tick.run();
                DatagramPacket packet = receive(second);
                assertEquals("PRESENCE_V1|server|8|123456", new String(packet.getData(),
                        0, packet.getLength(), StandardCharsets.UTF_8));
                first.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> receive(first));
                service.stop();
                service.stop();
                assertTrue(sender.isClosed());
                assertTrue(scheduler.isShutdown());
                scheduler.tick.run();
                second.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> receive(second));
                assertThrows(IllegalStateException.class, service::start);
            }
            assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test void bindsActualSocketAndReleasesPort() throws Exception {
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0)) {
            port = reservation.getLocalPort();
        }
        ManualScheduler scheduler = new ManualScheduler();
        try (DatagramSocket client = receiver();
             var service = new UdpPresenceService(config(port), "s", s -> LOOPBACK,
                     () -> 0, CLOCK, scheduler, DatagramSocket::new)) {
            service.registerEndpoint(new TestSession("id", "Alice"), client.getLocalPort());
            service.start();
            scheduler.tick.run();
            assertEquals(port, receive(client).getPort());
        }
        try (DatagramSocket rebound = new DatagramSocket(port)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test void reportsBindFailureWithPortAndCause() throws Exception {
        try (DatagramSocket occupied = new DatagramSocket(0)) {
            ManualScheduler scheduler = new ManualScheduler();
            try (var service = new UdpPresenceService(config(occupied.getLocalPort()), "s",
                    s -> LOOPBACK, () -> 0, CLOCK, scheduler, DatagramSocket::new)) {
                var error = assertThrows(IllegalStateException.class, service::start);
                assertTrue(error.getMessage().contains(Integer.toString(occupied.getLocalPort())));
                assertInstanceOf(SocketException.class, error.getCause());
                assertEquals(0, scheduler.schedules);
            }
            assertTrue(scheduler.isShutdown());
        }
    }

    @Test void isolatedSendFailureDoesNotPreventOtherClientDelivery() throws Exception {
        try (DatagramSocket bad = receiver(); DatagramSocket good = receiver()) {
            DatagramSocket sender = new DatagramSocket(0) {
                @Override public void send(DatagramPacket packet) throws IOException {
                    if (packet.getPort() == bad.getLocalPort()) {
                        throw new IOException("Simulated endpoint send failure");
                    }
                    super.send(packet);
                }
            };
            ManualScheduler scheduler = new ManualScheduler();
            try (var service = new UdpPresenceService(config(sender.getLocalPort()), "s",
                    s -> LOOPBACK, () -> 0, CLOCK, scheduler, port -> sender)) {
                service.registerEndpoint(new TestSession("bad", "Alice"), bad.getLocalPort());
                service.registerEndpoint(new TestSession("good", "Bobby"), good.getLocalPort());
                service.start();
                scheduler.tick.run();
                assertNotNull(receive(good));
                scheduler.tick.run();
                assertNotNull(receive(good));
            }
        }
    }

    @Test void rejectsInvalidRegistrationAndStopBeforeStartIsTerminal() {
        ManualScheduler scheduler = new ManualScheduler();
        try (var service = new UdpPresenceService(NetConnectConfig.defaults(), "s",
                s -> LOOPBACK, () -> 0, CLOCK, scheduler, DatagramSocket::new)) {
            Session session = new TestSession("id", "Alice");
            assertThrows(IllegalArgumentException.class, () -> service.registerEndpoint(session, 0));
            assertThrows(IllegalArgumentException.class, () -> service.registerEndpoint(session, 65536));
            service.stop();
            assertThrows(IllegalStateException.class,
                    () -> service.registerEndpoint(session, 1234));
            assertThrows(IllegalStateException.class, service::start);
            assertTrue(scheduler.isShutdown());
        }
    }
}
