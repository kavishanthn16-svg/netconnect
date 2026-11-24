package com.netconnect.client.transfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client receiver connecting via Java NIO to the server file plane (port 5052),
 * receiving raw bytes, verifying SHA-256 digest, and sending final 1/0 acknowledgment.
 */
public final class NioFileReceiver {

    private static final Logger LOGGER = Logger.getLogger(NioFileReceiver.class.getName());
    private static final int BUFFER_SIZE = 64 * 1024;

    private NioFileReceiver() {
    }

    /**
     * Connects to NIO server, completes handshake, receives raw bytes into target file,
     * verifies SHA-256 checksum, and sends 1-byte ack.
     */
    public static void receiveFile(String host, int port, UUID transferId, String ticket,
                                   long expectedSizeBytes, String expectedSha256,
                                   Path destinationPath, TransferProgressListener progressListener) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(destinationPath, "destinationPath");

        if (expectedSizeBytes < 0 || expectedSizeBytes > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("File size must be between 0 and 20 MiB: " + expectedSizeBytes);
        }

        // Ensure parent directory exists
        Path parent = destinationPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Use a temp file during receive
        Path tempDownloadFile = Files.createTempFile(parent != null ? parent : Path.of("."),
                "netconnect-download-", ".tmp");

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(new InetSocketAddress(host, port));

            // 1. Send handshake
            byte[] handshakeBytes = ClientNioHandshake.encode(transferId, ticket, "RECEIVE");
            channel.write(ByteBuffer.wrap(handshakeBytes));

            // 2. Read 1-byte status
            ByteBuffer responseBuffer = ByteBuffer.allocate(1);
            while (responseBuffer.hasRemaining()) {
                int read = channel.read(responseBuffer);
                if (read == -1) {
                    throw new IOException("Server closed connection during receive handshake");
                }
            }
            responseBuffer.flip();
            byte status = responseBuffer.get();
            if (status != 1) {
                throw new IOException("Server denied file receive handshake (status " + status + ")");
            }

            // 3. Receive raw file bytes and compute digest
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }

            long totalBytesReceived = 0;
            if (expectedSizeBytes > 0) {
                try (FileChannel fileChannel = FileChannel.open(tempDownloadFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

                    while (totalBytesReceived < expectedSizeBytes) {
                        buffer.clear();
                        int read = channel.read(buffer);
                        if (read == -1) {
                            throw new IOException("Connection closed prematurely after " + totalBytesReceived + " bytes");
                        }
                        buffer.flip();

                        totalBytesReceived += buffer.remaining();
                        buffer.mark();
                        digest.update(buffer);
                        buffer.reset();

                        while (buffer.hasRemaining()) {
                            fileChannel.write(buffer);
                        }

                        if (progressListener != null) {
                            progressListener.onProgress(totalBytesReceived, expectedSizeBytes);
                        }
                    }
                }
            } else {
                // Zero-byte file: empty temp file
                Files.write(tempDownloadFile, new byte[0]);
                if (progressListener != null) {
                    progressListener.onProgress(0, 0);
                }
            }

            // 4. Verify SHA-256
            byte[] calculatedHash = digest.digest();
            String calculatedHex = HexFormat.of().formatHex(calculatedHash);

            boolean match = calculatedHex.equalsIgnoreCase(expectedSha256);
            byte ackByte = match ? (byte) 1 : (byte) 0;

            // 5. Send final 1-byte acknowledgment
            channel.write(ByteBuffer.wrap(new byte[]{ackByte}));

            if (!match) {
                Files.deleteIfExists(tempDownloadFile);
                throw new IOException("Checksum mismatch! Expected: " + expectedSha256 + ", Calculated: " + calculatedHex);
            }

            // Atomically replace target destination
            Files.move(tempDownloadFile, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.log(Level.INFO, "Received and verified {0} bytes into {1}",
                    new Object[]{expectedSizeBytes, destinationPath});
        } finally {
            try {
                Files.deleteIfExists(tempDownloadFile);
            } catch (IOException ignored) {
            }
        }
    }
}
