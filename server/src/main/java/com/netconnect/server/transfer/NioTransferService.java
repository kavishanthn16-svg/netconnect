package com.netconnect.server.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.contracts.ControlGateway;
import com.netconnect.common.contracts.FileOffer;
import com.netconnect.common.contracts.Session;
import com.netconnect.common.contracts.SessionRegistry;
import com.netconnect.common.contracts.TransferService;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.ErrorCode;
import com.netconnect.common.protocol.EventType;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server transfer service coordinating control plane file offers and decisions,
 * one-time ticket lifecycle, and delegation to the NIO File Server (port 5052).
 */
public final class NioTransferService implements TransferService, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(NioTransferService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final long TICKET_EXPIRY_MILLIS = 60_000L; // 60 seconds

    private final NetConnectConfig config;
    private final SessionRegistry sessionRegistry;
    private final ControlGateway controlGateway;
    private final Clock clock;
    private final TicketRegistry ticketRegistry;
    private final ConcurrentMap<UUID, ActiveTransfer> activeTransfers = new ConcurrentHashMap<>();
    private final NioFileServer nioFileServer;

    public NioTransferService(NetConnectConfig config,
                              SessionRegistry sessionRegistry,
                              ControlGateway controlGateway) throws IOException {
        this(config, sessionRegistry, controlGateway, Clock.systemUTC(),
                Paths.get(System.getProperty("java.io.tmpdir"), "netconnect-staging"));
    }

    public NioTransferService(NetConnectConfig config,
                              SessionRegistry sessionRegistry,
                              ControlGateway controlGateway,
                              Clock clock,
                              Path stagingDirectory) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.controlGateway = Objects.requireNonNull(controlGateway, "controlGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticketRegistry = new TicketRegistry();
        this.nioFileServer = new NioFileServer(
                config.transferPort(),
                this.ticketRegistry,
                this.activeTransfers,
                stagingDirectory,
                this.clock,
                new NioFileServer.TransferListener() {
                    @Override
                    public void onCompleted(ActiveTransfer transfer) {
                        handleTransferSuccess(transfer);
                    }

                    @Override
                    public void onFailed(ActiveTransfer transfer, String errorCode, String message) {
                        handleTransferFailure(transfer, errorCode, message);
                    }
                }
        );
    }

    public void start() throws IOException {
        nioFileServer.start();
    }

    public int nioPort() {
        return nioFileServer.boundPort();
    }

    public TicketRegistry ticketRegistry() {
        return ticketRegistry;
    }

    public ConcurrentMap<UUID, ActiveTransfer> activeTransfers() {
        return activeTransfers;
    }

    @Override
    public void offer(Session sender, FileOffer offer) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(offer, "offer");

        if (sender.sessionId() == null || sender.sessionId().isBlank()) {
            sendError(sender, ErrorCode.NOT_AUTHENTICATED, "Sender is not authenticated");
            return;
        }

        // Sanitize file name
        String sanitizedName = TransferFileNameSanitizer.sanitize(offer.fileName());

        // Validate recipient is online
        Optional<Session> recipientOpt = sessionRegistry.getByDisplayName(offer.toUser());
        if (recipientOpt.isEmpty()) {
            sendError(sender, ErrorCode.USER_OFFLINE, "Recipient '" + offer.toUser() + "' is offline");
            return;
        }

        Session recipient = recipientOpt.get();
        if (recipient.sessionId().equals(sender.sessionId())) {
            sendError(sender, ErrorCode.INVALID_REQUEST, "Cannot send file to yourself");
            return;
        }

        // Rule: One transfer at a time per sender
        boolean senderBusy = activeTransfers.values().stream()
                .anyMatch(t -> !t.isTerminal() && t.sender().sessionId().equals(sender.sessionId()));
        if (senderBusy) {
            sendError(sender, ErrorCode.SERVER_BUSY, "Sender already has an active file transfer");
            return;
        }

        // Rule: Reject duplicate live transferId
        if (activeTransfers.containsKey(offer.transferId())) {
            sendError(sender, ErrorCode.INVALID_REQUEST, "Duplicate transferId");
            return;
        }

        ActiveTransfer activeTransfer = new ActiveTransfer(
                offer.transferId(),
                sender,
                recipient,
                sanitizedName,
                offer.sizeBytes(),
                offer.sha256(),
                clock.millis()
        );
        activeTransfers.put(offer.transferId(), activeTransfer);

        // Send FILE_OFFERED to recipient
        ObjectNode offeredPayload = MAPPER.createObjectNode()
                .put("transferId", offer.transferId().toString())
                .put("fromUser", sender.displayName())
                .put("fileName", sanitizedName)
                .put("sizeBytes", offer.sizeBytes())
                .put("sha256", offer.sha256());

        Envelope offeredEnvelope = Envelope.request(
                EventType.FILE_OFFERED,
                UUID.randomUUID(),
                recipient.sessionId(),
                offeredPayload
        );
        controlGateway.send(recipient, offeredEnvelope);

        LOGGER.log(Level.INFO, "File offer sent: from={0}, to={1}, file={2}, size={3}",
                new Object[]{sender.displayName(), recipient.displayName(), sanitizedName, offer.sizeBytes()});
    }

    @Override
    public void decide(Session recipient, String transferIdStr, boolean accepted) {
        Objects.requireNonNull(recipient, "recipient");
        if (transferIdStr == null || transferIdStr.isBlank()) {
            sendError(recipient, ErrorCode.INVALID_REQUEST, "Invalid transferId");
            return;
        }

        final UUID transferId;
        try {
            transferId = UUID.fromString(transferIdStr);
        } catch (IllegalArgumentException e) {
            sendError(recipient, ErrorCode.INVALID_REQUEST, "Invalid transferId UUID format");
            return;
        }

        ActiveTransfer activeTransfer = activeTransfers.get(transferId);
        if (activeTransfer == null || activeTransfer.isTerminal()) {
            sendError(recipient, ErrorCode.TRANSFER_EXPIRED, "Transfer not found or expired");
            return;
        }

        if (!activeTransfer.recipient().sessionId().equals(recipient.sessionId())) {
            sendError(recipient, ErrorCode.ACCESS_DENIED, "Not authorized to decide for this transfer");
            return;
        }

        if (activeTransfer.state() != ActiveTransfer.State.OFFERED) {
            sendError(recipient, ErrorCode.INVALID_REQUEST, "Transfer is not in OFFERED state");
            return;
        }

        if (!accepted) {
            activeTransfer.transitionTo(ActiveTransfer.State.REJECTED);
            activeTransfer.cleanupStagingFile();
            ticketRegistry.invalidateForTransfer(transferId);

            // Send FILE_RESULT to sender
            sendResult(activeTransfer.sender(), transferId, "REJECTED",
                    ErrorCode.TRANSFER_REJECTED.name(), "Transfer rejected by recipient");
            // Send FILE_RESULT to recipient
            sendResult(recipient, transferId, "REJECTED",
                    ErrorCode.TRANSFER_REJECTED.name(), "Transfer rejected");

            activeTransfers.remove(transferId);
            LOGGER.log(Level.INFO, "Transfer rejected: id={0}", transferId);
            return;
        }

        // Accepted: issue tickets and send FILE_READY
        activeTransfer.transitionTo(ActiveTransfer.State.ACCEPTED);

        String senderTicket = UUID.randomUUID().toString();
        String receiverTicket = UUID.randomUUID().toString();
        long expiresAt = clock.millis() + TICKET_EXPIRY_MILLIS;

        ticketRegistry.register(new TransferTicket(
                senderTicket,
                transferId,
                activeTransfer.sender().sessionId(),
                "SEND",
                expiresAt
        ));
        ticketRegistry.register(new TransferTicket(
                receiverTicket,
                transferId,
                recipient.sessionId(),
                "RECEIVE",
                expiresAt
        ));

        activeTransfer.transitionTo(ActiveTransfer.State.READY);

        String serverHost = config.serverHost();
        int transferPort = nioFileServer.boundPort();

        // Send FILE_READY to sender
        ObjectNode senderReadyPayload = MAPPER.createObjectNode()
                .put("transferId", transferId.toString())
                .put("ticket", senderTicket)
                .put("host", serverHost)
                .put("port", transferPort)
                .put("role", "SEND")
                .put("expiresAt", expiresAt);
        controlGateway.send(activeTransfer.sender(), Envelope.request(
                EventType.FILE_READY,
                UUID.randomUUID(),
                activeTransfer.sender().sessionId(),
                senderReadyPayload
        ));

        // Send FILE_READY to recipient
        ObjectNode receiverReadyPayload = MAPPER.createObjectNode()
                .put("transferId", transferId.toString())
                .put("ticket", receiverTicket)
                .put("host", serverHost)
                .put("port", transferPort)
                .put("role", "RECEIVE")
                .put("expiresAt", expiresAt);
        controlGateway.send(recipient, Envelope.request(
                EventType.FILE_READY,
                UUID.randomUUID(),
                recipient.sessionId(),
                receiverReadyPayload
        ));

        LOGGER.log(Level.INFO, "Transfer accepted, FILE_READY dispatched: id={0}", transferId);
    }

    public void handleDisconnect(String sessionId) {
        if (sessionId == null) {
            return;
        }
        ticketRegistry.invalidateForSession(sessionId);

        for (ActiveTransfer transfer : activeTransfers.values()) {
            if (!transfer.isTerminal()) {
                if (transfer.sender().sessionId().equals(sessionId)
                        || transfer.recipient().sessionId().equals(sessionId)) {
                    LOGGER.info("Aborting transfer " + transfer.transferId() + " due to session disconnect: " + sessionId);
                    transfer.fail("Session disconnected");
                    ticketRegistry.invalidateForTransfer(transfer.transferId());

                    // Notify remaining party
                    Session remaining = transfer.sender().sessionId().equals(sessionId)
                            ? transfer.recipient() : transfer.sender();
                    sendResult(remaining, transfer.transferId(), "FAILED",
                            ErrorCode.USER_OFFLINE.name(), "Peer disconnected during transfer");

                    activeTransfers.remove(transfer.transferId());
                }
            }
        }
    }

    private void handleTransferSuccess(ActiveTransfer transfer) {
        sendResult(transfer.sender(), transfer.transferId(), "SUCCESS", "SUCCESS",
                "File transfer completed successfully");
        sendResult(transfer.recipient(), transfer.transferId(), "SUCCESS", "SUCCESS",
                "File transfer completed successfully");
        activeTransfers.remove(transfer.transferId());
    }

    private void handleTransferFailure(ActiveTransfer transfer, String errorCode, String message) {
        sendResult(transfer.sender(), transfer.transferId(), "FAILED", errorCode, message);
        sendResult(transfer.recipient(), transfer.transferId(), "FAILED", errorCode, message);
        activeTransfers.remove(transfer.transferId());
    }

    private void sendResult(Session session, UUID transferId, String status, String code, String message) {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("transferId", transferId.toString())
                .put("status", status)
                .put("code", code)
                .put("message", message);
        Envelope envelope = Envelope.request(
                EventType.FILE_RESULT,
                UUID.randomUUID(),
                session.sessionId(),
                payload
        );
        try {
            controlGateway.send(session, envelope);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send FILE_RESULT to " + session.displayName(), e);
        }
    }

    private void sendError(Session session, ErrorCode code, String message) {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("code", code.name())
                .put("message", message);
        Envelope envelope = Envelope.request(
                EventType.ERROR,
                UUID.randomUUID(),
                session.sessionId(),
                payload
        );
        try {
            controlGateway.send(session, envelope);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send ERROR to " + session.displayName(), e);
        }
    }

    @Override
    public void close() {
        activeTransfers.values().forEach(ActiveTransfer::cleanupStagingFile);
        activeTransfers.clear();
        nioFileServer.close();
    }
}
