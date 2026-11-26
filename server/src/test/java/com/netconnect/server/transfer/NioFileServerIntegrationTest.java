package com.netconnect.server.transfer;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.ControlGateway;
import com.netconnect.common.contracts.FileOffer;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import com.netconnect.common.contracts.SessionResult;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioFileServerIntegrationTest {

    @TempDir
    Path tempDir;

    private Path stagingDir;
    private int freeNioPort;
    private NioTransferService transferService;
    private TestControlGateway gateway;
    private TestSessionRegistry registry;

    private final Session alice = new SimpleSession("sess-alice", "Alice");
    private final Session bob = new SimpleSession("sess-bob", "Bob");

    @BeforeEach
    void setUp() throws Exception {
        stagingDir = tempDir.resolve("server-staging");
        Files.createDirectories(stagingDir);

        try (ServerSocket ss = new ServerSocket(0)) {
            freeNioPort = ss.getLocalPort();
        }

        NetConnectConfig config = new NetConnectConfig(
                "localhost", 5050, 5051, freeNioPort, stagingDir, tempDir.resolve("downloads")
        );

        gateway = new TestControlGateway();
        registry = new TestSessionRegistry();
        registry.register(alice.displayName(), alice);
        registry.register(bob.displayName(), bob);

        transferService = new NioTransferService(config, registry, gateway, java.time.Clock.systemUTC(), stagingDir);
        transferService.start();
    }

    @AfterEach
    void tearDown() {
        if (transferService != null) {
            transferService.close();
        }
    }

    @Test
    @DisplayName("T06: Transfer empty 0-byte file with 100% progress and empty SHA-256 match")
    void testEmptyFileTransfer() throws Exception {
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "empty.txt", 0L, emptySha);

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), true);

        Envelope senderReady = gateway.findFirstFor(alice.sessionId(), EventType.FILE_READY);
        Envelope receiverReady = gateway.findFirstFor(bob.sessionId(), EventType.FILE_READY);
        assertNotNull(senderReady);
        assertNotNull(receiverReady);

        String senderTicket = senderReady.payload().get("ticket").asText();
        String receiverTicket = receiverReady.payload().get("ticket").asText();

        // 1. Sender connects for SEND
        try (SocketChannel senderChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            NioHandshake handshake = NioHandshake.of(transferId, senderTicket, "SEND");
            senderChan.write(ByteBuffer.wrap(handshake.encode()));

            ByteBuffer res = ByteBuffer.allocate(1);
            senderChan.read(res);
            res.flip();
            assertEquals(1, res.get()); // Accepted status
            // 0 bytes sent
        }

        // 2. Receiver connects for RECEIVE
        try (SocketChannel recvChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            NioHandshake handshake = NioHandshake.of(transferId, receiverTicket, "RECEIVE");
            recvChan.write(ByteBuffer.wrap(handshake.encode()));

            ByteBuffer res = ByteBuffer.allocate(1);
            recvChan.read(res);
            res.flip();
            assertEquals(1, res.get()); // Accepted status

            // 0 bytes to read, send 1-byte ack
            recvChan.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertTrue(gateway.waitForResult(alice.sessionId(), transferId, 3000));
        assertTrue(gateway.waitForResult(bob.sessionId(), transferId, 3000));

        Envelope resultEnv = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        assertEquals("SUCCESS", resultEnv.payload().get("status").asText());
    }

    @Test
    @DisplayName("T06: Transfer 1 KiB file with real NIO channels and checksum match")
    void testOneKiBFileTransfer() throws Exception {
        executeTransferTest(1024);
    }

    @Test
    @DisplayName("T06: Transfer 1 MiB file with multi-chunk buffer streaming")
    void testOneMiBFileTransfer() throws Exception {
        executeTransferTest(1024 * 1024);
    }

    @Test
    @DisplayName("T06: Transfer 20 MiB boundary file successfully")
    void testTwentyMiBFileTransfer() throws Exception {
        executeTransferTest(20 * 1024 * 1024);
    }

    @Test
    @DisplayName("T06: Binary bytes containing 0x0A, 0x0D, 0x00, 0xFF are not corrupted by line parsing")
    void testBinaryBytesWithNewlinesAndControlChars() throws Exception {
        byte[] pattern = new byte[]{0x0A, 0x0D, 0x00, (byte) 0xFF, 0x0A, 0x55, 0x0D, 0x0A};
        byte[] data = new byte[8192];
        for (int i = 0; i < data.length; i++) {
            data[i] = pattern[i % pattern.length];
        }

        String sha256 = sha256Of(data);
        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "binary.dat", data.length, sha256);

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), true);

        Envelope senderReady = gateway.findFirstFor(alice.sessionId(), EventType.FILE_READY);
        Envelope receiverReady = gateway.findFirstFor(bob.sessionId(), EventType.FILE_READY);

        String senderTicket = senderReady.payload().get("ticket").asText();
        String receiverTicket = receiverReady.payload().get("ticket").asText();

        // Sender
        try (SocketChannel senderChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            senderChan.write(ByteBuffer.wrap(NioHandshake.of(transferId, senderTicket, "SEND").encode()));
            ByteBuffer res = ByteBuffer.allocate(1);
            senderChan.read(res);
            assertEquals(1, res.get(0));

            senderChan.write(ByteBuffer.wrap(data));
        }

        // Receiver
        byte[] received = new byte[data.length];
        try (SocketChannel recvChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            recvChan.write(ByteBuffer.wrap(NioHandshake.of(transferId, receiverTicket, "RECEIVE").encode()));
            ByteBuffer res = ByteBuffer.allocate(1);
            recvChan.read(res);
            assertEquals(1, res.get(0));

            ByteBuffer readBuf = ByteBuffer.wrap(received);
            while (readBuf.hasRemaining()) {
                int r = recvChan.read(readBuf);
                if (r == -1) break;
            }

            assertEquals(sha256, sha256Of(received));
            assertArrayEquals(data, received);

            // Send ack
            recvChan.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertTrue(gateway.waitForResult(alice.sessionId(), transferId, 3000));
        Envelope res = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        assertEquals("SUCCESS", res.payload().get("status").asText());
    }

    @Test
    @DisplayName("T07: Connecting with invalid or expired ticket is denied with byte 0 and socket closed")
    void testInvalidTicketRejected() throws Exception {
        UUID randomTransferId = UUID.randomUUID();
        NioHandshake handshake = NioHandshake.of(randomTransferId, "fake-ticket", "SEND");
        byte[] payload = handshake.encode();

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            channel.write(ByteBuffer.wrap(payload));

            ByteBuffer res = ByteBuffer.allocate(1);
            int read = channel.read(res);
            res.flip();

            assertEquals(1, read);
            assertEquals(0, res.get()); // 0 indicates denied

            // Server closes socket
            res.clear();
            int eof = channel.read(res);
            assertEquals(-1, eof);
        }
    }

    @Test
    @DisplayName("Checksum mismatch detected: server aborts transfer and emits CHECKSUM_MISMATCH")
    void testChecksumMismatchDetected() throws Exception {
        byte[] realBytes = "Actual Content".getBytes();
        String fakeSha = "0000000000000000000000000000000000000000000000000000000000000000";

        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "real.txt", realBytes.length, fakeSha);

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), true);

        Envelope senderReady = gateway.findFirstFor(alice.sessionId(), EventType.FILE_READY);
        String senderTicket = senderReady.payload().get("ticket").asText();

        try (SocketChannel senderChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            senderChan.write(ByteBuffer.wrap(NioHandshake.of(transferId, senderTicket, "SEND").encode()));
            ByteBuffer res = ByteBuffer.allocate(1);
            senderChan.read(res);
            assertEquals(1, res.get(0));

            senderChan.write(ByteBuffer.wrap(realBytes));
        }

        assertTrue(gateway.waitForResult(alice.sessionId(), transferId, 3000));
        Envelope resultEnv = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        assertEquals("FAILED", resultEnv.payload().get("status").asText());
        assertEquals("CHECKSUM_MISMATCH", resultEnv.payload().get("code").asText());
    }

    @Test
    @DisplayName("T08: Recipient rejects offer: emits TRANSFER_REJECTED and cleans up")
    void testRecipientRejection() {
        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "doc.pdf", 100L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), false);

        Envelope senderResult = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        Envelope recipientResult = gateway.findFirstFor(bob.sessionId(), EventType.FILE_RESULT);

        assertNotNull(senderResult);
        assertNotNull(recipientResult);
        assertEquals("REJECTED", senderResult.payload().get("status").asText());
        assertEquals("TRANSFER_REJECTED", senderResult.payload().get("code").asText());
    }

    @Test
    @DisplayName("T08: Broken connection mid-stream: server detects premature EOF and cleans up staging")
    void testBrokenConnectionMidStream() throws Exception {
        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "large.dat", 1024 * 1024L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), true);

        Envelope senderReady = gateway.findFirstFor(alice.sessionId(), EventType.FILE_READY);
        String senderTicket = senderReady.payload().get("ticket").asText();

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            channel.write(ByteBuffer.wrap(NioHandshake.of(transferId, senderTicket, "SEND").encode()));
            ByteBuffer resp = ByteBuffer.allocate(1);
            channel.read(resp);
            assertEquals(1, resp.get(0));

            channel.write(ByteBuffer.wrap(new byte[100]));
        }

        assertTrue(gateway.waitForResult(alice.sessionId(), transferId, 3000));
        Envelope result = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        assertEquals("FAILED", result.payload().get("status").asText());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void malformedHandshakesRejectWithoutLoggingTicketDataOrExceptionCauses() throws Exception {
        String marker = "SYNTHETIC_SECRET_TICKET";
        String prefix = "{\"v\":1,\"transferId\":\"00000000-0000-0000-0000-000000000001\",\"ticket\":";
        // Exercise both Jackson parse failures and validation failures with secret-bearing causes.
        List<String> malformed = List.of(prefix + marker + ",\"role\":\"SEND\"}",
                prefix + "\"" + marker + "\",\"role\":\"" + marker + "\"}");
        Logger logger = Logger.getLogger(NioFileServer.class.getName());
        try (LogCapture capture = new LogCapture(logger)) {
            for (String payload : malformed) {
                byte[] json = payload.getBytes(StandardCharsets.UTF_8);
                ByteBuffer frame = ByteBuffer.allocate(4 + json.length).putInt(json.length).put(json);
                try (Socket client = new Socket("localhost", freeNioPort)) {
                    client.setSoTimeout(2000);
                    client.getOutputStream().write(frame.array());
                    assertEquals(0, client.getInputStream().read(), "Malformed handshake must be rejected");
                    assertEquals(-1, client.getInputStream().read(), "Rejected connection must close");
                }
            }
            List<LogRecord> failures = capture.records.stream()
                    .filter(record -> record.getMessage().startsWith("Handshake decode failed")).toList();
            assertEquals(2, failures.size());
            for (LogRecord record : failures) {
                assertNull(record.getThrown(), "A nested parser exception can contain ticket data");
                assertFalse(new SimpleFormatter().format(record).contains(marker));
            }
        }
    }

    @Test
    void disconnectLogRetainsTransferDiagnosticsWithoutSessionIdentifiers() throws Exception {
        UUID transferId = UUID.randomUUID();
        try (LogCapture capture = new LogCapture(Logger.getLogger(NioTransferService.class.getName()))) {
            transferService.offer(alice, new FileOffer(transferId, "Bob", "disconnect.txt", 1,
                    sha256Of(new byte[]{1})));
            transferService.decide(bob, transferId.toString(), true);
            assertEquals(2, transferService.ticketRegistry().size());
            registry.remove(alice.sessionId());
            transferService.handleDisconnect(alice.sessionId());
            String rendered = capture.records.stream().map(new SimpleFormatter()::format)
                    .collect(java.util.stream.Collectors.joining());
            assertFalse(rendered.contains(alice.sessionId()));
            assertFalse(rendered.contains(bob.sessionId()));
            assertTrue(rendered.contains("Aborting transfer " + transferId));
            assertTrue(rendered.contains("disconnected"));
            assertTrue(transferService.activeTransfers().isEmpty());
            assertEquals(0, transferService.ticketRegistry().size());
            Envelope result = gateway.findFirstFor(bob.sessionId(), EventType.FILE_RESULT);
            assertNotNull(result);
            assertEquals("FAILED", result.payload().path("status").asText());
            assertEquals("USER_OFFLINE", result.payload().path("code").asText());
        }
    }

    private static final class LogCapture extends Handler implements AutoCloseable {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;
        LogCapture(Logger logger) { this.logger = logger; logger.addHandler(this); }
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { logger.removeHandler(this); }
    }

    private void executeTransferTest(int size) throws Exception {
        byte[] testBytes = new byte[size];
        new Random(42).nextBytes(testBytes);
        String sha256 = sha256Of(testBytes);

        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(transferId, "Bob", "file_" + size + ".bin", size, sha256);

        transferService.offer(alice, offer);
        transferService.decide(bob, transferId.toString(), true);

        Envelope senderReady = gateway.findFirstFor(alice.sessionId(), EventType.FILE_READY);
        Envelope receiverReady = gateway.findFirstFor(bob.sessionId(), EventType.FILE_READY);

        String senderTicket = senderReady.payload().get("ticket").asText();
        String receiverTicket = receiverReady.payload().get("ticket").asText();

        // 1. Sender stream
        try (SocketChannel senderChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            senderChan.write(ByteBuffer.wrap(NioHandshake.of(transferId, senderTicket, "SEND").encode()));
            ByteBuffer res = ByteBuffer.allocate(1);
            senderChan.read(res);
            assertEquals(1, res.get(0));

            ByteBuffer writeBuf = ByteBuffer.wrap(testBytes);
            while (writeBuf.hasRemaining()) {
                senderChan.write(writeBuf);
            }
        }

        // 2. Receiver stream
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
        try (SocketChannel recvChan = SocketChannel.open(new InetSocketAddress("localhost", freeNioPort))) {
            recvChan.write(ByteBuffer.wrap(NioHandshake.of(transferId, receiverTicket, "RECEIVE").encode()));
            ByteBuffer res = ByteBuffer.allocate(1);
            recvChan.read(res);
            assertEquals(1, res.get(0));

            ByteBuffer readBuf = ByteBuffer.allocate(64 * 1024);
            long totalRead = 0;
            while (totalRead < size) {
                readBuf.clear();
                int r = recvChan.read(readBuf);
                if (r == -1) break;
                readBuf.flip();
                totalRead += readBuf.remaining();
                byte[] chunk = new byte[readBuf.remaining()];
                readBuf.get(chunk);
                baos.write(chunk);
            }

            // Verify SHA-256
            byte[] receivedBytes = baos.toByteArray();
            assertEquals(sha256, sha256Of(receivedBytes));
            assertArrayEquals(testBytes, receivedBytes);

            // Send 1-byte ack
            recvChan.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertTrue(gateway.waitForResult(alice.sessionId(), transferId, 5000));
        Envelope res = gateway.findFirstFor(alice.sessionId(), EventType.FILE_RESULT);
        assertEquals("SUCCESS", res.payload().get("status").asText());
    }

    private static String sha256Of(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private record SimpleSession(String sessionId, String displayName) implements Session {}

    private static final class TestControlGateway implements ControlGateway {
        private final List<Envelope> messages = new CopyOnWriteArrayList<>();

        @Override
        public void send(Session session, Envelope envelope) {
            messages.add(envelope);
        }

        public Envelope findFirstFor(String sessionId, EventType type) {
            for (Envelope env : messages) {
                if (env.type() == type && sessionId.equals(env.sessionId())) {
                    return env;
                }
            }
            return null;
        }

        public boolean waitForResult(String sessionId, UUID transferId, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (Envelope env : messages) {
                    if (env.type() == EventType.FILE_RESULT && sessionId.equals(env.sessionId())) {
                        if (transferId.toString().equals(env.payload().get("transferId").asText())) {
                            return true;
                        }
                    }
                }
                Thread.sleep(50);
            }
            return false;
        }
    }

    private static final class TestSessionRegistry implements SessionRegistry {
        private final Map<String, Session> usersByName = new ConcurrentHashMap<>();

        @Override
        public SessionResult register(String displayName, Session connection) {
            usersByName.put(displayName, connection);
            return new SessionResult(true, "OK", connection);
        }

        @Override
        public Optional<Session> getByDisplayName(String name) {
            return Optional.ofNullable(usersByName.get(name));
        }

        @Override
        public List<String> listUsers() {
            return new ArrayList<>(usersByName.keySet());
        }

        @Override
        public void remove(String sessionId) {
            usersByName.values().removeIf(s -> s.sessionId().equals(sessionId));
        }
    }
}
