package com.example.onlinemeeting.database;

import com.example.onlinemeeting.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

public class UserDAO {

    private static final String USER_COLUMNS =
            "id, username, email, password_hash, display_name, created_at, updated_at";

    public Optional<User> findByUsernameOrEmail(String identifier) throws SQLException {
        String sql = "SELECT " + USER_COLUMNS
                + " FROM users WHERE username = ? OR email = ? LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);
            statement.setString(2, identifier);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapUser(result)) : Optional.empty();
            }
        }
    }

    public boolean existsByUsername(String username) throws SQLException {
        return exists("SELECT 1 FROM users WHERE username = ? LIMIT 1", username);
    }

    public boolean existsByEmail(String email) throws SQLException {
        return exists("SELECT 1 FROM users WHERE email = ? LIMIT 1", email);
    }

    private boolean exists(String sql, String value) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Stores a hash supplied by the service layer; does not hash passwords.
     * Unique constraint violations propagate as SQLException.
     */
    public User createUser(String username, String email, String passwordHash,
                           String displayName) throws SQLException {
        String sql = "INSERT INTO users "
                + "(username, email, password_hash, display_name, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            statement.setString(4, displayName);

            if (statement.executeUpdate() != 1) {
                throw new SQLException("Creating user did not insert exactly one row");
            }

            long id;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Creating user did not return a generated ID");
                }
                id = keys.getLong(1);
            }

            // Read back database timestamps rather than guessing their values.
            String selectSql = "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?";
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setLong(1, id);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Created user could not be retrieved");
                    }
                    return mapUser(result);
                }
            }
        }
    }

    private User mapUser(ResultSet result) throws SQLException {
        Timestamp createdAt = result.getTimestamp("created_at");
        Timestamp updatedAt = result.getTimestamp("updated_at");
        return new User(
                result.getLong("id"),
                result.getString("username"),
                result.getString("email"),
                result.getString("password_hash"),
                result.getString("display_name"),
                createdAt == null ? null : createdAt.toLocalDateTime(),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }
}
