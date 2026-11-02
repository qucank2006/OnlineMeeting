package com.example.onlinemeeting.service;

import com.example.onlinemeeting.database.UserDAO;
import com.example.onlinemeeting.model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class AuthService {

    private static final int BCRYPT_COST = 12;
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$");
    // Missing accounts still incur BCrypt verification work.
    private static final String DUMMY_HASH =
            BCrypt.hashpw("unused-authentication-value", BCrypt.gensalt(BCRYPT_COST));

    private final UserDAO userDAO = new UserDAO();

    public enum Status {
        REGISTERED,
        LOGGED_IN,
        INVALID_USERNAME,
        INVALID_EMAIL,
        INVALID_PASSWORD,
        INVALID_DISPLAY_NAME,
        ACCOUNT_ALREADY_EXISTS,
        INVALID_CREDENTIALS,
        SERVICE_UNAVAILABLE
    }

    /**
     * Contains only safe identity data, never a User carrying a password hash.
     * Identity fields are null on failure.
     */
    public record AuthResult(Status status, Long userId,
                             String username, String displayName) {
        public boolean isSuccess() {
            return status == Status.REGISTERED || status == Status.LOGGED_IN;
        }
    }

    /**
     * Usernames and emails are stripped and lowercased using Locale.ROOT.
     * Display names are stripped; passwords are preserved exactly.
     */
    public AuthResult register(String username, String email, String password,
                               String displayName) {
        if (!safeField(username) || !lengthBetween(username.strip(), 3, 50)) {
            return failure(Status.INVALID_USERNAME);
        }
        if (!safeField(email)) {
            return failure(Status.INVALID_EMAIL);
        }
        if (!validPassword(password)) {
            return failure(Status.INVALID_PASSWORD);
        }
        if (!safeField(displayName) || !lengthBetween(displayName.strip(), 1, 100)) {
            return failure(Status.INVALID_DISPLAY_NAME);
        }

        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email);
        if (!lengthBetween(normalizedUsername, 3, 50)) {
            return failure(Status.INVALID_USERNAME);
        }
        if (!lengthBetween(normalizedEmail, 1, 255)
                || !EMAIL.matcher(normalizedEmail).matches()) {
            return failure(Status.INVALID_EMAIL);
        }

        try {
            boolean usernameExists = userDAO.existsByUsername(normalizedUsername);
            boolean emailExists = userDAO.existsByEmail(normalizedEmail);
            if (usernameExists || emailExists) {
                return failure(Status.ACCOUNT_ALREADY_EXISTS);
            }

            String hash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST));
            User user = userDAO.createUser(
                    normalizedUsername, normalizedEmail, hash, displayName.strip());
            return success(Status.REGISTERED, user);
        } catch (SQLException e) {
            // Unique constraints are authoritative when registrations race.
            return failure(isDuplicateKey(e)
                    ? Status.ACCOUNT_ALREADY_EXISTS : Status.SERVICE_UNAVAILABLE);
        }
    }

    public AuthResult login(String identifier, String password) {
        if (!safeField(identifier) || !validPassword(password)) {
            return failure(Status.INVALID_CREDENTIALS);
        }
        String normalizedIdentifier = normalize(identifier);
        if (!lengthBetween(normalizedIdentifier, 1, 255)) {
            return failure(Status.INVALID_CREDENTIALS);
        }

        try {
            Optional<User> found = userDAO.findByUsernameOrEmail(normalizedIdentifier);
            String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
            boolean matches;
            try {
                matches = BCrypt.checkpw(password, hash);
            } catch (IllegalArgumentException e) {
                // Malformed stored hashes must not escape or reveal account details.
                return failure(Status.INVALID_CREDENTIALS);
            }
            if (found.isEmpty() || !matches) {
                return failure(Status.INVALID_CREDENTIALS);
            }
            return success(Status.LOGGED_IN, found.get());
        } catch (SQLException e) {
            return failure(Status.SERVICE_UNAVAILABLE);
        }
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean safeField(String value) {
        return value != null && !value.isBlank()
                && value.indexOf('|') < 0
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0
                && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static boolean validPassword(String password) {
        if (!safeField(password)) {
            return false;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 8 && bytes <= 72;
    }

    private static boolean lengthBetween(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }

    private static boolean isDuplicateKey(SQLException exception) {
        for (Throwable cause : exception) {
            if (cause instanceof SQLException current
                    && (current.getErrorCode() == 1062
                    || "23505".equals(current.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static AuthResult failure(Status status) {
        return new AuthResult(status, null, null, null);
    }

    private static AuthResult success(Status status, User user) {
        return new AuthResult(status, user.getId(), user.getUsername(), user.getDisplayName());
    }
}
