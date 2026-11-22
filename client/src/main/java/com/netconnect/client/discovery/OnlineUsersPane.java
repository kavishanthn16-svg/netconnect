package com.netconnect.client.discovery;

import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Construct on the JavaFX thread; adapter methods may be called from background workers.
 * Owns no sockets or threads. Mount this Node using the shell owner's extension point.
 * Use one pane per authenticated connection; reset after old callbacks have been detached.
 */
public final class OnlineUsersPane extends VBox {
    private OnlineUsersState state = OnlineUsersState.initial();
    private final Label count = new Label();
    private final ListView<String> users = new ListView<>();
    private final Label liveness = new Label();

    public OnlineUsersPane() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Construct OnlineUsersPane on the JavaFX Application Thread");
        }
        setSpacing(12);
        setPadding(new Insets(16));
        Label heading = new Label("Online users");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        users.setPlaceholder(new Label("No users in the current TLS roster"));
        users.setFocusTraversable(true);
        users.setAccessibleText("Online users from the authenticated TLS roster");
        liveness.setWrapText(true);
        Label explanation = new Label("UDP hints indicate server liveness only. Missing hints do not change the TLS roster.");
        explanation.setWrapText(true);
        getChildren().addAll(heading, count, users, liveness, explanation);
        VBox.setVgrow(users, Priority.ALWAYS);
        render();
    }

    /** Invoke only from the authenticated USER_ROSTER dispatcher, never a UDP receiver. */
    public void onAuthoritativeRoster(long revision, List<String> displayNames) {
        if (revision < 0) {
            throw new IllegalArgumentException("Roster revision must be nonnegative");
        }
        List<String> snapshot = List.copyOf(displayNames);
        // Always queue to preserve callback order even when callers include the FX thread.
        Platform.runLater(() -> {
            state = state.withRoster(revision, snapshot);
            render();
        });
    }

    public void onLivenessChanged(PresenceLiveness.State next) {
        Objects.requireNonNull(next);
        Platform.runLater(() -> {
            state = state.withLiveness(next);
            render();
        });
    }

    /** TLS lifecycle action only; UDP expiry must never call this. */
    public void resetForConnection() {
        Platform.runLater(() -> {
            state = OnlineUsersState.initial();
            render();
        });
    }

    private void render() {
        users.getItems().setAll(state.users());
        count.setText(state.revision() < 0 ? "Waiting for authenticated TLS roster"
                : state.users().size() + " online (TLS roster)");
        liveness.setText(state.livenessText());
        liveness.setStyle(switch (state.liveness()) {
            case WAITING -> "-fx-text-fill: #555555;";
            case FRESH -> "-fx-text-fill: #21643b;";
            case EXPIRED -> "-fx-text-fill: #805000;";
        });
    }

    // Package-local read access for FX-thread verification without exposing mutable controls.
    OnlineUsersState presentationState() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Read presentation state on JavaFX thread");
        }
        return state;
    }
}
