package com.netconnect.client.security;

import com.netconnect.common.config.NetConnectConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Independent security UI entry point; does not depend on the application shell. */
public final class SecureLoginLauncher extends Application {
    private SecureLoginConnection connection;

    @Override public void init() throws Exception {
        boolean allowTls12 = Boolean.parseBoolean(System.getenv("NETCONNECT_ALLOW_TLS12"));
        String trustStore = System.getenv("NETCONNECT_TRUST_STORE");
        if (trustStore == null || trustStore.isBlank()) {
            connection = new SecureLoginConnection(allowTls12);
        } else {
            String value = System.getenv("NETCONNECT_TRUST_STORE_PASSWORD");
            char[] password = value == null ? null : value.toCharArray();
            try { connection = new SecureLoginConnection(Path.of(trustStore), password, allowTls12); }
            finally { if (password != null) Arrays.fill(password, '\0'); }
        }
    }

    @Override public void start(Stage stage) {
        SecureLoginPane pane = new SecureLoginPane(connection, NetConnectConfig.load());
        stage.setTitle("NetConnect");
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        Scene scene = new Scene(scroll, 600, 680);
        scene.getStylesheets().add(SecureLoginPane.class.getResource("security-login.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(440);
        stage.setMinHeight(540);
        stage.show();
    }

    @Override public void stop() {
        // close() may wait for a pending login; never block the JavaFX thread.
        Thread cleanup = new Thread(() -> {
            try { connection.close(); }
            catch (IOException failure) { System.err.println("Unable to close the security login connection."); }
        }, "security-login-cleanup");
        cleanup.start();
    }

    public static void main(String[] args) { launch(args); }
}
