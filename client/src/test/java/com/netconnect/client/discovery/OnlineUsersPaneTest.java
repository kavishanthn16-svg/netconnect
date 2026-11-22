package com.netconnect.client.discovery;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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
            Label status = (Label) pane.getChildren().get(3);
            assertEquals("Server UDP hints: missing for 15 seconds or more", status.getText());
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
