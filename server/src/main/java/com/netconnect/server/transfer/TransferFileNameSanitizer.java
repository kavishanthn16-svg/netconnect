package com.netconnect.server.transfer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Sanitizes file names to prevent path traversal (NFR-03, NFR-04).
 * Strips directory paths and invalid characters, ensuring the name is a safe single file name.
 */
public final class TransferFileNameSanitizer {

    private static final String DEFAULT_FILE_NAME = "unnamed_file";
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private TransferFileNameSanitizer() {
    }

    public static String sanitize(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            return DEFAULT_FILE_NAME;
        }

        // Normalize slashes
        String normalized = rawFileName.replace('\\', '/');

        // Extract the base name after the last '/' without using Paths.get (which fails on Windows for illegal chars)
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;

        // Strip dangerous path traversal characters and illegal symbols
        String cleaned = baseName.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_");
        cleaned = cleaned.replace("..", "_").trim();

        // Remove leading/trailing dots, spaces, or underscores
        while (!cleaned.isEmpty() && (cleaned.startsWith(".") || cleaned.startsWith(" ") || cleaned.startsWith("_"))) {
            cleaned = cleaned.substring(1);
        }
        while (!cleaned.isEmpty() && (cleaned.endsWith(".") || cleaned.endsWith(" ") || cleaned.endsWith("_"))) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        if (cleaned.isBlank()) {
            return DEFAULT_FILE_NAME;
        }

        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_FILE_NAME_LENGTH);
        }

        return cleaned;
    }
}
