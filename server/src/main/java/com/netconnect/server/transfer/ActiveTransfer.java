package com.netconnect.server.transfer;

import com.netconnect.common.contracts.Session;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracks the state machine of an active file transfer on the server.
 * Lifecycle: OFFERED -> ACCEPTED -> READY -> SENDING -> STAGED -> RECEIVING -> VERIFIED -> COMPLETED
 * or OFFERED -> REJECTED; active phase -> FAILED / EXPIRED.
 */
public final class ActiveTransfer {

    public enum State {
        OFFERED,
        ACCEPTED,
        READY,
        SENDING,
        STAGED,
        RECEIVING,
        VERIFIED,
        COMPLETED,
        REJECTED,
        FAILED,
        EXPIRED
    }

    private final UUID transferId;
    private final Session sender;
    private final Session recipient;
    private final String fileName;
    private final long sizeBytes;
    private final String expectedSha256;
    private final long createdAtMillis;

    private volatile State state;
    private volatile Path stagingFile;
    private volatile SocketChannel senderChannel;
    private volatile SocketChannel receiverChannel;
    private volatile String failureReason;

    public ActiveTransfer(UUID transferId, Session sender, Session recipient,
                          String fileName, long sizeBytes, String expectedSha256,
                          long createdAtMillis) {
        this.transferId = Objects.requireNonNull(transferId);
        this.sender = Objects.requireNonNull(sender);
        this.recipient = Objects.requireNonNull(recipient);
        this.fileName = Objects.requireNonNull(fileName);
        this.sizeBytes = sizeBytes;
        this.expectedSha256 = Objects.requireNonNull(expectedSha256).toLowerCase();
        this.createdAtMillis = createdAtMillis;
        this.state = State.OFFERED;
    }

    public UUID transferId() {
        return transferId;
    }

    public Session sender() {
        return sender;
    }

    public Session recipient() {
        return recipient;
    }

    public String fileName() {
        return fileName;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public State state() {
        return state;
    }

    public synchronized void transitionTo(State newState) {
        this.state = newState;
    }

    public Path stagingFile() {
        return stagingFile;
    }

    public synchronized void setStagingFile(Path stagingFile) {
        this.stagingFile = stagingFile;
    }

    public SocketChannel senderChannel() {
        return senderChannel;
    }

    public synchronized void setSenderChannel(SocketChannel senderChannel) {
        this.senderChannel = senderChannel;
    }

    public SocketChannel receiverChannel() {
        return receiverChannel;
    }

    public synchronized void setReceiverChannel(SocketChannel receiverChannel) {
        this.receiverChannel = receiverChannel;
    }

    public String failureReason() {
        return failureReason;
    }

    public synchronized void fail(String reason) {
        this.state = State.FAILED;
        this.failureReason = reason;
        cleanupStagingFile();
    }

    public synchronized void cleanupStagingFile() {
        if (stagingFile != null) {
            try {
                Files.deleteIfExists(stagingFile);
            } catch (IOException ignored) {
            }
            stagingFile = null;
        }
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.REJECTED
                || state == State.FAILED || state == State.EXPIRED;
    }
}
