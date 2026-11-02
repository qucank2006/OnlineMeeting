package com.example.onlinemeeting.database;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    private static final int CONNECTION_TIMEOUT_MILLIS = 5000;

    /**
     * Opens a new connection. The caller must close it, preferably using
     * try-with-resources.
     */
    public static Connection getConnection() throws SQLException {
        Properties configuration = loadConfiguration();
        String host = requiredProperty(configuration, "db.host").trim();
        String port = requiredProperty(configuration, "db.port").trim();
        String database = requiredProperty(configuration, "db.name").trim();
        String username = requiredProperty(configuration, "db.username");
        String password = requiredProperty(configuration, "db.password");

        try {
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new SQLException(
                    "Invalid database.properties: db.port must be an integer between 1 and 65535");
        }

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        // Connector/J timeout in milliseconds; avoids changing global JDBC settings.
        properties.setProperty("connectTimeout",
                Integer.toString(CONNECTION_TIMEOUT_MILLIS));

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        return DriverManager.getConnection(url, properties);
    }

    private static Properties loadConfiguration() throws SQLException {
        Properties configuration = new Properties();
        try (InputStream input = DatabaseConnection.class.getResourceAsStream(
                "/database.properties")) {
            if (input == null) {
                throw new SQLException("Missing database.properties on the classpath");
            }
            configuration.load(input);
            return configuration;
        } catch (IOException | IllegalArgumentException e) {
            // Avoid including parser details that could expose property values.
            throw new SQLException("Unable to read database.properties: unreadable or invalid format");
        }
    }

    private static String requiredProperty(Properties configuration, String name)
            throws SQLException {
        String value = configuration.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new SQLException(
                    "Invalid database.properties: required property is missing or blank: " + name);
        }
        return value;
    }
}
