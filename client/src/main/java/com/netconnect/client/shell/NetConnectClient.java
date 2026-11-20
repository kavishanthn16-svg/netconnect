package com.netconnect.client.shell;

import com.netconnect.common.config.NetConnectConfig;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Minimal JavaFX shell; feature panes are extension points for the owning members. */
public final class NetConnectClient extends Application {
    @Override
    public void start(Stage stage) {
        NetConnectConfig config = NetConnectConfig.load();
        Label status = new Label("Status: Not connected");
        Label login = new Label("Login integration placeholder — authentication is owned by 234042R.");

        TabPane navigation = new TabPane(
                placeholderTab("Chat", "Chat view placeholder — owned by 234229X."),
                placeholderTab("Online Users", "Presence view placeholder — owned by 234012C."),
                placeholderTab("File Transfers", "Transfer view placeholder — owned by 234077D."));
        navigation.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox header = new VBox(8, new Label("NetConnect"), status, login,
                new Label("Configured server: " + config.serverHost() + ":" + config.controlPort()));
        header.setPadding(new Insets(12));
        BorderPane root = new BorderPane(navigation, header, null, null, null);
        stage.setTitle("NetConnect");
        stage.setScene(new Scene(root, 720, 460));
        stage.show();
    }

    private static Tab placeholderTab(String title, String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        label.setPadding(new Insets(20));
        return new Tab(title, label);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
