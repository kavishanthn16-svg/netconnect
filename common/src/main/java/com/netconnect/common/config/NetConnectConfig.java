package com.netconnect.common.config;

import java.nio.file.Path;

/** Non-secret runtime configuration with the fixed SRS defaults. */
public record NetConnectConfig(
        String serverHost,
        int controlPort,
        int udpPort,
        int transferPort,
        Path fileStagingDirectory,
        Path clientDownloadDirectory) {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_CONTROL_PORT = 5050;
    public static final int DEFAULT_UDP_PORT = 5051;
    public static final int DEFAULT_TRANSFER_PORT = 5052;
    public static final String DEFAULT_STAGING_DIR = "staging";
    public static final String DEFAULT_DOWNLOAD_DIR = "downloads";

    public NetConnectConfig {
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("serverHost must not be blank");
        }
        validatePort(controlPort, "controlPort");
        validatePort(udpPort, "udpPort");
        validatePort(transferPort, "transferPort");
        if (fileStagingDirectory == null || clientDownloadDirectory == null) {
            throw new IllegalArgumentException("Configured directories must not be null");
        }
    }

    public static NetConnectConfig defaults() {
        return new NetConnectConfig(DEFAULT_HOST, DEFAULT_CONTROL_PORT, DEFAULT_UDP_PORT,
                DEFAULT_TRANSFER_PORT, Path.of(DEFAULT_STAGING_DIR), Path.of(DEFAULT_DOWNLOAD_DIR));
    }

    public static NetConnectConfig load() {
        NetConnectConfig defaults = defaults();
        return new NetConnectConfig(
                value("NETCONNECT_SERVER_HOST", defaults.serverHost()),
                integer("NETCONNECT_CONTROL_PORT", defaults.controlPort()),
                integer("NETCONNECT_UDP_PORT", defaults.udpPort()),
                integer("NETCONNECT_TRANSFER_PORT", defaults.transferPort()),
                Path.of(value("NETCONNECT_FILE_STAGING_DIR", defaults.fileStagingDirectory().toString())),
                Path.of(value("NETCONNECT_CLIENT_DOWNLOAD_DIR", defaults.clientDownloadDirectory().toString())));
    }

    private static String value(String name, String fallback) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? fallback : environment;
    }

    private static int integer(String name, int fallback) {
        String value = value(name, Integer.toString(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }
}
