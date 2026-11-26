package com.netconnect.client.transfer;

import com.netconnect.common.contracts.FileOffer;
import javafx.application.Platform;
import com.netconnect.client.ui.Ui;
import static com.netconnect.client.ui.Ui.Tone.*;
import javafx.scene.control.TitledPane;
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
    private final Label fileHashLabel = new Label("Choose a file to calculate its SHA-256 fingerprint.");
    private final Button sendOfferButton = new Button("Send File Request");
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
    private final Label progressTextLabel = new Label("No transfer in progress");
    private final Label statusLabel = new Label("Ready");

    // Activity log
    private final ListView<String> activityLog = new ListView<>();
    // Presentation-only completion tracking prevents late progress callbacks from hiding a failure.
    private final java.util.Set<UUID> failedTransfers = new java.util.HashSet<>();
    private final java.util.Set<UUID> receivingTransfers = new java.util.HashSet<>();
    private final java.util.Set<UUID> verifiedDownloads = new java.util.HashSet<>();
    private final java.util.Set<UUID> completedTransfers = new java.util.HashSet<>();

    public record IncomingOffer(UUID transferId, String fromUser, String fileName, long sizeBytes, String sha256) {}

    public FileTransferPane() {
        getStyleClass().add("file-transfers");
        setSpacing(10);
        setPadding(new Insets(20));

        // Header
        Label heading = new Label("Share a file");
        heading.getStyleClass().add("section-title");
        Label notice = new Label();
        Ui.feedback(notice, WARNING, "Files are not encrypted during transfer. Your sign-in and file requests use a secure connection.");
        Ui.feedback(statusLabel, NEUTRAL, "Ready to share a file.");
        Ui.tone(progressBar, NEUTRAL);
        notice.setWrapText(true);
        statusLabel.setWrapText(true);
        progressTextLabel.setWrapText(true);
        activityLog.setPlaceholder(new Label("No transfer activity yet"));
        activityLog.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                Label text = new Label(empty ? "" : item);
                text.setWrapText(true);
                text.prefWidthProperty().bind(list.widthProperty().subtract(44));
                text.setMinWidth(0);
                setGraphic(empty ? null : text);
            }
        });

        // --- Outgoing Offer Section ---
        VBox sendBox = createSendSection();

        // --- Incoming File Requests Section ---
        incomingOffersBox.setSpacing(6);
        Label noOffers = new Label("No incoming requests");
        noOffers.getStyleClass().add("muted");
        noOffers.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(incomingOffersBox.getChildren()));
        noOffers.managedProperty().bind(noOffers.visibleProperty());
        Label incomingTitle = new Label("Incoming File Requests");
        incomingTitle.getStyleClass().add("section-title");
        VBox incomingContainer = new VBox(6, incomingTitle, noOffers, incomingOffersBox);
        incomingContainer.getStyleClass().add("card");

        // --- Progress Section ---
        VBox progressBox = createProgressSection();

        // --- Log Section ---
        Label logTitle = new Label("Transfer History");
        logTitle.getStyleClass().add("section-title");
        activityLog.setPrefHeight(150);
        activityLog.setMinHeight(100);
        activityLog.setAccessibleText("Transfer history");
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

        sendOfferButton.getStyleClass().add("primary");
    }

    public void setUiDispatcher(Consumer<Runnable> dispatcher) {
        this.uiDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    private void dispatchUi(Runnable action) {
        uiDispatcher.accept(action);
    }

    private VBox createSendSection() {
        Label sectionTitle = new Label("Send a file");
        sectionTitle.getStyleClass().add("section-title");

        recipientCombo.setPromptText("Select or enter recipient...");
        recipientCombo.setEditable(true);
        recipientCombo.setMaxWidth(Double.MAX_VALUE);
        recipientCombo.setMinWidth(120);
        recipientCombo.setAccessibleText("File recipient");
        HBox.setHgrow(recipientCombo, Priority.ALWAYS);

        Button chooseFileButton = new Button("Browse File...");
        chooseFileButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        chooseFileButton.setOnAction(e -> handleBrowseFile());

        Label recipientLabel = new Label("Recipient:");
        recipientLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        sendOfferButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox recipientRow = new HBox(8, recipientLabel, recipientCombo, sendOfferButton);
        recipientRow.setAlignment(Pos.CENTER_LEFT);

        fileHashLabel.getStyleClass().add("hash");
        fileHashLabel.setWrapText(true);

        sendOfferButton.setDisable(true);
        sendOfferButton.setOnAction(e -> handleSendOffer());

        selectedFileLabel.setWrapText(true);
        selectedFileLabel.setMinWidth(0);
        HBox.setHgrow(selectedFileLabel, Priority.ALWAYS);
        HBox fileRow = new HBox(12, chooseFileButton, selectedFileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        TitledPane fingerprint = new TitledPane("File verification details", fileHashLabel);
        fingerprint.setExpanded(false);
        fingerprint.setAnimated(false);
        VBox box = new VBox(8, sectionTitle, fileRow, recipientRow, fingerprint);
        box.getStyleClass().add("transfer-card");
        box.getStyleClass().add("card");
        return box;
    }

    private VBox createProgressSection() {
        Label progressTitle = new Label("Transfer Progress");
        progressTitle.getStyleClass().add("section-title");

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setProgress(0.0);

        VBox labelsRow = new VBox(4, statusLabel, progressTextLabel);

        VBox box = new VBox(6, progressTitle, progressBar, labelsRow);
        box.getStyleClass().add("card");
        return box;
    }

    private void handleBrowseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Transfer (up to 20 MiB)");
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
        fileHashLabel.setText("Calculating the file fingerprint...");
        Ui.feedback(statusLabel, INFORMATION, "Checking your file...");
        sendOfferButton.setDisable(true);

        backgroundExecutor.submit(() -> {
            try {
                String sha256 = calculateSha256(selectedFilePath);
                dispatchUi(() -> {
                    selectedFileSha256 = sha256;
                    fileHashLabel.setText("SHA-256: " + sha256);
                    sendOfferButton.setDisable(false);
                    Ui.feedback(statusLabel, NEUTRAL, "File ready. Choose a recipient and send a request.");
                });
            } catch (Exception ex) {
                dispatchUi(() -> {
                    fileHashLabel.setText("Could not read this file.");
                    Ui.feedback(statusLabel, ERROR, "Could not read this file. Choose another file and try again.");
                    sendOfferButton.setDisable(true);
                });
            }
        });
    }

    private void handleSendOffer() {
        String recipient = recipientCombo.isEditable()
                ? recipientCombo.getEditor().getText() : recipientCombo.getValue();
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
        log("Requested to send " + offer.fileName() + " to " + offer.toUser() + ".");
        Ui.feedback(statusLabel, INFORMATION, "Request sent. Waiting for " + offer.toUser() + " to accept.");
        Ui.tone(progressBar, NEUTRAL);
        progressBar.setProgress(0);
        progressTextLabel.setText("Waiting for a response");

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
            log(fromUser + " wants to send you " + fileName + " (" + formatBytes(sizeBytes) + ").");
            Ui.feedback(statusLabel, INFORMATION, "New file request from " + fromUser + ". Accept or decline below.");
        });
    }

    private void renderIncomingOfferCard(IncomingOffer offer) {
        VBox card = new VBox(6);
        card.setId("offer-card-" + offer.transferId());
        card.getStyleClass().add("card");

        Label info = new Label(offer.fileName() + " - " + formatBytes(offer.sizeBytes()));
        Label sender = new Label("From " + offer.fromUser());
        sender.getStyleClass().add("muted");
        sender.setWrapText(true);
        info.getStyleClass().add("section-title");

        Label hash = new Label("SHA-256: " + offer.sha256());
        hash.getStyleClass().add("hash");
        hash.setWrapText(true);
        info.setWrapText(true);

        Button acceptBtn = new Button("Accept");
        acceptBtn.getStyleClass().add("primary");
        Button rejectBtn = new Button("Decline");

        HBox btnRow = new HBox(8, acceptBtn, rejectBtn);
        TitledPane fingerprint = new TitledPane("File verification details", hash);
        fingerprint.setAnimated(false);
        fingerprint.setExpanded(false);
        card.getStyleClass().add("transfer-card");
        card.getChildren().addAll(info, sender, fingerprint, btnRow);
        incomingOffersBox.getChildren().add(card);

        rejectBtn.setOnAction(e -> {
            incomingOffersBox.getChildren().remove(card);
            pendingIncomingOffers.remove(offer.transferId());
            log("Declined " + offer.fileName() + " from " + offer.fromUser() + ".");
            Ui.feedback(statusLabel, WARNING, "File request declined.");
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
                Label overwrite = new Label();
                Ui.feedback(overwrite, WARNING, "Replace the existing file '" + destinationFile.getName() + "'? This will overwrite its contents.");
                overwrite.setMaxWidth(420);
                confirm.getDialogPane().setContent(overwrite);
                styleDialog(confirm);
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) {
                    return;
                }
            }

            incomingOffersBox.getChildren().remove(card);
            log("Accepted " + offer.fileName() + " from " + offer.fromUser() + ".");
            Ui.feedback(statusLabel, INFORMATION, "Request accepted. Preparing your file...");
            pendingSendPaths.put(offer.transferId(), destinationFile.toPath());

            if (onDecisionRequested != null) {
                onDecisionRequested.accept(offer.transferId().toString(), true);
            }
        });
    }

    public void onFileReady(UUID transferId, String ticket, String host, int port, String role, long expiresAt) {
        dispatchUi(() -> {
            if ("RECEIVE".equalsIgnoreCase(role)) receivingTransfers.add(transferId);
            log("Request accepted. Preparing your file...");
            Ui.feedback(statusLabel, INFORMATION, "Request accepted. Preparing your file...");
            Ui.tone(progressBar, INFORMATION);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressTextLabel.setText("Waiting for file data...");
        });

        backgroundExecutor.submit(() -> {
            try {
                if ("SEND".equalsIgnoreCase(role)) {
                    Path sendPath = pendingSendPaths.get(transferId);
                    if (sendPath == null || !Files.exists(sendPath)) {
                        throw new IOException("Local file to send not found for transfer " + transferId);
                    }
                    dispatchUi(() -> { if (!failedTransfers.contains(transferId) && !completedTransfers.contains(transferId))
                        Ui.feedback(statusLabel, INFORMATION, "Sending your file..."); });

                    NioFileSender.sendFile(host, port, transferId, ticket, sendPath, (sent, totalBytes) -> {
                        dispatchUi(() -> {
                            if (failedTransfers.contains(transferId) || completedTransfers.contains(transferId)) return;
                            updateProgress(sent, totalBytes);
                            progressTextLabel.setText(formatBytes(sent) + " / " + formatBytes(totalBytes));
                        });
                    });

                    dispatchUi(() -> {
                        if (failedTransfers.contains(transferId) || completedTransfers.contains(transferId)) return;
                        Ui.feedback(statusLabel, INFORMATION, "File uploaded. Waiting for delivery confirmation...");
                        log("File uploaded. Waiting for delivery confirmation.");
                    });

                } else if ("RECEIVE".equalsIgnoreCase(role)) {
                    IncomingOffer offer = pendingIncomingOffers.get(transferId);
                    Path destination = pendingSendPaths.get(transferId);

                    long expectedBytes = offer != null ? offer.sizeBytes() : 0;
                    String expectedHash = offer != null ? offer.sha256() : "";

                    if (destination == null) {
                        destination = Path.of(offer != null ? offer.fileName() : "downloaded_file");
                    }

                    dispatchUi(() -> { if (!failedTransfers.contains(transferId))
                        Ui.feedback(statusLabel, INFORMATION, "Receiving your file..."); });

                    NioFileReceiver.receiveFile(host, port, transferId, ticket, expectedBytes, expectedHash, destination,
                            (recv, totalBytes) -> {
                                dispatchUi(() -> {
                                    if (failedTransfers.contains(transferId) || completedTransfers.contains(transferId)) return;
                                    updateProgress(recv, totalBytes);
                                    progressTextLabel.setText(formatBytes(recv) + " / " + formatBytes(totalBytes));
                                });
                            });

                    dispatchUi(() -> {
                        if (failedTransfers.contains(transferId)) return;
                        verifiedDownloads.add(transferId);
                        completedTransfers.add(transferId);
                        Ui.feedback(statusLabel, SUCCESS, "File received successfully. File verification passed.");
                        Ui.tone(progressBar, SUCCESS);
                        progressBar.setProgress(1.0);
                        progressTextLabel.setText("100% complete");
                        log("File received successfully. File verification passed.");
                    });
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "NIO Transfer failed for " + transferId, ex);
                dispatchUi(() -> {
                    failedTransfers.add(transferId);
                    Ui.feedback(statusLabel, ERROR, "Transfer failed. Check your connection and try again.");
                    Ui.tone(progressBar, ERROR);
                    log("Transfer could not finish. Check the connection and retry.");
                    showAlert("Transfer failed", "The file could not be transferred. Check the connection and try again.");
                });
            }
        });
    }

    public void onFileResult(UUID transferId, String status, String code, String message) {
        dispatchUi(() -> {
            // Keep protocol diagnostics out of normal user feedback.
            LOGGER.log(Level.FINE, "File result: status={0}, code={1}", new Object[]{status, code});
            boolean success = "SUCCESS".equalsIgnoreCase(status);
            boolean declined = "REJECTED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status);
            if (success && !failedTransfers.contains(transferId)) {
                if (receivingTransfers.contains(transferId) && !verifiedDownloads.contains(transferId)) {
                    Ui.feedback(statusLabel, INFORMATION, "File delivered. Checking your received file...");
                    Ui.tone(progressBar, INFORMATION);
                } else {
                    completedTransfers.add(transferId);
                    String text = receivingTransfers.contains(transferId)
                            ? "File received successfully. File verification passed." : "File sent successfully.";
                    Ui.feedback(statusLabel, SUCCESS, text);
                    Ui.tone(progressBar, SUCCESS);
                    progressBar.setProgress(1.0);
                    progressTextLabel.setText("100% complete");
                    log(text);
                }
            } else if (!success) {
                failedTransfers.add(transferId);
                Ui.Tone tone = declined ? WARNING : ERROR;
                String text = declined ? "File request declined or cancelled." : Ui.transferError(code);
                Ui.feedback(statusLabel, tone, text);
                Ui.tone(progressBar, tone);
                progressTextLabel.setText(declined ? "Transfer stopped" : "Transfer incomplete");
                log(text);
            }
            pendingSendPaths.remove(transferId);
            pendingIncomingOffers.remove(transferId);
        });
    }

    public void onError(String code, String message) {
        dispatchUi(() -> {
            LOGGER.log(Level.FINE, "File request error: {0}", code);
            String text = Ui.transferError(code);
            progressTextLabel.setText("Request unsuccessful");
            Ui.feedback(statusLabel, ERROR, text);
            Ui.tone(progressBar, ERROR);
            log(text);
            showAlert("File request unsuccessful", text);
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
            failedTransfers.clear();
            receivingTransfers.clear();
            verifiedDownloads.clear();
            completedTransfers.clear();
            progressTextLabel.setText("No transfer in progress");
            Ui.feedback(statusLabel, NEUTRAL, "Ready to share a file.");
            Ui.tone(progressBar, NEUTRAL);
            log("Ready for a new connection.");
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
        Ui.feedback(statusLabel, ERROR, content);
        Ui.tone(progressBar, ERROR);
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            Label feedback = new Label();
            Ui.feedback(feedback, ERROR, content);
            feedback.setMaxWidth(420);
            alert.getDialogPane().setContent(feedback);
            styleDialog(alert);
            alert.show();
        } catch (Exception ignored) {
            // Headless / non-GUI environments
        }
    }

    private void styleDialog(Alert alert) {
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
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
