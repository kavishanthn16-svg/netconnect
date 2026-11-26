package com.netconnect.client.shell;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.netconnect.common.config.NetConnectConfig;
import com.netconnect.common.protocol.*;
import com.netconnect.client.security.*;
import com.netconnect.client.transfer.FileTransferPane;
import com.netconnect.client.discovery.OnlineUsersPane;
import com.netconnect.client.chat.ChatPane;
import com.netconnect.client.chat.ChatSender;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import com.netconnect.client.ui.Ui;
import static com.netconnect.client.ui.Ui.Tone.*;
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
    private ChatPane chats;
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
        stage.setMinWidth(760);
        stage.setMinHeight(540);
        showLogin();
        stage.show();
    }
    private void showLogin() { showLogin(null); }
    private void showLogin(String message) {
        var pane = new SecureLoginPane(login, NetConnectConfig.load(), this::authenticated);
        if (message != null) pane.showConnectionNotice(message);
        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        stage.setScene(styledScene(scroll, 1000, 680));
    }
    private void authenticated(Envelope result) {
        if (stopped) return;
        try {
            var connection = new AuthenticatedControlConnection(login.detachAuthenticatedSocket(), result.sessionId());
            control = connection;
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
            boolean[] disconnectRequested = {false};
            logout.setOnAction(event -> {
                disconnectRequested[0] = true;
                logout.setDisable(true);
                connection.close();
            });
            logout.setGraphic(Ui.icon("disconnect"));
            String displayName = result.payload().path("displayName").asText();
            users.setCurrentUser(displayName);
            StackPane brandLogo = new StackPane(Ui.icon("network"));
            brandLogo.getStyleClass().add("sidebar-logo");
            Label brand = new Label("NetConnect", brandLogo);
            brand.getStyleClass().add("sidebar-brand");
            Label account = new Label(displayName);
            account.getStyleClass().add("sidebar-account");
            account.setWrapText(true);
            account.setMinWidth(0);
            Label profileStatus = new Label("Connected");
            profileStatus.getStyleClass().add("profile-status");
            profileStatus.setGraphic(Ui.icon("check"));
            VBox identity = new VBox(4, account, profileStatus);
            identity.setMinWidth(0);
            HBox profile = new HBox(10, Ui.avatar(displayName), identity);
            profile.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            profile.getStyleClass().add("profile");
            HBox.setHgrow(identity, Priority.ALWAYS);
            Label secure = new Label();
            Ui.feedback(secure, SUCCESS, "Secure connection");
            secure.getStyleClass().add("connection-status");
            Label pageTitle = new Label("Chat");
            pageTitle.getStyleClass().add("page-title");
            Region headerSpacer = new Region();
            HBox.setHgrow(headerSpacer, Priority.ALWAYS);
            HBox header = new HBox(16, pageTitle, headerSpacer, secure);
            header.getStyleClass().add("page-header");
            var chat = new ChatPane(displayName, new ChatSender() {
                @Override public void sendGroup(String text) {
                    connection.send(EventType.CHAT_GROUP_SEND, JsonNodeFactory.instance.objectNode().put("text", text));
                }
                @Override public void sendPrivate(String toUser, String text) {
                    connection.send(EventType.CHAT_PRIVATE_SEND,
                            JsonNodeFactory.instance.objectNode().put("toUser", toUser).put("text", text));
                }
            });
            chats = chat;
            BorderPane content = new BorderPane(chat, header, null, null, null);
            ToggleGroup group = new ToggleGroup();
            VBox links = new VBox(6);
            javafx.scene.Node[] pages = {chat, users, filePane};
            String[] titles = {"Chat", "Online Users", "File Transfers"};
            String[] icons = {"chat", "users", "file"};
            ScrollPane transferScroll = new ScrollPane(filePane);
            transferScroll.setFitToWidth(true);
            transferScroll.setFitToHeight(true);
            pages[2] = transferScroll;
            for (int i = 0; i < titles.length; i++) {
                final int index = i;
                ToggleButton link = new ToggleButton(titles[i]);
                link.getStyleClass().add("nav-item");
                link.setGraphic(Ui.icon(icons[i]));
                link.setAccessibleText(titles[i]);
                link.setMinHeight(44);
                link.setMaxWidth(Double.MAX_VALUE);
                link.setToggleGroup(group);
                link.setSelected(i == 0);
                link.setOnAction(event -> {
                    link.setSelected(true);
                    pageTitle.setText(titles[index]);
                    content.setCenter(pages[index]);
                });
                links.getChildren().add(link);
            }
            Region spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);
            logout.getStyleClass().add("sidebar-action");
            logout.setMaxWidth(Double.MAX_VALUE);
            VBox sidebar = new VBox(20, brand, links, spacer, profile, logout);
            sidebar.getStyleClass().add("sidebar");
            sidebar.setPrefWidth(224);
            sidebar.setMinWidth(224);
            stage.setScene(styledScene(new BorderPane(content, null, null, null, sidebar),
                    1000, 680));
            connection.start(envelope -> Platform.runLater(() -> {
                if (control != connection || stopped) return;
                var p = envelope.payload();
                switch (envelope.type()) {
                    case USER_ROSTER -> {
                        List<String> names = new ArrayList<>();
                        p.path("users").forEach(name -> names.add(name.asText()));
                        users.onAuthoritativeRoster(p.path("revision").asLong(), names);
                        chat.onRoster(names);
                        filePane.updateOnlineUsers(names.stream().filter(name -> !name.equals(result.payload().path("displayName").asText())).toList());
                    }
                    case FILE_OFFERED -> filePane.onFileOffered(UUID.fromString(p.path("transferId").asText()),
                            p.path("fromUser").asText(), p.path("fileName").asText(), p.path("sizeBytes").asLong(), p.path("sha256").asText());
                    case FILE_READY -> filePane.onFileReady(UUID.fromString(p.path("transferId").asText()), p.path("ticket").asText(),
                            p.path("host").asText(), p.path("port").asInt(), p.path("role").asText(), p.path("expiresAt").asLong());
                    case FILE_RESULT -> filePane.onFileResult(UUID.fromString(p.path("transferId").asText()), p.path("status").asText(),
                            p.path("code").asText(), p.path("message").asText());
                    case CHAT_DELIVER -> chat.onDeliver(envelope);
                    case ERROR -> {
                        chat.onServerError(p.path("code").asText(), p.path("message").asText());
                        filePane.onError(p.path("code").asText(), p.path("message").asText());
                    }
                    default -> { }
                }
            }), () -> Platform.runLater(() -> {
                chat.setSendingEnabled(false);
                chat.close();
                filePane.shutdown();
                if (control == connection) { control = null; transfers = null; if (!stopped) showLogin(disconnectRequested[0]
                        ? "Disconnected. You can connect again when you're ready."
                        : "Connection lost. Check your network and connect again."); }
            }));
        } catch (java.io.IOException failure) { showLogin(); }
    }
    private Scene styledScene(javafx.scene.Parent root, double width, double height) {
        Scene previous = stage.getScene();
        Scene scene = new Scene(root, previous == null ? width : previous.getWidth(),
                previous == null ? height : previous.getHeight());
        scene.getStylesheets().add(SecureLoginPane.class.getResource("security-login.css").toExternalForm());
        return scene;
    }
    @Override public void stop() {
        stopped = true;
        if (transfers != null) transfers.shutdown();
        if (chats != null) chats.close();
        if (control != null) control.close();
        Thread.ofVirtual().name("netconnect-login-cleanup").start(() -> {
            try { if (login != null) login.close(); } catch (java.io.IOException ignored) { }
        });
    }
    public static void main(String[] args) { launch(args); }
}
