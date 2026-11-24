package com.netconnect.client.transfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client sender streaming raw bytes over Java NIO to the server file plane (port 5052).
 */
public final class NioFileSender {

    private static final Logger LOGGER = Logger.getLogger(NioFileSender.class.getName());
    private static final int BUFFER_SIZE = 64 * 1024;

    private NioFileSender() {
    }

    /**
     * Connects to NIO server, sends framed JSON handshake, and streams raw file bytes.
     */
    public static void sendFile(String host, int port, UUID transferId, String ticket,
                                Path filePath, TransferProgressListener progressListener) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(filePath, "filePath");

        long fileSize = java.nio.file.Files.size(filePath);
        if (fileSize > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("File exceeds 20 MiB limit: " + fileSize);
        }

        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(new InetSocketAddress(host, port));

            // 1. Send framed handshake
            byte[] handshakeBytes = ClientNioHandshake.encode(transferId, ticket, "SEND");
            channel.write(ByteBuffer.wrap(handshakeBytes));

            // 2. Read 1-byte response
            ByteBuffer responseBuffer = ByteBuffer.allocate(1);
            while (responseBuffer.hasRemaining()) {
                int read = channel.read(responseBuffer);
                if (read == -1) {
                    throw new IOException("Server closed connection during handshake");
                }
            }
            responseBuffer.flip();
            byte status = responseBuffer.get();
            if (status != 1) {
                throw new IOException("Server denied file send handshake (status " + status + ")");
            }

            // 3. Stream file bytes
            if (fileSize == 0) {
                if (progressListener != null) {
                    progressListener.onProgress(0, 0);
                }
                return;
            }

            try (FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                long totalBytesSent = 0;

                while (totalBytesSent < fileSize) {
                    buffer.clear();
                    int bytesReadFromFile = fileChannel.read(buffer);
                    if (bytesReadFromFile == -1) {
                        break;
                    }
                    buffer.flip();

                    while (buffer.hasRemaining()) {
                        int written = channel.write(buffer);
                        totalBytesSent += written;
                        if (progressListener != null) {
                            progressListener.onProgress(totalBytesSent, fileSize);
                        }
                    }
                }

                if (totalBytesSent < fileSize) {
                    throw new IOException("Failed to send complete file: sent " + totalBytesSent + " of " + fileSize);
                }
            }

            LOGGER.log(Level.INFO, "Client successfully streamed {0} bytes for transfer {1}",
                    new Object[]{fileSize, transferId});
        }
    }
}
