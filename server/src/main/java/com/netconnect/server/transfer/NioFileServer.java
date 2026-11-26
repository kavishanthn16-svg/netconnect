package com.netconnect.server.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance Java NIO File Transfer Server (port 5052).
 * Uses ServerSocketChannel, non-blocking SocketChannel, Selector, and ByteBuffers.
 * Strictly implements SRS Section 8.5 framing, single-use ticket verification,
 * raw byte streaming, SHA-256 verification, and receive delivery with final 1/0 ack.
 */
public final class NioFileServer implements Closeable, Runnable {

    private static final Logger LOGGER = Logger.getLogger(NioFileServer.class.getName());
    public static final int BUFFER_SIZE = 64 * 1024;
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MiB

    public interface TransferListener {
        void onCompleted(ActiveTransfer transfer);
        void onFailed(ActiveTransfer transfer, String errorCode, String message);
    }

    private final int port;
    private final TicketRegistry ticketRegistry;
    private final ConcurrentMap<UUID, ActiveTransfer> activeTransfers;
    private final Path stagingDirectory;
    private final Clock clock;
    private final TransferListener transferListener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread workerThread;

    public NioFileServer(int port,
                         TicketRegistry ticketRegistry,
                         ConcurrentMap<UUID, ActiveTransfer> activeTransfers,
                         Path stagingDirectory,
                         Clock clock,
                         TransferListener transferListener) {
        this.port = port;
        this.ticketRegistry = Objects.requireNonNull(ticketRegistry, "ticketRegistry");
        this.activeTransfers = Objects.requireNonNull(activeTransfers, "activeTransfers");
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transferListener = Objects.requireNonNull(transferListener, "transferListener");
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        Files.createDirectories(stagingDirectory);

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running.set(true);
        workerThread = new Thread(this, "netconnect-nio-transfer");
        workerThread.setDaemon(true);
        workerThread.start();

        LOGGER.log(Level.INFO, "NIO File Transfer Server started on port {0}", port);
    }

    public int boundPort() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            } catch (IOException ignored) {
            }
        }
        return port;
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                int selected = selector.select(500);
                if (selected == 0) {
                    continue;
                }

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error handling NIO key", e);
                        closeKey(key);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Error in NIO selector loop", e);
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);

        ConnectionAttachment attachment = new ConnectionAttachment();
        client.register(selector, SelectionKey.OP_READ, attachment);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionAttachment attachment = (ConnectionAttachment) key.attachment();

        switch (attachment.state) {
            case READING_HANDSHAKE_LENGTH -> {
                int read = channel.read(attachment.lengthBuffer);
                if (read == -1) {
                    closeKey(key);
                    return;
                }
                if (!attachment.lengthBuffer.hasRemaining()) {
                    attachment.lengthBuffer.flip();
                    int bodyLength = attachment.lengthBuffer.getInt();
                    if (bodyLength <= 0 || bodyLength > NioHandshake.MAX_HANDSHAKE_BYTES) {
                        LOGGER.warning("Invalid handshake length: " + bodyLength);
                        sendHandshakeResponseAndClose(key, (byte) 0);
                        return;
                    }
                    attachment.bodyBuffer = ByteBuffer.allocate(bodyLength);
                    attachment.state = ConnectionState.READING_HANDSHAKE_BODY;
                }
            }
            case READING_HANDSHAKE_BODY -> {
                int read = channel.read(attachment.bodyBuffer);
                if (read == -1) {
                    closeKey(key);
                    return;
                }
                if (!attachment.bodyBuffer.hasRemaining()) {
                    attachment.bodyBuffer.flip();
                    byte[] jsonBytes = new byte[attachment.bodyBuffer.remaining()];
                    attachment.bodyBuffer.get(jsonBytes);
                    processHandshake(key, jsonBytes);
                }
            }
            case RECEIVING_RAW_BYTES -> {
                ActiveTransfer transfer = attachment.transfer;
                if (transfer == null || transfer.isTerminal()) {
                    closeKey(key);
                    return;
                }

                attachment.readBuffer.clear();
                int read = channel.read(attachment.readBuffer);
                if (read == -1) {
                    // Premature EOF
                    if (attachment.bytesProcessed < transfer.sizeBytes()) {
                        LOGGER.warning("Premature EOF from sender for transfer " + transfer.transferId());
                        transfer.fail("Premature EOF from sender");
                        transferListener.onFailed(transfer, "INTERNAL_ERROR", "Sender disconnected prematurely");
                    }
                    closeKey(key);
                    return;
                }

                if (read > 0) {
                    attachment.readBuffer.flip();
                    int bytesInChunk = attachment.readBuffer.remaining();
                    attachment.bytesProcessed += bytesInChunk;

                    if (attachment.bytesProcessed > transfer.sizeBytes()
                            || attachment.bytesProcessed > MAX_FILE_SIZE) {
                        LOGGER.warning("Sender exceeded transfer file size limit");
                        transfer.fail("File size limit exceeded");
                        transferListener.onFailed(transfer, "FILE_TOO_LARGE", "File size exceeded declared size");
                        closeKey(key);
                        return;
                    }

                    // Update digest
                    attachment.readBuffer.mark();
                    attachment.digest.update(attachment.readBuffer);
                    attachment.readBuffer.reset();

                    // Write to staging file
                    while (attachment.readBuffer.hasRemaining()) {
                        attachment.stagingFileChannel.write(attachment.readBuffer);
                    }

                    // Check if sender has sent all bytes
                    if (attachment.bytesProcessed == transfer.sizeBytes()) {
                        finishSenderStaging(key, attachment);
                    }
                }
            }
            case WAITING_FOR_RECEIVER_ACK -> {
                int read = channel.read(attachment.ackBuffer);
                if (read == -1) {
                    LOGGER.warning("Receiver disconnected before sending final ack");
                    ActiveTransfer transfer = attachment.transfer;
                    if (transfer != null) {
                        transfer.fail("Receiver disconnected before final ack");
                        transferListener.onFailed(transfer, "INTERNAL_ERROR", "Receiver disconnected before ack");
                    }
                    closeKey(key);
                    return;
                }

                if (!attachment.ackBuffer.hasRemaining()) {
                    attachment.ackBuffer.flip();
                    byte ack = attachment.ackBuffer.get();
                    ActiveTransfer transfer = attachment.transfer;
                    if (transfer != null) {
                        if (ack == 1) {
                            transfer.transitionTo(ActiveTransfer.State.COMPLETED);
                            transfer.cleanupStagingFile();
                            transferListener.onCompleted(transfer);
                        } else {
                            transfer.fail("Receiver reported checksum mismatch");
                            transferListener.onFailed(transfer, "CHECKSUM_MISMATCH", "Receiver rejected file digest");
                        }
                    }
                    closeKey(key);
                }
            }
            default -> {
                // Ignore unexpected reads
            }
        }
    }

    private void processHandshake(SelectionKey key, byte[] jsonBytes) throws IOException {
        ConnectionAttachment attachment = (ConnectionAttachment) key.attachment();
        final NioHandshake handshake;
        try {
            handshake = NioHandshake.decode(jsonBytes);
        } catch (Exception e) {
            LOGGER.warning("Handshake decode failed: malformed request rejected");
            sendHandshakeResponseAndClose(key, (byte) 0);
            return;
        }

        ActiveTransfer transfer = activeTransfers.get(handshake.transferId());
        if (transfer == null || transfer.isTerminal()) {
            LOGGER.warning("Transfer not found or terminated: " + handshake.transferId());
            sendHandshakeResponseAndClose(key, (byte) 0);
            return;
        }

        // Validate ticket
        Optional<TransferTicket> ticketOpt = ticketRegistry.validateAndConsume(
                handshake.ticket(),
                handshake.transferId(),
                handshake.role(),
                clock.millis()
        );

        if (ticketOpt.isEmpty()) {
            LOGGER.warning("Invalid, expired, or mismatched transfer ticket");
            sendHandshakeResponseAndClose(key, (byte) 0);
            return;
        }

        attachment.transfer = transfer;

        if ("SEND".equals(handshake.role())) {
            transfer.setSenderChannel((SocketChannel) key.channel());
            try {
                Path stageFile = Files.createTempFile(stagingDirectory, "staging-" + transfer.transferId(), ".tmp");
                transfer.setStagingFile(stageFile);
                attachment.stagingFileChannel = FileChannel.open(stageFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                attachment.digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to initialize staging file", e);
                sendHandshakeResponseAndClose(key, (byte) 0);
                return;
            }

            // Send accept status 1
            sendHandshakeResponse(key, (byte) 1, () -> {
                attachment.state = ConnectionState.RECEIVING_RAW_BYTES;
                key.interestOps(SelectionKey.OP_READ);
                // Zero-byte handling
                if (transfer.sizeBytes() == 0) {
                    try {
                        finishSenderStaging(key, attachment);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to finish zero-byte staging", e);
                        closeKey(key);
                    }
                }
            });

        } else if ("RECEIVE".equals(handshake.role())) {
            transfer.setReceiverChannel((SocketChannel) key.channel());

            sendHandshakeResponse(key, (byte) 1, () -> {
                if (transfer.state() == ActiveTransfer.State.STAGED) {
                    startStreamingToReceiver(key, attachment);
                } else {
                    attachment.state = ConnectionState.RECEIVER_WAITING_FOR_STAGE;
                    key.interestOps(0); // Pause until staged
                }
            });
        }
    }

    private void finishSenderStaging(SelectionKey key, ConnectionAttachment attachment) throws IOException {
        ActiveTransfer transfer = attachment.transfer;
        if (attachment.stagingFileChannel != null && attachment.stagingFileChannel.isOpen()) {
            attachment.stagingFileChannel.force(true);
            attachment.stagingFileChannel.close();
        }

        byte[] calculatedDigest = attachment.digest.digest();
        String calculatedSha256 = HexFormat.of().formatHex(calculatedDigest);

        if (!calculatedSha256.equalsIgnoreCase(transfer.expectedSha256())) {
            LOGGER.log(Level.WARNING, "Server staging checksum mismatch! expected={0}, actual={1}",
                    new Object[]{transfer.expectedSha256(), calculatedSha256});
            transfer.fail("Checksum mismatch on server staging");
            transferListener.onFailed(transfer, "CHECKSUM_MISMATCH", "Checksum mismatch during server staging");
            closeKey(key);
            return;
        }

        LOGGER.info("Sender finished staging transfer " + transfer.transferId() + " successfully.");
        transfer.transitionTo(ActiveTransfer.State.STAGED);

        // Disconnect or unregister sender socket as staging is done
        closeKey(key);

        // Check if receiver is already connected and waiting
        SocketChannel receiverChannel = transfer.receiverChannel();
        if (receiverChannel != null && receiverChannel.isOpen()) {
            SelectionKey receiverKey = receiverChannel.keyFor(selector);
            if (receiverKey != null && receiverKey.isValid()) {
                ConnectionAttachment receiverAttachment = (ConnectionAttachment) receiverKey.attachment();
                if (receiverAttachment != null && receiverAttachment.state == ConnectionState.RECEIVER_WAITING_FOR_STAGE) {
                    startStreamingToReceiver(receiverKey, receiverAttachment);
                    selector.wakeup();
                }
            }
        }
    }

    private void startStreamingToReceiver(SelectionKey key, ConnectionAttachment attachment) {
        ActiveTransfer transfer = attachment.transfer;
        try {
            transfer.transitionTo(ActiveTransfer.State.RECEIVING);
            if (transfer.sizeBytes() == 0) {
                // Zero-byte file: nothing to stream, wait directly for final ack
                attachment.state = ConnectionState.WAITING_FOR_RECEIVER_ACK;
                attachment.ackBuffer = ByteBuffer.allocate(1);
                key.interestOps(SelectionKey.OP_READ);
                return;
            }

            attachment.stagingFileChannel = FileChannel.open(transfer.stagingFile(), StandardOpenOption.READ);
            attachment.writeBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            attachment.writeBuffer.flip(); // Start empty
            attachment.state = ConnectionState.STREAMING_TO_RECEIVER;
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to open staging file for receiver streaming", e);
            transfer.fail("Failed to open staging file");
            transferListener.onFailed(transfer, "INTERNAL_ERROR", "Failed to stream file to recipient");
            closeKey(key);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionAttachment attachment = (ConnectionAttachment) key.attachment();

        switch (attachment.state) {
            case SENDING_HANDSHAKE_RESPONSE -> {
                channel.write(attachment.responseBuffer);
                if (!attachment.responseBuffer.hasRemaining()) {
                    if (attachment.afterResponseAction != null) {
                        Runnable action = attachment.afterResponseAction;
                        attachment.afterResponseAction = null;
                        action.run();
                    }
                }
            }
            case STREAMING_TO_RECEIVER -> {
                ActiveTransfer transfer = attachment.transfer;
                if (transfer == null || transfer.isTerminal()) {
                    closeKey(key);
                    return;
                }

                // If buffer is drained, read next chunk from staging file
                if (!attachment.writeBuffer.hasRemaining()) {
                    attachment.writeBuffer.clear();
                    int read = attachment.stagingFileChannel.read(attachment.writeBuffer);
                    attachment.writeBuffer.flip();

                    if (read == -1 || attachment.bytesProcessed >= transfer.sizeBytes()) {
                        // All bytes sent to receiver
                        if (attachment.stagingFileChannel != null) {
                            attachment.stagingFileChannel.close();
                        }
                        attachment.state = ConnectionState.WAITING_FOR_RECEIVER_ACK;
                        attachment.ackBuffer = ByteBuffer.allocate(1);
                        key.interestOps(SelectionKey.OP_READ);
                        return;
                    }
                }

                int written = channel.write(attachment.writeBuffer);
                if (written > 0) {
                    attachment.bytesProcessed += written;
                }

                if (attachment.bytesProcessed >= transfer.sizeBytes() && !attachment.writeBuffer.hasRemaining()) {
                    if (attachment.stagingFileChannel != null) {
                        attachment.stagingFileChannel.close();
                    }
                    attachment.state = ConnectionState.WAITING_FOR_RECEIVER_ACK;
                    attachment.ackBuffer = ByteBuffer.allocate(1);
                    key.interestOps(SelectionKey.OP_READ);
                }
            }
            default -> {
            }
        }
    }

    private void sendHandshakeResponse(SelectionKey key, byte status, Runnable onSuccess) {
        ConnectionAttachment attachment = (ConnectionAttachment) key.attachment();
        attachment.state = ConnectionState.SENDING_HANDSHAKE_RESPONSE;
        attachment.responseBuffer = ByteBuffer.wrap(new byte[]{status});
        attachment.afterResponseAction = onSuccess;
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void sendHandshakeResponseAndClose(SelectionKey key, byte status) {
        sendHandshakeResponse(key, status, () -> closeKey(key));
    }

    private void closeKey(SelectionKey key) {
        try {
            if (key.attachment() instanceof ConnectionAttachment attachment) {
                if (attachment.stagingFileChannel != null && attachment.stagingFileChannel.isOpen()) {
                    try {
                        attachment.stagingFileChannel.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        try {
            if (selector != null) {
                selector.wakeup();
            }
        } catch (Exception ignored) {
        }

        if (workerThread != null) {
            workerThread.interrupt();
        }

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (selector != null && selector.isOpen()) {
                for (SelectionKey key : selector.keys()) {
                    closeKey(key);
                }
                selector.close();
            }
        } catch (IOException ignored) {
        }

        // Cleanup staging files
        try {
            if (Files.exists(stagingDirectory)) {
                try (var stream = Files.list(stagingDirectory)) {
                    stream.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    private enum ConnectionState {
        READING_HANDSHAKE_LENGTH,
        READING_HANDSHAKE_BODY,
        SENDING_HANDSHAKE_RESPONSE,
        RECEIVING_RAW_BYTES,
        RECEIVER_WAITING_FOR_STAGE,
        STREAMING_TO_RECEIVER,
        WAITING_FOR_RECEIVER_ACK
    }

    private static final class ConnectionAttachment {
        ConnectionState state = ConnectionState.READING_HANDSHAKE_LENGTH;
        final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        ByteBuffer bodyBuffer;
        ByteBuffer responseBuffer;
        Runnable afterResponseAction;

        ActiveTransfer transfer;

        FileChannel stagingFileChannel;
        MessageDigest digest;
        final ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        ByteBuffer writeBuffer;
        ByteBuffer ackBuffer;
        long bytesProcessed = 0;
    }
}
