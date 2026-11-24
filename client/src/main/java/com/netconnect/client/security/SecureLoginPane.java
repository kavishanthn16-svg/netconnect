package com.netconnect.client.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.EventType;
import com.netconnect.common.protocol.Envelope;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.util.function.Consumer;

/** Reusable security-only view; exposes authentication to a future shell without owning it. */
public final class SecureLoginPane extends VBox {
    public SecureLoginPane(SecureLoginConnection connection, NetConnectConfig config) {
        this(connection, config, ignored -> { });
    }

    public SecureLoginPane(SecureLoginConnection connection, NetConnectConfig config, Consumer<Envelope> onAuthenticated) {
        super(12);
        setPadding(new Insets(30));
        getStyleClass().add("security-login");
        var css = getClass().getResource("security-login.css");
        if (css != null) getStylesheets().add(css.toExternalForm());
        Label title = new Label("NetConnect");
        title.getStyleClass().add("brand");
        Label subtitle = new Label("Secure connections for your local network");
        subtitle.setWrapText(true);
        TextField name = new TextField();
        name.setPromptText("Display name (3-24 characters)");
        PasswordField code = new PasswordField();
        code.setPromptText("Shared access code");
        TextField host = new TextField(config.serverHost());
        TextField port = new TextField(Integer.toString(config.controlPort()));
        name.setAccessibleText("Display name, 3 to 24 characters");
        code.setAccessibleText("Shared access code");
        host.setAccessibleText("Server host");
        port.setAccessibleText("Control port");
        Button login = new Button("Connect securely");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        Label feedback = new Label("Ready - TLS certificate verification enabled");
        feedback.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        feedback.setWrapText(true);
        feedback.setMinHeight(50);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        VBox form = new VBox(8, new Label("Display name"), name, new Label("Access code"), code,
                new Label("Server host"), host, new Label("Control port"), port, login);
        Label identity = new Label();
        identity.setWrapText(true);
        Button disconnect = new Button("Disconnect");
        VBox authenticated = new VBox(14, new Label("Authentication successful"), identity,
                new Label("Session status: authenticated over TLS"), disconnect);
        authenticated.setVisible(false);
        authenticated.managedProperty().bind(authenticated.visibleProperty());
        form.managedProperty().bind(form.visibleProperty());
        getChildren().addAll(title, subtitle, new Separator(), form, authenticated, progress, feedback);
        login.setOnAction(event -> {
            String displayName = name.getText();
            if (displayName.isBlank() || displayName.length() < 3 || displayName.length() > 24
                    || displayName.codePoints().anyMatch(Character::isISOControl)) {
                feedback.setText("Display name must contain 3-24 characters without control characters.");
                return;
            }
            String secret = code.getText();
            if (secret.isBlank()) { feedback.setText("Enter an access code."); return; }
            NetConnectConfig target;
            try {
                target = new NetConnectConfig(host.getText().strip(), Integer.parseInt(port.getText()),
                        config.udpPort(), config.transferPort(), config.fileStagingDirectory(), config.clientDownloadDirectory());
            } catch (IllegalArgumentException invalid) {
                feedback.setText("Enter a server host and a port between 1 and 65535.");
                return;
            }
            code.clear();
            form.setDisable(true);
            progress.setVisible(true);
            feedback.setText("Connecting - verifying server certificate...");
            Task<Envelope> task = new Task<>() {
                @Override protected Envelope call() throws Exception { return connection.login(target, displayName, secret); }
            };
            task.setOnSucceeded(done -> {
                form.setDisable(false);
                progress.setVisible(false);
                boolean accepted = task.getValue().type() == EventType.AUTH_OK;
                if (accepted) {
                    identity.setText("Signed in as " + displayName);
                    form.setVisible(false);
                    authenticated.setVisible(true);
                    feedback.setText("Connected securely. Disconnect when finished.");
                    onAuthenticated.accept(task.getValue());
                } else {
                    feedback.setText(switch (task.getValue().payload().path("code").asText()) {
                        case "NAME_TAKEN" -> "That display name is already in use.";
                        case "INVALID_REQUEST" -> "Check your display name and access code.";
                        default -> "Login denied. Check your access code.";
                    });
                    code.requestFocus();
                }
            });
            task.setOnFailed(done -> {
                form.setDisable(false);
                progress.setVisible(false);
                feedback.setText(task.getException() instanceof SSLException
                        ? "TLS verification failed. Check the trusted certificate and server hostname."
                        : task.getException() instanceof SocketTimeoutException
                        ? "Connection timed out. Check the server and retry."
                        : "Could not connect securely. Check the server address and retry.");
                code.requestFocus();
            });
            run(task);
        });
        disconnect.setOnAction(event -> {
            disconnect.setDisable(true);
            Task<Void> close = new Task<>() {
                @Override protected Void call() throws Exception { connection.close(); return null; }
            };
            close.setOnSucceeded(done -> {
                disconnect.setDisable(false);
                authenticated.setVisible(false);
                form.setVisible(true);
                feedback.setText("Disconnected. Ready to connect again.");
            });
            close.setOnFailed(done -> { disconnect.setDisable(false); feedback.setText("Unable to disconnect. Retry."); });
            run(close);
        });
    }
    private static void run(Task<?> task) {
        Thread worker = new Thread(task, "security-login");
        worker.setDaemon(true);
        worker.start();
    }
}
