package com.netconnect.client.discovery;

import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import com.netconnect.client.ui.Ui;
import static com.netconnect.client.ui.Ui.Tone.*;
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

    private String currentUser;

    public void setCurrentUser(String name) { currentUser = name; users.refresh(); }

    public OnlineUsersPane() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Construct OnlineUsersPane on the JavaFX Application Thread");
        }
        setSpacing(12);
        setPadding(new Insets(16));
        Label heading = new Label("Online users");
        heading.getStyleClass().add("section-title");
        users.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                setText(null);
                if (empty || name == null) { setGraphic(null); return; }
                Label avatar = Ui.avatar(name);
                Label identity = new Label(name + (name.equals(currentUser) ? "  (you)" : ""));
                Label status = new Label("Online");
                Ui.feedback(status, SUCCESS, "Online");
                identity.setMinWidth(0);
                identity.setWrapText(true);
                javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(14, avatar, identity, spacer, status);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(spacer, Priority.ALWAYS);
                setGraphic(row);
                setAccessibleText(name + (name.equals(currentUser) ? ", you, online" : ", online"));
            }
        });
        users.setPlaceholder(new Label("No one is online yet"));
        users.setFocusTraversable(true);
        users.setAccessibleText("Online users");
        liveness.setWrapText(true);
        Label explanation = new Label("Connection checks do not change the list of people signed in.");
        explanation.setWrapText(true);
        javafx.scene.control.TitledPane details = new javafx.scene.control.TitledPane("Connection details", new VBox(6, liveness, explanation));
        details.setAnimated(false);
        details.setExpanded(false);
        getChildren().addAll(heading, count, users, details);
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
        Ui.feedback(count, state.revision() < 0 ? INFORMATION : NEUTRAL, state.revision() < 0
                ? "Loading people signed in..." : state.users().size() + (state.users().size() == 1 ? " person online" : " people online"));
        Ui.feedback(liveness, state.liveness() == PresenceLiveness.State.FRESH ? SUCCESS
                : state.liveness() == PresenceLiveness.State.EXPIRED ? WARNING : NEUTRAL, switch (state.liveness()) {
            case WAITING -> "No background connection check received yet.";
            case FRESH -> "Background connection check received recently.";
            case EXPIRED -> "No recent background connection check. Your signed-in list is unchanged.";
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
