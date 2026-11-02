package com.example.onlinemeeting.test;

import com.example.onlinemeeting.service.AuthService;
import com.example.onlinemeeting.service.AuthService.AuthResult;
import com.example.onlinemeeting.service.AuthService.Status;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Standalone database integration checks. Running this class creates two
 * uniquely named accounts in the configured database and leaves them there.
 */
public class AuthServiceTest {

    public static void main(String[] args) {
        AuthService service = new AuthService();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "auth_" + suffix;
        String email = username + "@example.com";
        String password = UUID.randomUUID().toString();

        AuthResult registered = attempt(
                () -> service.register(username, email, password, "Auth Test"));
        boolean registrationPassed = hasStatus(registered, Status.REGISTERED)
                && registered.userId() != null && registered.userId() > 0
                && username.equals(registered.username());
        int passed = report("Registration with unique username and email", registrationPassed);

        AuthResult usernameLogin = attempt(() -> service.login(username, password));
        passed += report("Login using username",
                registrationPassed && sameUser(usernameLogin, registered));

        AuthResult emailLogin = attempt(() -> service.login(email, password));
        passed += report("Login using email",
                registrationPassed && sameUser(emailLogin, registered));

        AuthResult wrongPassword = attempt(
                () -> service.login(username, password + "incorrect"));
        passed += report("Incorrect password rejected",
                registrationPassed && hasStatus(wrongPassword, Status.INVALID_CREDENTIALS)
                        && wrongPassword.userId() == null);

        AuthResult duplicate = attempt(
                () -> service.register(username, email, password, "Duplicate Test"));
        passed += report("Duplicate registration rejected",
                registrationPassed && hasStatus(duplicate, Status.ACCOUNT_ALREADY_EXISTS));

        String unicodeUsername = "unicode_" + suffix;
        String unicodeEmail = unicodeUsername + "@example.com";
        String unicodePassword = "MậtKhẩuViệt🔐😊";
        AuthResult unicodeRegistered = attempt(() -> service.register(
                unicodeUsername, unicodeEmail, unicodePassword, "Unicode Test"));
        AuthResult unicodeLogin = attempt(
                () -> service.login(unicodeUsername, unicodePassword));
        AuthResult changedUnicodeLogin = attempt(
                () -> service.login(unicodeUsername, unicodePassword + "x"));
        passed += report("Vietnamese and emoji password registration and verification",
                hasStatus(unicodeRegistered, Status.REGISTERED)
                        && unicodeRegistered.userId() != null
                        && sameUser(unicodeLogin, unicodeRegistered)
                        && hasStatus(changedUnicodeLogin, Status.INVALID_CREDENTIALS));

        System.out.println("Results: " + passed + "/6 PASS, " + (6 - passed) + "/6 FAIL.");
    }

    private static AuthResult attempt(Supplier<AuthResult> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            // Do not print exception details, passwords, hashes, or configuration.
            return null;
        }
    }

    private static boolean hasStatus(AuthResult result, Status expected) {
        return result != null && result.status() == expected;
    }

    private static boolean sameUser(AuthResult login, AuthResult registered) {
        return hasStatus(login, Status.LOGGED_IN)
                && registered != null
                && Objects.equals(login.userId(), registered.userId())
                && Objects.equals(login.username(), registered.username());
    }

    private static int report(String scenario, boolean passed) {
        System.out.println((passed ? "PASS: " : "FAIL: ") + scenario);
        return passed ? 1 : 0;
    }
}
