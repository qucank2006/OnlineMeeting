package com.example.onlinemeeting.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

/** Immutable network settings loaded once from the application classpath. */
public final class AppConfig {
    private static AppConfig instance;

    private final String serverBindHost;
    private final String clientServerHost;
    private final int tcpPort;
    private final int udpPort;
    private final int videoPort;

    private AppConfig(Properties properties) {
        serverBindHost = host(properties, "server.host");
        clientServerHost = host(properties, "client.server.host");
        tcpPort = port(properties, "server.tcp.port");
        udpPort = port(properties, "server.udp.port");
        videoPort = port(properties, "server.video.port");
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            Properties properties = new Properties();
            try (InputStream input = AppConfig.class.getResourceAsStream("/application.properties")) {
                if (input == null) {
                    throw new IllegalStateException("Missing application.properties on the classpath");
                }
                properties.load(input);
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Unable to read application.properties: invalid or unreadable file", e);
            }
            instance = new AppConfig(properties);
        }
        return instance;
    }

    public String getServerBindHost() {
        return serverBindHost;
    }

    public String getClientServerHost() {
        return clientServerHost;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public int getVideoPort() { return videoPort; }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Invalid application.properties: missing or blank " + name);
        }
        return value.strip();
    }

    private static String host(Properties properties, String name) {
        String value = required(properties, name);
        try {
            // Validate syntax without DNS lookups or opening any sockets.
            new URI("tcp", null, value, -1, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid application.properties: " + name
                    + " must be a hostname or IP address without a scheme or port");
        }
        return value;
    }

    private static int port(Properties properties, String name) {
        String value = required(properties, name);
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Report the property name and required range.
        }
        throw new IllegalStateException("Invalid application.properties: " + name
                + " must be an integer between 1 and 65535");
    }
}
