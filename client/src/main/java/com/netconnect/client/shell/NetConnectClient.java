package com.netconnect.client.shell;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.*;
import com.netconnect.client.security.*;
import com.netconnect.client.transfer.FileTransferPane;
import com.netconnect.client.discovery.OnlineUsersPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.*;

/** Existing application shell with security-owned login and retained TLS transport. */
public final class NetConnectClient extends Application {
    private SecureLoginConnection login;
    private AuthenticatedControlConnection control;
    private FileTransferPane transfers;
    private Stage stage;
    private boolean stopped;

    @Override public void init() throws Exception {
        boolean allowTls12 = Boolean.parseBoolean(System.getenv("NETCONNECT_ALLOW_TLS12"));
        String store = System.getenv("NETCONNECT_TRUST_STORE");
        if (store == null || store.isBlank()) login = new SecureLoginConnection(allowTls12);
        else {
            String value = System.getenv("NETCONNECT_TRUST_STORE_PASSWORD");
            char[] password = value == null ? null : value.toCharArray();
            try { login = new SecureLoginConnection(Path.of(store), password, allowTls12); }
            finally { if (password != null) Arrays.fill(password, '\0'); }
        }
    }
    @Override public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("NetConnect");
        stage.setMinWidth(560);
        stage.setMinHeight(700);
        showLogin();
        stage.show();
    }
    private void showLogin() {
        var pane = new SecureLoginPane(login, NetConnectConfig.load(), this::authenticated);
        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        stage.setScene(new Scene(scroll, 720, 740));
    }
    private void authenticated(Envelope result) {
        if (stopped) return;
        try {
            var connection = new AuthenticatedControlConnection(login.detachAuthenticatedSocket(), result.sessionId());
            control = connection;
            var config = NetConnectConfig.load();
            var users = new OnlineUsersPane();
            transfers = new FileTransferPane();
            var filePane = transfers;
            filePane.setOnOfferRequested(offer -> connection.send(EventType.FILE_OFFER,
                    JsonNodeFactory.instance.objectNode().put("transferId", offer.transferId().toString())
                            .put("toUser", offer.toUser()).put("fileName", offer.fileName())
                            .put("sizeBytes", offer.sizeBytes()).put("sha256", offer.sha256())));
            filePane.setOnDecisionRequested((id, accept) -> connection.send(EventType.FILE_DECISION,
                    JsonNodeFactory.instance.objectNode().put("transferId", id).put("accept", accept)));
            Button logout = new Button("Disconnect");
            logout.setOnAction(event -> connection.close());
            VBox header = new VBox(8, new Label("NetConnect"),
                    new Label("Connected over verified TLS as " + result.payload().path("displayName").asText()), logout,
                    new Label("Configured server: " + config.serverHost() + ":" + config.controlPort()));
            header.setPadding(new Insets(12));
            TabPane navigation = new TabPane(new Tab("Chat", new Label()),
                    new Tab("Online Users", users), new Tab("File Transfers", filePane));
            navigation.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            stage.setScene(new Scene(new BorderPane(navigation, header, null, null, null), 860, 740));
            connection.start(envelope -> Platform.runLater(() -> {
                if (control != connection || stopped) return;
                var p = envelope.payload();
                switch (envelope.type()) {
                    case USER_ROSTER -> {
                        List<String> names = new ArrayList<>();
                        p.path("users").forEach(name -> names.add(name.asText()));
                        users.onAuthoritativeRoster(p.path("revision").asLong(), names);
                        filePane.updateOnlineUsers(names.stream().filter(name -> !name.equals(result.payload().path("displayName").asText())).toList());
                    }
                    case FILE_OFFERED -> filePane.onFileOffered(UUID.fromString(p.path("transferId").asText()),
                            p.path("fromUser").asText(), p.path("fileName").asText(), p.path("sizeBytes").asLong(), p.path("sha256").asText());
                    case FILE_READY -> filePane.onFileReady(UUID.fromString(p.path("transferId").asText()), p.path("ticket").asText(),
                            p.path("host").asText(), p.path("port").asInt(), p.path("role").asText(), p.path("expiresAt").asLong());
                    case FILE_RESULT -> filePane.onFileResult(UUID.fromString(p.path("transferId").asText()), p.path("status").asText(),
                            p.path("code").asText(), p.path("message").asText());
                    case ERROR -> filePane.onError(p.path("code").asText(), p.path("message").asText());
                    default -> { }
                }
            }), () -> Platform.runLater(() -> {
                filePane.shutdown();
                if (control == connection) { control = null; transfers = null; if (!stopped) showLogin(); }
            }));
        } catch (java.io.IOException failure) { showLogin(); }
    }
    @Override public void stop() {
        stopped = true;
        if (transfers != null) transfers.shutdown();
        if (control != null) control.close();
        Thread.ofVirtual().name("netconnect-login-cleanup").start(() -> {
            try { if (login != null) login.close(); } catch (java.io.IOException ignored) { }
        });
    }
    public static void main(String[] args) { launch(args); }
}
