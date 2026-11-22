package com.netconnect.client.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FileTransferPaneTest {

    @Test
    @DisplayName("Constructs pane and verifies formatting helpers")
    void constructionAndFormatting() {
        FileTransferPane pane = new FileTransferPane();
        pane.setUiDispatcher(Runnable::run);
        assertNotNull(pane);

        // Byte formatting tests
        assertEquals("0 B", FileTransferPane.formatBytes(0));
        assertEquals("500 B", FileTransferPane.formatBytes(500));
        assertEquals("1.0 KiB", FileTransferPane.formatBytes(1024));
        assertEquals("1.5 KiB", FileTransferPane.formatBytes(1536));
        assertEquals("1.00 MiB", FileTransferPane.formatBytes(1024 * 1024));
        assertEquals("20.00 MiB", FileTransferPane.formatBytes(20 * 1024 * 1024));

        pane.shutdown();
    }

    @Test
    @DisplayName("Incoming offer, user roster, and reset are processed safely")
    void incomingOfferAndReset() {
        FileTransferPane pane = new FileTransferPane();
        pane.setUiDispatcher(Runnable::run);

        pane.updateOnlineUsers(List.of("Alice", "Bob", "Carol"));

        UUID transferId = UUID.randomUUID();
        pane.onFileOffered(transferId, "Alice", "test_report.pdf", 1024,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        assertFalse(pane.getChildren().isEmpty());

        pane.onFileResult(transferId, "SUCCESS", "SUCCESS", "Completed successfully");
        pane.resetForConnection();

        pane.shutdown();
    }
}
