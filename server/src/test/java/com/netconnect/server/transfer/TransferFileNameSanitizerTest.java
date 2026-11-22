package com.netconnect.server.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransferFileNameSanitizerTest {

    @Test
    @DisplayName("Normal file names remain unchanged")
    void normalFileNames() {
        assertEquals("report.pdf", TransferFileNameSanitizer.sanitize("report.pdf"));
        assertEquals("image_2026.png", TransferFileNameSanitizer.sanitize("image_2026.png"));
    }

    @Test
    @DisplayName("Path traversal sequences are stripped to safe base name")
    void pathTraversalPrevented() {
        assertEquals("passwd", TransferFileNameSanitizer.sanitize("../../etc/passwd"));
        assertEquals("cmd.exe", TransferFileNameSanitizer.sanitize("C:\\Windows\\System32\\cmd.exe"));
        assertEquals("secret.doc", TransferFileNameSanitizer.sanitize("/var/log/../../../secret.doc"));
    }

    @Test
    @DisplayName("Illegal characters and control bytes are replaced")
    void illegalCharactersReplaced() {
        String sanitized = TransferFileNameSanitizer.sanitize("bad<name>:file|test?.txt");
        assertFalse(sanitized.contains("<"));
        assertFalse(sanitized.contains(">"));
        assertFalse(sanitized.contains(":"));
        assertFalse(sanitized.contains("|"));
        assertFalse(sanitized.contains("?"));
    }

    @Test
    @DisplayName("Null, empty, or blank file names return safe default")
    void nullOrBlankReturnsDefault() {
        assertEquals("unnamed_file", TransferFileNameSanitizer.sanitize(null));
        assertEquals("unnamed_file", TransferFileNameSanitizer.sanitize(""));
        assertEquals("unnamed_file", TransferFileNameSanitizer.sanitize("   "));
        assertEquals("unnamed_file", TransferFileNameSanitizer.sanitize(".."));
    }
}
