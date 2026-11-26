package com.netconnect.client.discovery;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import com.netconnect.client.ui.Ui;
import com.netconnect.client.transfer.FileTransferPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineUsersPaneTest {
    @BeforeAll static void startToolkit() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @AfterAll static void stopToolkit() { Platform.exit(); }

    private static <T> T onFx(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        Platform.runLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test void backgroundAdaptersUpdateActualControlsOnFxThread() throws Exception {
        OnlineUsersPane pane = onFx(OnlineUsersPane::new);
        assertFalse(Platform.isFxApplicationThread());
        pane.onAuthoritativeRoster(8, List.of("Alice", "Bobby"));
        pane.onLivenessChanged(PresenceLiveness.State.EXPIRED);
        onFx(() -> {
            assertEquals(List.of("Alice", "Bobby"), pane.presentationState().users());
            ListView<?> list = (ListView<?>) pane.getChildren().get(2);
            assertEquals(List.of("Alice", "Bobby"), list.getItems());
            TitledPane details = (TitledPane) pane.getChildren().get(3);
            Label status = (Label) ((VBox) details.getContent()).getChildren().getFirst();
            assertEquals("No recent background connection check. Your signed-in list is unchanged.", status.getText());
            assertTrue(status.getStyleClass().contains("warning"));
            return null;
        });
        pane.onAuthoritativeRoster(7, List.of());
        pane.onLivenessChanged(PresenceLiveness.State.FRESH);
        assertEquals(List.of("Alice", "Bobby"), onFx(() -> pane.presentationState().users()));
        pane.resetForConnection();
        assertEquals(OnlineUsersState.initial(), onFx(pane::presentationState));
        pane.onAuthoritativeRoster(0, List.of("Carol"));
        assertEquals(List.of("Carol"), onFx(() -> pane.presentationState().users()));
    }

    @Test void sharedFeedbackReplacesOldSemanticState() throws Exception {
        onFx(() -> {
            Label label = new Label();
            VBox root = new VBox(label);
            var scene = new javafx.scene.Scene(root, 600, 200);
            scene.getStylesheets().add(com.netconnect.client.security.SecureLoginPane.class
                    .getResource("security-login.css").toExternalForm());
            Ui.feedback(label, Ui.Tone.ERROR, "Could not connect");
            root.applyCss();
            assertEquals(javafx.scene.paint.Color.web("#B91C1C"), label.getTextFill());
            assertTrue(label.getStyleClass().contains("error"));
            Ui.feedback(label, Ui.Tone.INFORMATION, "Connecting");
            root.applyCss();
            assertEquals(javafx.scene.paint.Color.web("#1D4ED8"), label.getTextFill());
            assertFalse(label.getStyleClass().contains("error"));
            Ui.feedback(label, Ui.Tone.SUCCESS, "Connected");
            root.applyCss();
            assertEquals(javafx.scene.paint.Color.web("#15803D"), label.getTextFill());
            assertFalse(label.getStyleClass().contains("information"));
            assertTrue(label.getStyleClass().contains("success"));
            assertNotNull(label.getGraphic());
            assertEquals("Connected", label.getAccessibleText());
            return null;
        });
    }

    @Test void transferFeedbackDistinguishesRejectionFailureAndSuccess() throws Exception {
        onFx(() -> {
            FileTransferPane pane = new FileTransferPane();
            pane.setUiDispatcher(Runnable::run);
            try {
                ProgressBar bar = (ProgressBar) pane.lookup(".progress-bar");
                pane.onFileResult(new java.util.UUID(0, 1), "REJECTED", "TRANSFER_REJECTED", "raw diagnostic");
                assertTrue(bar.getStyleClass().contains("warning"));
                var failed = new java.util.UUID(0, 2);
                pane.onFileResult(failed, "FAILED", "CHECKSUM_MISMATCH", "raw diagnostic");
                assertTrue(bar.getStyleClass().contains("error"));
                pane.onFileResult(failed, "SUCCESS", "SUCCESS", "late event");
                assertTrue(bar.getStyleClass().contains("error"));
                pane.onFileResult(new java.util.UUID(0, 3), "SUCCESS", "SUCCESS", "raw diagnostic");
                assertTrue(bar.getStyleClass().contains("success"));
                assertFalse(bar.getStyleClass().contains("error"));
                assertEquals(1.0, bar.getProgress());
                assertEquals("The file could not be transferred. Check your connection and try again.",
                        Ui.transferError("UNKNOWN_FUTURE_CODE"));
                assertFalse(pane.lookupAll(".label").stream().map(node -> ((Label) node).getText())
                        .anyMatch(text -> text != null && text.contains("raw diagnostic")));
            } finally { pane.shutdown(); }
            return null;
        });
    }

    @Test void transferActionsRemainReadableAtCompactAndLargeWidths() throws Exception {
        onFx(() -> {
            FileTransferPane pane = new FileTransferPane();
            try {
                var sidebar = new javafx.scene.layout.Region();
                sidebar.setPrefWidth(224);
                var scroller = new javafx.scene.control.ScrollPane(pane);
                scroller.setFitToWidth(true);
                scroller.setFitToHeight(true);
                var root = new javafx.scene.layout.BorderPane(scroller, null, null, null, sidebar);
                var scene = new javafx.scene.Scene(root, 760, 540);
                scene.getStylesheets().add(com.netconnect.client.security.SecureLoginPane.class
                        .getResource("security-login.css").toExternalForm());
                for (double width : new double[]{760, 1000, 1280, 1366, 1920}) {
                    root.resize(width, width == 760 ? 540 : 720);
                    root.applyCss();
                    root.layout();
                    for (var node : pane.lookupAll(".feedback")) {
                        if (node instanceof Label feedback) {
                            var text = (javafx.scene.text.Text) feedback.lookup(".text");
                            assertEquals(feedback.getText(), text.getText(), "Clipped feedback at width " + width);
                        }
                    }
                    for (var node : pane.lookupAll(".button")) {
                        if (node instanceof javafx.scene.control.Button button && !button.getText().isBlank()) {
                            var text = (javafx.scene.text.Text) button.lookup(".text");
                            assertEquals(button.getText(), text.getText(), "Clipped action at width " + width);
                        }
                    }
                }
            } finally { pane.shutdown(); }
            return null;
        });
    }

    @Test void rejectsOffThreadConstructionAndStateRead() throws Exception {
        assertThrows(IllegalStateException.class, OnlineUsersPane::new);
        OnlineUsersPane pane = onFx(OnlineUsersPane::new);
        assertThrows(IllegalStateException.class, pane::presentationState);
        assertThrows(IllegalArgumentException.class, () -> pane.onAuthoritativeRoster(-1, List.of()));
    }

    @Test void fxThreadProcessesUpdatesWhileUdpWorkerWaitsForPackets() throws Exception {
        OnlineUsersPane pane = onFx(OnlineUsersPane::new);
        try (var server = new java.net.DatagramSocket(0);
             var receiver = new UdpPresenceReceiver(pane::onLivenessChanged)) {
            receiver.onAuthenticated(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), server.getLocalPort()));
            pane.onAuthoritativeRoster(1, List.of("Alice"));
            FutureTask<Boolean> responsive = new FutureTask<>(() -> {
                assertEquals(List.of("Alice"), pane.presentationState().users());
                return Platform.isFxApplicationThread();
            });
            Platform.runLater(responsive);
            assertTrue(responsive.get(1, TimeUnit.SECONDS));
            receiver.close();
            assertTrue(receiver.awaitTermination(1000));
        }
    }
}
