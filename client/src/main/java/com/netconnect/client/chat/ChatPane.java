package com.netconnect.client.chat;

import com.netconnect.common.protocol.Envelope;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Self-contained group/private chat view (SRS 17). Construct it on the JavaFX thread. Every
 * {@code on*} method is safe to call from any thread; network sends run on a background thread.
 * The shell mounts it as a plain {@link javafx.scene.Node}: {@code new Tab("Chat", chatPane)}.
 * Use one pane per connection and call {@link #close()} when the connection ends.
 */
public final class ChatPane extends BorderPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ChatSender sender;
    private final ChatConversationModel model;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "netconnect-chat-send");
        thread.setDaemon(true);
        return thread;
    });
    private List<String> roster = List.of();
    private final Consumer<Runnable> fxDispatch;
    private boolean sendingEnabled = true;

    final ToggleButton groupToggle = new ToggleButton("Group");
    final ToggleButton privateToggle = new ToggleButton("Private");
    final ComboBox<String> recipientBox = new ComboBox<>();
    final ListView<ChatMessage> messageList = new ListView<>();
    final TextField input = new TextField();
    final Button sendButton = new Button("Send");
    final Label counter = new Label("0/" + ChatProtocol.MAX_TEXT_LENGTH);
    final Label status = new Label();

    public ChatPane(String selfName, ChatSender sender) {
        this(selfName, sender, ChatPane::platformDispatch);
    }

    /** Test seam: how UI updates are marshalled onto the JavaFX thread. */
    ChatPane(String selfName, ChatSender sender, Consumer<Runnable> fxDispatch) {
        this.fxDispatch = Objects.requireNonNull(fxDispatch, "fxDispatch");
        this.model = new ChatConversationModel(selfName);
        this.sender = Objects.requireNonNull(sender, "sender");
        buildUi();
        refresh();
    }

    private void buildUi() {
        ToggleGroup views = new ToggleGroup();
        groupToggle.setToggleGroup(views);
        privateToggle.setToggleGroup(views);
        groupToggle.setSelected(true);
        // Keep exactly one view selected.
        views.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                old.setSelected(true);
            } else {
                refresh();
            }
        });
        recipientBox.setPromptText("Choose user");
        recipientBox.valueProperty().addListener((obs, old, now) -> refresh());
        HBox top = new HBox(8, groupToggle, privateToggle, recipientBox);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));
        setTop(top);

        messageList.setPlaceholder(new Label("No messages yet"));
        messageList.setCellFactory(list -> new MessageCard());
        setCenter(messageList);

        input.setPromptText("Type a message");
        input.setOnAction(event -> send());
        input.textProperty().addListener((obs, old, now) -> counter.setText(now.length() + "/" + ChatProtocol.MAX_TEXT_LENGTH));
        sendButton.setDefaultButton(false);
        sendButton.setOnAction(event -> send());
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(8, input, counter, sendButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-text-fill: #b00020;");
        VBox bottom = new VBox(4, inputRow, status);
        bottom.setPadding(new Insets(8));
        setBottom(bottom);
    }

    // ---- inbound (any thread) -------------------------------------------------------------

    /** Feed a CHAT_DELIVER envelope from the control connection. Malformed ones are reported, not thrown. */
    public void onDeliver(Envelope envelope) {
        ChatMessage message;
        try {
            message = ChatProtocol.parseDeliver(envelope);
        } catch (IllegalArgumentException malformed) {
            fx(() -> status.setText("Received an unreadable chat message"));
            return;
        }
        onMessage(message);
    }

    public void onMessage(ChatMessage message) {
        fx(() -> {
            if (model.add(message)) {
                refreshRecipients();
                refresh();
            }
        });
    }

    /** Authoritative roster from USER_ROSTER (display names). */
    public void onRoster(List<String> users) {
        List<String> copy = List.copyOf(users);
        fx(() -> {
            roster = copy;
            refreshRecipients();
        });
    }

    /** Server ERROR for a chat request, e.g. USER_OFFLINE. Only the code drives wording. */
    public void onServerError(String code, String message) {
        fx(() -> status.setText("USER_OFFLINE".equals(code) ? "That user is offline" : "Chat error: " + code));
    }

    public void setSendingEnabled(boolean enabled) {
        fx(() -> {
            sendingEnabled = enabled;
            input.setDisable(!enabled);
            sendButton.setDisable(!enabled);
            if (!enabled) {
                status.setText("Disconnected");
            }
        });
    }

    public void close() {
        sendExecutor.shutdownNow();
    }

    // ---- outbound -------------------------------------------------------------------------

    private void send() {
        if (!sendingEnabled) {
            return;
        }
        String text = input.getText();
        String problem = ChatProtocol.validateText(text);
        boolean isPrivate = privateToggle.isSelected();
        String recipient = recipientBox.getValue();
        if (problem == null && isPrivate && recipient == null) {
            problem = "Choose a user to message";
        }
        if (problem != null) {
            status.setText(problem);
            return;
        }
        status.setText("");
        input.clear();
        try {
            sendExecutor.execute(() -> {
                try {
                    if (isPrivate) {
                        sender.sendPrivate(recipient, text);
                    } else {
                        sender.sendGroup(text);
                    }
                } catch (Exception failure) {
                    fx(() -> status.setText("Could not send: " + failure.getClass().getSimpleName()));
                }
            });
        } catch (RejectedExecutionException closed) {
            status.setText("Chat is closed");
        }
    }

    // ---- view -----------------------------------------------------------------------------

    private void refreshRecipients() {
        String selected = recipientBox.getValue();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String user : roster) {
            if (!user.equalsIgnoreCase(model.self())) {
                names.add(user);
            }
        }
        names.addAll(model.privatePeers());
        recipientBox.getItems().setAll(names);
        if (selected != null && names.contains(selected)) {
            recipientBox.setValue(selected);
        }
    }

    private void refresh() {
        List<ChatMessage> shown = groupToggle.isSelected() ? model.group() : model.privateWith(recipientBox.getValue());
        recipientBox.setVisible(privateToggle.isSelected());
        recipientBox.setManaged(privateToggle.isSelected());
        messageList.getItems().setAll(new ArrayList<>(shown));
        if (!shown.isEmpty()) {
            messageList.scrollTo(shown.size() - 1);
        }
    }

    private void fx(Runnable action) {
        fxDispatch.accept(action);
    }

    private static void platformDispatch(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private final class MessageCard extends ListCell<ChatMessage> {
        @Override
        protected void updateItem(ChatMessage message, boolean empty) {
            super.updateItem(message, empty);
            if (empty || message == null) {
                setGraphic(null);
                return;
            }
            boolean mine = model.self().equalsIgnoreCase(message.fromUser());
            Label header = new Label((mine ? "You" : message.fromUser()) + " · " + TIME.format(message.sentAt()));
            header.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
            Label body = new Label(message.text());
            body.setWrapText(true);
            body.maxWidthProperty().bind(messageList.widthProperty().subtract(70));
            VBox card = new VBox(2, header, body);
            card.setPadding(new Insets(6, 10, 6, 10));
            card.setStyle("-fx-background-radius: 8; -fx-background-color: " + (mine ? "#d8ebff" : "#eeeeee") + ";");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = mine ? new HBox(spacer, card) : new HBox(card, spacer);
            setGraphic(row);
        }
    }
}
