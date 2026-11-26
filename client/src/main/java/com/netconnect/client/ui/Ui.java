package com.netconnect.client.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import java.util.Locale;

/** Shared presentation primitives; never interprets or changes transport state. */
public final class Ui {
    private Ui() { }

    public enum Tone { SUCCESS, ERROR, WARNING, INFORMATION, NEUTRAL }

    public static void tone(Node node, Tone tone) {
        node.getStyleClass().removeAll("success", "error", "warning", "information", "neutral");
        node.getStyleClass().add(tone.name().toLowerCase(Locale.ROOT));
    }

    public static void feedback(Label label, Tone tone, String text) {
        if (!label.getStyleClass().contains("feedback")) label.getStyleClass().add("feedback");
        tone(label, tone);
        label.setText(text);
        label.setGraphic(icon(switch (tone) {
            case SUCCESS -> "check";
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFORMATION -> "info";
            case NEUTRAL -> "info";
        }));
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setAccessibleText(text);
    }

    public static StackPane icon(String name) {
        SVGPath path = new SVGPath();
        path.setContent(switch (name) {
            case "chat" -> "M3 3 H21 V17 H9 L3 22 Z";
            case "users" -> "M15 7 A4 4 0 1 1 7 7 A4 4 0 1 1 15 7 M3 21 V18 Q3 13 11 13 Q19 13 19 18 V21 M18 3 Q25 7 19 11 M21 14 Q24 16 24 21";
            case "file" -> "M5 2 H14 L20 8 V22 H5 Z M14 2 V8 H20 M9 13 H16 M9 17 H16";
            case "disconnect" -> "M12 2 V12 M6 5 A9 9 0 1 0 18 5";
            case "network" -> "M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M9 15 H15 V21 H9 Z M6 9 V12 H18 V9 M12 12 V15";
            case "check" -> "M3 12 L9 18 L21 5";
            case "error" -> "M12 2 A10 10 0 1 1 11.99 2 M8 8 L16 16 M16 8 L8 16";
            case "warning" -> "M12 2 L23 21 H1 Z M12 8 V14 M12 17 V18";
            default -> "M12 2 A10 10 0 1 1 11.99 2 M12 10 V18 M12 6 V7";
        });
        path.getStyleClass().add("icon-stroke");
        path.setScaleX(0.7);
        path.setScaleY(0.7);
        StackPane icon = new StackPane(path);
        icon.getStyleClass().add("ui-icon");
        icon.setMinSize(20, 20);
        icon.setPrefSize(20, 20);
        icon.setMaxSize(20, 20);
        icon.setMouseTransparent(true);
        icon.setFocusTraversable(false);
        return icon;
    }

    public static Label avatar(String name) {
        String[] words = name.strip().split("\\s+");
        String initials = name.isBlank() ? "?" : first(words[0]);
        if (words.length > 1) initials += first(words[words.length - 1]);
        Label avatar = new Label(initials.toUpperCase(Locale.ROOT));
        avatar.getStyleClass().add("avatar");
        avatar.setAccessibleText(name);
        return avatar;
    }

    private static String first(String text) { return new String(Character.toChars(text.codePointAt(0))); }

    public static String transferError(String code) {
        return switch (code == null ? "" : code) {
            case "USER_OFFLINE" -> "This person is no longer online. Choose another recipient or try again later.";
            case "TRANSFER_REJECTED" -> "The file request was declined.";
            case "TRANSFER_EXPIRED" -> "The file request expired. Send a new request.";
            case "HASH_MISMATCH", "CHECKSUM_MISMATCH" -> "File verification failed. Please send the file again.";
            case "FILE_TOO_LARGE", "TRANSFER_TOO_LARGE" -> "Choose a file no larger than 20 MiB.";
            case "ACCESS_DENIED", "INVALID_TICKET", "TICKET_EXPIRED" -> "This transfer could not be authorized. Send a new request.";
            case "INVALID_REQUEST", "INVALID_FILE_NAME" -> "Check the file and recipient, then try again.";
            default -> "The file could not be transferred. Check your connection and try again.";
        };
    }
}
