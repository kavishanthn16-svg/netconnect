package com.netconnect.client.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netconnect.common.protocol.Envelope;
import com.netconnect.common.protocol.EventType;
import javafx.application.Platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-toolkit ChatPane scenarios. This is a stand-alone main run in its OWN JVM by
 * {@link ChatPaneFxIsolatedTest}: the discovery tests own Platform.startup/exit for the surefire
 * JVM, and a JavaFX toolkit can be started only once per process.
 */
public final class ChatPaneFxScenarios {
    private ChatPaneFxScenarios() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        if (!started.await(10, TimeUnit.SECONDS)) {
            System.err.println("JavaFX toolkit did not start");
            System.exit(2);
        }
        Platform.setImplicitExit(false);

        Map<String, ThrowingRunnable> scenarios = new LinkedHashMap<>();
        scenarios.put("groupSendRunsOffTheFxThreadAndClearsInput", ChatPaneFxScenarios::groupSend);
        scenarios.put("privateSendNeedsRecipientThenGoesToChosenUser", ChatPaneFxScenarios::privateSend);
        scenarios.put("emptyAndOversizedInputAreRefusedLocally", ChatPaneFxScenarios::localValidation);
        scenarios.put("senderFailureIsShownNotThrown", ChatPaneFxScenarios::senderFailure);
        scenarios.put("incomingMessagesFromBackgroundThreadAppearInRightView", ChatPaneFxScenarios::incoming);
        scenarios.put("duplicateAndMalformedDeliveriesAreHandled", ChatPaneFxScenarios::duplicatesAndMalformed);
        scenarios.put("serverErrorsAndDisconnectUpdateStatusAndDisableSending", ChatPaneFxScenarios::errorsAndDisconnect);
        scenarios.put("conversationWithPeerWhoLeftStaysSelectable", ChatPaneFxScenarios::peerLeaves);

        int failures = 0;
        for (var scenario : scenarios.entrySet()) {
            try {
                scenario.getValue().run();
                System.out.println("PASS " + scenario.getKey());
            } catch (Throwable failure) {
                failures++;
                System.out.println("FAIL " + scenario.getKey() + ": " + failure);
                failure.printStackTrace(System.out);
            }
        }
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static <T> T onFx(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        Platform.runLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingSender implements ChatSender {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile Exception failure;
        final CountDownLatch sent = new CountDownLatch(1);

        @Override
        public void sendGroup(String text) throws Exception {
            threads.add(Thread.currentThread().getName());
            try {
                if (failure != null) {
                    throw failure;
                }
                calls.add("G:" + text);
            } finally {
                sent.countDown();
            }
        }

        @Override
        public void sendPrivate(String toUser, String text) {
            threads.add(Thread.currentThread().getName());
            calls.add("P:" + toUser + ":" + text);
            sent.countDown();
        }
    }

    private static Envelope deliver(String from, String to, String scope, String text) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("messageId", UUID.randomUUID().toString());
        p.put("fromUser", from);
        if (to != null) {
            p.put("toUser", to);
        }
        p.put("scope", scope);
        p.put("text", text);
        p.put("sentAt", Instant.now().toString());
        return new Envelope(1, EventType.CHAT_DELIVER, UUID.randomUUID(), "sid", p);
    }

    private static ChatPane newPane(ChatSender sender) throws Exception {
        return onFx(() -> new ChatPane("Alice", sender));
    }

    private static void groupSend() throws Exception {
        RecordingSender sender = new RecordingSender();
        ChatPane pane = newPane(sender);
        onFx(() -> {
            pane.input.setText("Hello team");
            pane.sendButton.fire();
            assertEquals("", pane.input.getText());
            return null;
        });
        assertTrue(sender.sent.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("G:Hello team"), sender.calls);
        assertEquals("netconnect-chat-send", sender.threads.get(0), "send must not run on the FX thread");
        pane.close();
    }

    private static void privateSend() throws Exception {
        RecordingSender sender = new RecordingSender();
        ChatPane pane = newPane(sender);
        pane.onRoster(List.of("Alice", "Bobby", "Carol"));
        onFx(() -> {
            pane.privateToggle.setSelected(true);
            assertEquals(List.of("Bobby", "Carol"), pane.recipientBox.getItems());
            pane.input.setText("psst");
            pane.sendButton.fire();
            assertEquals("Choose a user to message", pane.status.getText());
            assertEquals("psst", pane.input.getText(), "input kept when send refused");
            pane.recipientBox.setValue("Bobby");
            pane.sendButton.fire();
            return null;
        });
        assertTrue(sender.sent.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("P:Bobby:psst"), sender.calls);
        pane.close();
    }

    private static void localValidation() throws Exception {
        RecordingSender sender = new RecordingSender();
        ChatPane pane = newPane(sender);
        onFx(() -> {
            pane.input.setText("   ");
            pane.sendButton.fire();
            assertFalse(pane.status.getText().isEmpty());
            pane.input.setText("x".repeat(2001));
            pane.sendButton.fire();
            assertTrue(pane.status.getText().contains("2000"));
            assertEquals("2001/2000", pane.counter.getText());
            return null;
        });
        Thread.sleep(150);
        assertTrue(sender.calls.isEmpty());
        pane.close();
    }

    private static void senderFailure() throws Exception {
        RecordingSender sender = new RecordingSender();
        sender.failure = new java.io.IOException("socket closed");
        ChatPane pane = newPane(sender);
        onFx(() -> {
            pane.input.setText("hi");
            pane.sendButton.fire();
            return null;
        });
        assertTrue(sender.sent.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals("Could not send: IOException", onFx(() -> pane.status.getText()));
        pane.close();
    }

    private static void incoming() throws Exception {
        ChatPane pane = newPane(new RecordingSender());
        assertFalse(Platform.isFxApplicationThread());
        pane.onDeliver(deliver("Bobby", null, "GROUP", "hello all"));
        pane.onDeliver(deliver("Bobby", "Alice", "PRIVATE", "just you"));
        pane.onDeliver(deliver("Alice", "Bobby", "PRIVATE", "reply"));
        onFx(() -> {
            assertEquals(List.of("hello all"), pane.messageList.getItems().stream().map(ChatMessage::text).toList());
            pane.privateToggle.setSelected(true);
            assertEquals(List.of("Bobby"), pane.recipientBox.getItems());
            assertTrue(pane.messageList.getItems().isEmpty(), "no peer chosen yet");
            pane.recipientBox.setValue("Bobby");
            assertEquals(List.of("just you", "reply"), pane.messageList.getItems().stream().map(ChatMessage::text).toList());
            return null;
        });
        pane.close();
    }

    private static void duplicatesAndMalformed() throws Exception {
        ChatPane pane = newPane(new RecordingSender());
        Envelope once = deliver("Bobby", null, "GROUP", "dup");
        pane.onDeliver(once);
        pane.onDeliver(once);
        pane.onDeliver(new Envelope(1, EventType.CHAT_DELIVER, UUID.randomUUID(), "sid",
                JsonNodeFactory.instance.objectNode()));
        onFx(() -> {
            assertEquals(1, pane.messageList.getItems().size());
            assertEquals("Received an unreadable chat message", pane.status.getText());
            return null;
        });
        pane.close();
    }

    private static void errorsAndDisconnect() throws Exception {
        ChatPane pane = newPane(new RecordingSender());
        pane.onServerError("USER_OFFLINE", "Recipient is not online");
        assertEquals("That user is offline", onFx(() -> pane.status.getText()));
        pane.setSendingEnabled(false);
        onFx(() -> {
            assertTrue(pane.sendButton.isDisabled());
            assertTrue(pane.input.isDisabled());
            return null;
        });
        pane.setSendingEnabled(true);
        onFx(() -> {
            assertFalse(pane.sendButton.isDisabled());
            return null;
        });
        pane.close();
    }

    private static void peerLeaves() throws Exception {
        ChatPane pane = newPane(new RecordingSender());
        pane.onRoster(List.of("Alice", "Bobby"));
        pane.onDeliver(deliver("Bobby", "Alice", "PRIVATE", "bye"));
        pane.onRoster(List.of("Alice"));
        onFx(() -> {
            assertEquals(List.of("Bobby"), pane.recipientBox.getItems());
            return null;
        });
        pane.close();
    }
}
