package com.netconnect.client.security;

import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.EventType;
import com.netconnect.common.protocol.Envelope;
import javafx.concurrent.Task;
import com.netconnect.client.ui.Ui;
import static com.netconnect.client.ui.Ui.Tone.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.util.function.Consumer;

/** Reusable security-only view; exposes authentication to a future shell without owning it. */
public final class SecureLoginPane extends VBox {
    private final Label connectionFeedback = new Label();
    public SecureLoginPane(SecureLoginConnection connection, NetConnectConfig config) {
        this(connection, config, ignored -> { });
    }

    public SecureLoginPane(SecureLoginConnection connection, NetConnectConfig config, Consumer<Envelope> onAuthenticated) {
        super(12);
        setPadding(new Insets(32));
        setAlignment(javafx.geometry.Pos.CENTER);
        getStyleClass().add("security-login");
        var css = getClass().getResource("security-login.css");
        if (css != null) getStylesheets().add(css.toExternalForm());
        Label mark = new Label();
        mark.setGraphic(Ui.icon("network"));
        mark.getStyleClass().add("brand-mark");
        Label title = new Label("NetConnect");
        title.getStyleClass().add("brand");
        Label subtitle = new Label("Your local network, connected securely");
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
        Label feedback = connectionFeedback;
        Ui.feedback(feedback, INFORMATION, "Ready to connect securely.");
        feedback.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        feedback.setWrapText(true);
        feedback.setMinHeight(44);
        feedback.getStyleClass().add("feedback");
        feedback.setMaxWidth(440);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        VBox settings = new VBox(8, new Label("Server host"), host, new Label("Control port"), port);
        TitledPane advanced = new TitledPane("Connection settings", settings);
        advanced.setAnimated(false);
        advanced.setExpanded(false);
        VBox form = new VBox(10, new Label("Display name"), name, new Label("Access code"), code, advanced, login);
        form.getStyleClass().add("card");
        form.setMaxWidth(440);
        login.getStyleClass().add("primary");
        Label identity = new Label();
        identity.setWrapText(true);
        Button disconnect = new Button("Disconnect");
        VBox authenticated = new VBox(14, new Label("You're connected"), identity,
                new Label("Secure connection"), disconnect);
        authenticated.setVisible(false);
        authenticated.managedProperty().bind(authenticated.visibleProperty());
        form.managedProperty().bind(form.visibleProperty());
        Label security = new Label("Server identity is checked before you connect");
        security.getStyleClass().add("muted");
        security.setWrapText(true);
        getChildren().addAll(mark, title, subtitle, form, authenticated, progress, feedback, security);
        login.setOnAction(event -> {
            String displayName = name.getText();
            if (displayName.isBlank() || displayName.length() < 3 || displayName.length() > 24
                    || displayName.codePoints().anyMatch(Character::isISOControl)) {
                Ui.feedback(feedback, ERROR, "Use a display name with 3-24 characters and no control characters.");
                return;
            }
            String secret = code.getText();
            if (secret.isBlank()) { Ui.feedback(feedback, ERROR, "Enter your access code."); return; }
            NetConnectConfig target;
            try {
                target = new NetConnectConfig(host.getText().strip(), Integer.parseInt(port.getText()),
                        config.udpPort(), config.transferPort(), config.fileStagingDirectory(), config.clientDownloadDirectory());
            } catch (IllegalArgumentException invalid) {
                Ui.feedback(feedback, ERROR, "Enter the server address and a port between 1 and 65535.");
                return;
            }
            code.clear();
            form.setDisable(true);
            progress.setVisible(true);
            Ui.feedback(feedback, INFORMATION, "Connecting... Checking the server's identity.");
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
                    Ui.feedback(feedback, SUCCESS, "Connected securely.");
                    onAuthenticated.accept(task.getValue());
                } else {
                    Ui.feedback(feedback, ERROR, switch (task.getValue().payload().path("code").asText()) {
                        case "NAME_TAKEN" -> "That display name is already in use.";
                        case "INVALID_REQUEST" -> "Check your display name and access code.";
                        case "ACCESS_DENIED" -> "Unable to sign in. Check your access code or contact the server owner.";
                        default -> "Unable to sign in. Please try again or contact the server owner.";
                    });
                    code.requestFocus();
                }
            });
            task.setOnFailed(done -> {
                form.setDisable(false);
                progress.setVisible(false);
                Ui.feedback(feedback, ERROR, task.getException() instanceof SSLException
                        ? "Unable to verify the server's identity. Check the server address or contact the server owner."
                        : task.getException() instanceof SocketTimeoutException
                        ? "The server took too long to respond. Check the address and try again."
                        : "The server is unavailable. Check the address and your connection, then try again.");
                code.requestFocus();
            });
            run(task);
        });
        disconnect.setOnAction(event -> {
            disconnect.setDisable(true);
            Ui.feedback(feedback, INFORMATION, "Disconnecting...");
            Task<Void> close = new Task<>() {
                @Override protected Void call() throws Exception { connection.close(); return null; }
            };
            close.setOnSucceeded(done -> {
                disconnect.setDisable(false);
                authenticated.setVisible(false);
                form.setVisible(true);
                Ui.feedback(feedback, NEUTRAL, "Disconnected. You can connect again when you're ready.");
            });
            close.setOnFailed(done -> { disconnect.setDisable(false); Ui.feedback(feedback, ERROR, "Could not disconnect. Please try again."); });
            run(close);
        });
    }
    public void showConnectionNotice(String message) {
        Ui.feedback(connectionFeedback, message.startsWith("Connection lost") ? WARNING : NEUTRAL, message);
    }

    private static void run(Task<?> task) {
        Thread worker = new Thread(task, "security-login");
        worker.setDaemon(true);
        worker.start();
    }
}
