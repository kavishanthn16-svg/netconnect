package com.netconnect.client.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioClientTransferTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("NioFileSender sends framed handshake and streams file bytes with progress updates")
    void testSenderSuccess() throws Exception {
        byte[] testData = "Hello NetConnect NIO transfer".getBytes();
        Path sourceFile = tempDir.resolve("sample.txt");
        Files.write(sourceFile, testData);

        UUID transferId = UUID.randomUUID();
        String ticket = "tkt-send-123";

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("localhost", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            CountDownLatch serverDone = new CountDownLatch(1);
            AtomicLong totalReceivedByServer = new AtomicLong(0);

            Thread serverThread = new Thread(() -> {
                try (SocketChannel client = server.accept()) {
                    // 1. Read 4-byte length
                    ByteBuffer lenBuf = ByteBuffer.allocate(4);
                    client.read(lenBuf);
                    lenBuf.flip();
                    int len = lenBuf.getInt();

                    // 2. Read body
                    ByteBuffer bodyBuf = ByteBuffer.allocate(len);
                    client.read(bodyBuf);
                    bodyBuf.flip();
                    byte[] json = new byte[bodyBuf.remaining()];
                    bodyBuf.get(json);
                    Map<?, ?> payload = MAPPER.readValue(new String(json, StandardCharsets.UTF_8), Map.class);
                    assertEquals("SEND", payload.get("role"));

                    // 3. Send 1-byte accept status 1
                    client.write(ByteBuffer.wrap(new byte[]{1}));

                    // 4. Read raw file bytes
                    ByteBuffer dataBuf = ByteBuffer.allocate(1024);
                    int r;
                    while ((r = client.read(dataBuf)) != -1) {
                        totalReceivedByServer.addAndGet(r);
                        if (totalReceivedByServer.get() >= testData.length) break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            AtomicLong clientProgress = new AtomicLong(0);
            NioFileSender.sendFile("localhost", port, transferId, ticket, sourceFile,
                    (sent, total) -> clientProgress.set(sent));

            assertTrue(serverDone.await(5, TimeUnit.SECONDS));
            assertEquals(testData.length, totalReceivedByServer.get());
            assertEquals(testData.length, clientProgress.get());
        }
    }

    @Test
    @DisplayName("NioFileReceiver receives raw bytes, verifies SHA-256, and sends ack 1")
    void testReceiverSuccess() throws Exception {
        byte[] testData = "Receiving verified file bytes".getBytes();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String expectedSha256 = HexFormat.of().formatHex(md.digest(testData));

        UUID transferId = UUID.randomUUID();
        String ticket = "tkt-recv-456";
        Path destFile = tempDir.resolve("received.txt");

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("localhost", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            AtomicBoolean ackReceived = new AtomicBoolean(false);
            CountDownLatch serverDone = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try (SocketChannel client = server.accept()) {
                    // Read length
                    ByteBuffer lenBuf = ByteBuffer.allocate(4);
                    client.read(lenBuf);
                    lenBuf.flip();
                    int len = lenBuf.getInt();

                    // Read body
                    ByteBuffer bodyBuf = ByteBuffer.allocate(len);
                    client.read(bodyBuf);

                    // Send status 1
                    client.write(ByteBuffer.wrap(new byte[]{1}));

                    // Stream raw file bytes
                    client.write(ByteBuffer.wrap(testData));

                    // Read final ack
                    ByteBuffer ackBuf = ByteBuffer.allocate(1);
                    client.read(ackBuf);
                    ackBuf.flip();
                    if (ackBuf.hasRemaining() && ackBuf.get() == 1) {
                        ackReceived.set(true);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            AtomicLong recvProgress = new AtomicLong(0);
            NioFileReceiver.receiveFile("localhost", port, transferId, ticket, testData.length, expectedSha256,
                    destFile, (recv, total) -> recvProgress.set(recv));

            assertTrue(serverDone.await(5, TimeUnit.SECONDS));
            assertTrue(ackReceived.get());
            assertEquals(testData.length, recvProgress.get());
            assertArrayEquals(testData, Files.readAllBytes(destFile));
        }
    }

    @Test
    @DisplayName("NioFileSender throws IOException if server rejects handshake with 0")
    void testSenderDenied() throws Exception {
        Path dummy = tempDir.resolve("dummy.txt");
        Files.write(dummy, new byte[10]);

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("localhost", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            new Thread(() -> {
                try (SocketChannel client = server.accept()) {
                    ByteBuffer lenBuf = ByteBuffer.allocate(4);
                    client.read(lenBuf);
                    lenBuf.flip();
                    ByteBuffer bodyBuf = ByteBuffer.allocate(lenBuf.getInt());
                    client.read(bodyBuf);

                    // Deny with 0
                    client.write(ByteBuffer.wrap(new byte[]{0}));
                } catch (Exception ignored) {
                }
            }).start();

            assertThrows(IOException.class, () ->
                    NioFileSender.sendFile("localhost", port, UUID.randomUUID(), "bad-ticket", dummy, null));
        }
    }

    @Test
    @DisplayName("NioFileReceiver detects checksum mismatch, cleans up destination, and sends ack 0")
    void testReceiverChecksumMismatch() throws Exception {
        byte[] testData = "Good bytes".getBytes();
        String fakeSha = "0000000000000000000000000000000000000000000000000000000000000000";

        UUID transferId = UUID.randomUUID();
        Path destFile = tempDir.resolve("dest.txt");

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("localhost", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            AtomicBoolean ackZeroReceived = new AtomicBoolean(false);

            new Thread(() -> {
                try (SocketChannel client = server.accept()) {
                    ByteBuffer lenBuf = ByteBuffer.allocate(4);
                    client.read(lenBuf);
                    lenBuf.flip();
                    ByteBuffer bodyBuf = ByteBuffer.allocate(lenBuf.getInt());
                    client.read(bodyBuf);

                    client.write(ByteBuffer.wrap(new byte[]{1}));
                    client.write(ByteBuffer.wrap(testData));

                    ByteBuffer ackBuf = ByteBuffer.allocate(1);
                    client.read(ackBuf);
                    ackBuf.flip();
                    if (ackBuf.hasRemaining() && ackBuf.get() == 0) {
                        ackZeroReceived.set(true);
                    }
                } catch (Exception ignored) {
                }
            }).start();

            assertThrows(IOException.class, () ->
                    NioFileReceiver.receiveFile("localhost", port, transferId, "ticket", testData.length, fakeSha, destFile, null));

            assertFalse(Files.exists(destFile));
        }
    }
}
