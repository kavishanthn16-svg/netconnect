package com.netconnect.client.transfer;

import com.netconnect.common.contracts.FileOffer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX File Transfer view component (SRS Section 8.5 & Member Prompt 19).
 * Handles sending file offers, accepting/rejecting incoming offers, save path selection,
 * progress reporting, and execution of background NIO streaming.
 * Marshals all UI updates to the JavaFX Application Thread.
 */
public final class FileTransferPane extends VBox {

    private static final Logger LOGGER = Logger.getLogger(FileTransferPane.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "netconnect-client-transfer");
        thread.setDaemon(true);
        return thread;
    });

    // UI dispatcher hook (defaults to JavaFX Platform.runLater)
    private Consumer<Runnable> uiDispatcher = action -> {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException e) {
            action.run();
        }
    };

    // Callback hooks for control-plane integration
    private Consumer<FileOffer> onOfferRequested;
    private BiConsumer<String, Boolean> onDecisionRequested;

    // Outgoing transfer controls
    private final ComboBox<String> recipientCombo = new ComboBox<>();
    private final Label selectedFileLabel = new Label("No file selected");
    private final Label fileHashLabel = new Label("SHA-256: -");
    private final Button sendOfferButton = new Button("Send File Offer");
    private Path selectedFilePath;
    private String selectedFileSha256;
    private long selectedFileSize;

    // Active transfers map to track file path for SEND role
    private final ConcurrentHashMap<UUID, Path> pendingSendPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, IncomingOffer> pendingIncomingOffers = new ConcurrentHashMap<>();

    // Incoming offer container
    private final VBox incomingOffersBox = new VBox(8);

    // Progress controls
    private final ProgressBar progressBar = new ProgressBar(0.0);
    private final Label progressTextLabel = new Label("Idle");
    private final Label statusLabel = new Label("Ready");

    // Activity log
    private final ListView<String> activityLog = new ListView<>();

    public record IncomingOffer(UUID transferId, String fromUser, String fileName, long sizeBytes, String sha256) {}

    public FileTransferPane() {
        setSpacing(12);
        setPadding(new Insets(16));

        // Header
        Label heading = new Label("File Transfers (NIO Port 5052)");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label notice = new Label("NIO file channel is high-performance binary transport. Limitation: Plaintext in v1.");
        notice.setStyle("-fx-text-fill: #666666; -fx-font-style: italic;");

        // --- Outgoing Offer Section ---
        VBox sendBox = createSendSection();

        // --- Incoming Offers Section ---
        incomingOffersBox.setSpacing(6);
        Label incomingTitle = new Label("Incoming Offers");
        incomingTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        VBox incomingContainer = new VBox(6, incomingTitle, incomingOffersBox);
        incomingContainer.setStyle("-fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-padding: 8;");

        // --- Progress Section ---
        VBox progressBox = createProgressSection();

        // --- Log Section ---
        Label logTitle = new Label("Transfer Activity Log");
        logTitle.setStyle("-fx-font-weight: bold;");
        activityLog.setPrefHeight(120);
        VBox.setVgrow(activityLog, Priority.ALWAYS);

        getChildren().addAll(
                heading,
                notice,
                sendBox,
                incomingContainer,
                progressBox,
                new Separator(),
                logTitle,
                activityLog
        );

        log("FileTransferPane initialized.");
    }

    public void setUiDispatcher(Consumer<Runnable> dispatcher) {
        this.uiDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    private void dispatchUi(Runnable action) {
        uiDispatcher.accept(action);
    }

    private VBox createSendSection() {
        Label sectionTitle = new Label("Send File");
        sectionTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        recipientCombo.setPromptText("Select or enter recipient...");
        recipientCombo.setEditable(true);
        recipientCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(recipientCombo, Priority.ALWAYS);

        Button chooseFileButton = new Button("Browse File...");
        chooseFileButton.setOnAction(e -> handleBrowseFile());

        HBox recipientRow = new HBox(8, new Label("Recipient:"), recipientCombo, chooseFileButton);
        recipientRow.setAlignment(Pos.CENTER_LEFT);

        fileHashLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: #444444;");
        fileHashLabel.setWrapText(true);

        sendOfferButton.setDisable(true);
        sendOfferButton.setOnAction(e -> handleSendOffer());

        HBox actionRow = new HBox(12, sendOfferButton, selectedFileLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, sectionTitle, recipientRow, actionRow, fileHashLabel);
        box.setStyle("-fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-padding: 8;");
        return box;
    }

    private VBox createProgressSection() {
        Label progressTitle = new Label("Transfer Progress");
        progressTitle.setStyle("-fx-font-weight: bold;");

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setProgress(0.0);

        HBox labelsRow = new HBox(16, statusLabel, progressTextLabel);
        labelsRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, progressTitle, progressBar, labelsRow);
        box.setStyle("-fx-background-color: #f7f9fa; -fx-border-color: #e2e8f0; -fx-border-radius: 4; -fx-padding: 8;");
        return box;
    }

    private void handleBrowseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Transfer (Max 20 MiB)");
        File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) {
            return;
        }

        long size = file.length();
        if (size > 20L * 1024 * 1024) {
            showAlert("File Too Large", "Maximum supported transfer size is 20 MiB. Selected: " + formatBytes(size));
            return;
        }

        selectedFilePath = file.toPath();
        selectedFileSize = size;
        selectedFileLabel.setText(file.getName() + " (" + formatBytes(size) + ")");
        fileHashLabel.setText("Calculating SHA-256...");
        sendOfferButton.setDisable(true);

        backgroundExecutor.submit(() -> {
            try {
                String sha256 = calculateSha256(selectedFilePath);
                dispatchUi(() -> {
                    selectedFileSha256 = sha256;
                    fileHashLabel.setText("SHA-256: " + sha256);
                    sendOfferButton.setDisable(false);
                });
            } catch (Exception ex) {
                dispatchUi(() -> {
                    fileHashLabel.setText("Error hashing file: " + ex.getMessage());
                    sendOfferButton.setDisable(true);
                });
            }
        });
    }

    private void handleSendOffer() {
        String recipient = recipientCombo.getValue();
        if (recipient == null || recipient.isBlank()) {
            showAlert("Validation Error", "Please select or type a recipient.");
            return;
        }

        if (selectedFilePath == null || selectedFileSha256 == null) {
            showAlert("Validation Error", "Please choose a file and wait for hashing to complete.");
            return;
        }

        UUID transferId = UUID.randomUUID();
        FileOffer offer = new FileOffer(
                transferId,
                recipient.trim(),
                selectedFilePath.getFileName().toString(),
                selectedFileSize,
                selectedFileSha256
        );

        pendingSendPaths.put(transferId, selectedFilePath);
        log("Offering file '" + offer.fileName() + "' (" + formatBytes(offer.sizeBytes()) + ") to " + offer.toUser());
        statusLabel.setText("Offer sent, waiting for " + offer.toUser() + "...");

        if (onOfferRequested != null) {
            onOfferRequested.accept(offer);
        }
    }

    public void setOnOfferRequested(Consumer<FileOffer> callback) {
        this.onOfferRequested = callback;
    }

    public void setOnDecisionRequested(BiConsumer<String, Boolean> callback) {
        this.onDecisionRequested = callback;
    }

    public void onFileOffered(UUID transferId, String fromUser, String fileName, long sizeBytes, String sha256) {
        dispatchUi(() -> {
            IncomingOffer offer = new IncomingOffer(transferId, fromUser, fileName, sizeBytes, sha256);
            pendingIncomingOffers.put(transferId, offer);
            renderIncomingOfferCard(offer);
            log("Received file offer from " + fromUser + ": " + fileName + " (" + formatBytes(sizeBytes) + ")");
        });
    }

    private void renderIncomingOfferCard(IncomingOffer offer) {
        VBox card = new VBox(6);
        card.setId("offer-card-" + offer.transferId());
        card.setStyle("-fx-background-color: #edf2f7; -fx-padding: 8; -fx-border-color: #cbd5e0; -fx-border-radius: 4;");

        Label info = new Label("From: " + offer.fromUser() + " | File: " + offer.fileName() + " (" + formatBytes(offer.sizeBytes()) + ")");
        info.setStyle("-fx-font-weight: bold;");

        Label hash = new Label("SHA-256: " + offer.sha256());
        hash.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-text-fill: #555555;");

        Button acceptBtn = new Button("Accept");
        acceptBtn.setStyle("-fx-background-color: #2b6cb0; -fx-text-fill: white; -fx-font-weight: bold;");
        Button rejectBtn = new Button("Reject");

        HBox btnRow = new HBox(8, acceptBtn, rejectBtn);
        card.getChildren().addAll(info, hash, btnRow);
        incomingOffersBox.getChildren().add(card);

        rejectBtn.setOnAction(e -> {
            incomingOffersBox.getChildren().remove(card);
            pendingIncomingOffers.remove(offer.transferId());
            log("Rejected file offer " + offer.fileName() + " from " + offer.fromUser());
            if (onDecisionRequested != null) {
                onDecisionRequested.accept(offer.transferId().toString(), false);
            }
        });

        acceptBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save Received File As");
            chooser.setInitialFileName(offer.fileName());
            File destinationFile = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);

            if (destinationFile == null) {
                return;
            }

            if (destinationFile.exists()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Confirm Overwrite");
                confirm.setHeaderText("File already exists");
                confirm.setContentText("Destination file '" + destinationFile.getName() + "' already exists. Overwrite?");
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) {
                    return;
                }
            }

            incomingOffersBox.getChildren().remove(card);
            log("Accepted file offer " + offer.fileName() + " -> Save to " + destinationFile.getAbsolutePath());
            pendingSendPaths.put(offer.transferId(), destinationFile.toPath());

            if (onDecisionRequested != null) {
                onDecisionRequested.accept(offer.transferId().toString(), true);
            }
        });
    }

    public void onFileReady(UUID transferId, String ticket, String host, int port, String role, long expiresAt) {
        dispatchUi(() -> {
            log("FILE_READY received: transfer=" + transferId + ", role=" + role + ", host=" + host + ":" + port);
            statusLabel.setText("Starting NIO transfer (" + role + ")...");
            updateProgress(0, 100);
        });

        backgroundExecutor.submit(() -> {
            try {
                if ("SEND".equalsIgnoreCase(role)) {
                    Path sendPath = pendingSendPaths.get(transferId);
                    if (sendPath == null || !Files.exists(sendPath)) {
                        throw new IOException("Local file to send not found for transfer " + transferId);
                    }
                    dispatchUi(() -> statusLabel.setText("Sending raw bytes via NIO..."));

                    NioFileSender.sendFile(host, port, transferId, ticket, sendPath, (sent, totalBytes) -> {
                        dispatchUi(() -> {
                            updateProgress(sent, totalBytes);
                            progressTextLabel.setText(formatBytes(sent) + " / " + formatBytes(totalBytes));
                        });
                    });

                    dispatchUi(() -> {
                        statusLabel.setText("File sent. Awaiting recipient verification...");
                        log("All bytes streamed to server. Waiting for FILE_RESULT...");
                    });

                } else if ("RECEIVE".equalsIgnoreCase(role)) {
                    IncomingOffer offer = pendingIncomingOffers.get(transferId);
                    Path destination = pendingSendPaths.get(transferId);

                    long expectedBytes = offer != null ? offer.sizeBytes() : 0;
                    String expectedHash = offer != null ? offer.sha256() : "";

                    if (destination == null) {
                        destination = Path.of(offer != null ? offer.fileName() : "downloaded_file");
                    }

                    dispatchUi(() -> statusLabel.setText("Receiving raw bytes via NIO..."));

                    NioFileReceiver.receiveFile(host, port, transferId, ticket, expectedBytes, expectedHash, destination,
                            (recv, totalBytes) -> {
                                dispatchUi(() -> {
                                    updateProgress(recv, totalBytes);
                                    progressTextLabel.setText(formatBytes(recv) + " / " + formatBytes(totalBytes));
                                });
                            });

                    dispatchUi(() -> {
                        statusLabel.setText("File received and SHA-256 verified!");
                        log("File received successfully and saved to: " + pendingSendPaths.get(transferId));
                    });
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "NIO Transfer failed for " + transferId, ex);
                dispatchUi(() -> {
                    statusLabel.setText("Transfer failed: " + ex.getMessage());
                    log("Transfer error (" + role + "): " + ex.getMessage());
                    showAlert("Transfer Failed", ex.getMessage());
                });
            }
        });
    }

    public void onFileResult(UUID transferId, String status, String code, String message) {
        dispatchUi(() -> {
            boolean success = "SUCCESS".equalsIgnoreCase(status);
            statusLabel.setText("Transfer " + status + ": " + message);
            if (success) {
                updateProgress(100, 100);
                progressTextLabel.setText("100% Completed");
            }
            log("FILE_RESULT: " + status + " [" + code + "] - " + message);
            pendingSendPaths.remove(transferId);
            pendingIncomingOffers.remove(transferId);
        });
    }

    public void onError(String code, String message) {
        dispatchUi(() -> {
            statusLabel.setText("Error: " + code);
            log("ERROR: [" + code + "] " + message);
            showAlert("Server Error [" + code + "]", message);
        });
    }

    public void updateOnlineUsers(List<String> users) {
        dispatchUi(() -> {
            String current = recipientCombo.getValue();
            recipientCombo.getItems().setAll(users);
            if (current != null) {
                recipientCombo.setValue(current);
            }
        });
    }

    public void resetForConnection() {
        dispatchUi(() -> {
            recipientCombo.getItems().clear();
            incomingOffersBox.getChildren().clear();
            pendingSendPaths.clear();
            pendingIncomingOffers.clear();
            progressBar.setProgress(0.0);
            progressTextLabel.setText("Idle");
            statusLabel.setText("Ready");
            log("Reset transfer state for new connection.");
        });
    }

    private void updateProgress(long current, long total) {
        if (total == 0) {
            progressBar.setProgress(1.0);
            progressTextLabel.setText("0 B / 0 B (100%)");
        } else {
            double fraction = (double) current / (double) total;
            progressBar.setProgress(Math.min(1.0, fraction));
            int pct = (int) (fraction * 100);
            progressTextLabel.setText(formatBytes(current) + " / " + formatBytes(total) + " (" + pct + "%)");
        }
    }

    private void log(String message) {
        String entry = "[" + LocalTime.now().format(TIME_FMT) + "] " + message;
        activityLog.getItems().add(entry);
        activityLog.scrollTo(activityLog.getItems().size() - 1);
    }

    private void showAlert(String title, String content) {
        try {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.show();
        } catch (Exception ignored) {
            // Headless / non-GUI environments
        }
    }

    private static String calculateSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                digest.update(buf, 0, r);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        return String.format("%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }
}
