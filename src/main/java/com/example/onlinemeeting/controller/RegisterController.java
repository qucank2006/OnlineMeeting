package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.config.AppConfig;
import com.example.onlinemeeting.client.TCPClient;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class RegisterController {

    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$");

    @FXML private TextField usernameField;
    @FXML private TextField emailField;
    @FXML private TextField displayNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button registerButton;
    @FXML private Button backButton;
    @FXML private Label statusLabel;

    // All form state belongs to the JavaFX application thread.
    private boolean submitting;
    private boolean registrationComplete;

    @FXML
    private void handleRegister() {
        if (submitting || registrationComplete) {
            return;
        }
        String username = usernameField.getText();
        String email = emailField.getText();
        String displayName = displayNameField.getText();
        String password = passwordField.getText();
        String confirmation = confirmPasswordField.getText();

        if (!safeText(username)) {
            showStatus("Tên đăng nhập phải có 3–50 ký tự hợp lệ.", true);
            return;
        }
        username = normalize(username);
        if (!lengthBetween(username, 3, 50)) {
            showStatus("Tên đăng nhập phải có 3–50 ký tự.", true);
            return;
        }
        if (!safeText(email)) {
            showStatus("Vui lòng nhập email hợp lệ, tối đa 255 ký tự.", true);
            return;
        }
        email = normalize(email);
        if (!lengthBetween(email, 1, 255) || !EMAIL.matcher(email).matches()) {
            showStatus("Vui lòng nhập email hợp lệ, tối đa 255 ký tự.", true);
            return;
        }
        if (!safeText(displayName) || !lengthBetween(displayName.strip(), 1, 100)) {
            showStatus("Tên hiển thị phải có 1–100 ký tự hợp lệ.", true);
            return;
        }
        if (!safeText(password)) {
            showStatus("Mật khẩu không hợp lệ; không dùng |, CR, LF hoặc NUL.", true);
            return;
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            showStatus("Mật khẩu phải có 8–72 byte UTF-8.", true);
            return;
        }
        if (!password.equals(confirmation)) {
            showStatus("Mật khẩu xác nhận không khớp.", true);
            return;
        }

        Stage stage = (Stage) registerButton.getScene().getWindow();
        Scene formScene = stage.getScene();
        String normalizedUsername = username;
        String normalizedEmail = email;
        String normalizedDisplayName = displayName.strip();
        setBusy(true);
        passwordField.clear();
        confirmPasswordField.clear();
        showStatus("Đang tạo tài khoản…", false);
        Thread worker = new Thread(() -> register(
                normalizedUsername, normalizedEmail, password, normalizedDisplayName,
                stage, formScene), "registration-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void register(String username, String email, String password,
                          String displayName, Stage stage, Scene formScene) {
        TCPClient client = new TCPClient();
        AtomicBoolean settled = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        Thread deadline = Thread.ofVirtual().name("registration-deadline").start(() -> {
            try {
                Thread.sleep(15_000);
                if (settled.compareAndSet(false, true)) {
                    worker.interrupt();
                    client.disconnect();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        boolean success = false;
        String message;
        try {
            AppConfig config = AppConfig.getInstance();
            client.connect(config.getClientServerHost(), config.getTcpPort());
            if (settled.get()) {
                throw new InterruptedException();
            }
            client.send("REGISTER|" + encode(username) + "|" + encode(email)
                    + "|" + encode(password) + "|" + encode(displayName));
            String response = client.receive();
            if (!settled.compareAndSet(false, true)) {
                throw new InterruptedException();
            }
            success = "REGISTER_SUCCESS".equals(response);
            message = success ? "Tạo tài khoản thành công. Vui lòng đăng nhập."
                    : failureMessage(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            message = timeoutMessage();
        } catch (Exception e) {
            message = settled.get() ? timeoutMessage()
                    : "Không thể kết nối hoặc đã mất kết nối tới máy chủ.";
        } finally {
            settled.set(true);
            deadline.interrupt();
            // Registration never retains an authenticated connection.
            client.disconnect();
        }

        boolean registered = success;
        String status = message;
        Platform.runLater(() -> {
            setBusy(false);
            if (!stage.isShowing() || stage.getScene() != formScene) {
                return;
            }
            showStatus(status, !registered);
            if (registered) {
                registrationComplete = true;
                registerButton.setDisable(true);
                try {
                    openLogin(username);
                } catch (Exception e) {
                    showStatus("Tài khoản đã được tạo. Nhấn Quay lại đăng nhập để tiếp tục.", false);
                }
            } else {
                passwordField.requestFocus();
            }
        });
    }

    private static String failureMessage(String response) {
        if (response == null || response.length() > 256) {
            return "Phản hồi đăng ký không hợp lệ từ máy chủ.";
        }
        String[] parts = response.split("\\|", -1);
        if (parts.length != 2 || !"REGISTER_FAILED".equals(parts[0])) {
            return "Phản hồi đăng ký không hợp lệ từ máy chủ.";
        }
        return switch (parts[1]) {
            case "ACCOUNT_ALREADY_EXISTS" -> "Tên đăng nhập hoặc email đã được sử dụng.";
            case "INVALID_USERNAME" -> "Tên đăng nhập không hợp lệ (3–50 ký tự).";
            case "INVALID_EMAIL" -> "Email không hợp lệ hoặc dài quá 255 ký tự.";
            case "INVALID_PASSWORD" -> "Mật khẩu không hợp lệ (8–72 byte UTF-8).";
            case "INVALID_DISPLAY_NAME" -> "Tên hiển thị không hợp lệ (1–100 ký tự).";
            case "SERVICE_UNAVAILABLE" -> "Dịch vụ đăng ký tạm thời không khả dụng.";
            case "MALFORMED_REQUEST" -> "Máy chủ không chấp nhận yêu cầu đăng ký.";
            default -> "Phản hồi đăng ký không hợp lệ từ máy chủ.";
        };
    }

    @FXML
    private void handleBackToLogin() {
        if (submitting) {
            return;
        }
        try {
            openLogin(registrationComplete ? normalize(usernameField.getText()) : null);
        } catch (Exception e) {
            showStatus("Không thể mở màn hình đăng nhập. Vui lòng thử lại.", true);
        }
    }

    private void openLogin(String registeredUsername) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/example/onlinemeeting/fxml/login-view.fxml"));
        Stage stage = (Stage) registerButton.getScene().getWindow();
        Scene scene = new Scene(loader.load(),
                stage.getScene().getWidth(), stage.getScene().getHeight());
        LoginController controller = loader.getController();
        if (registeredUsername != null) {
            controller.showRegistrationSuccess(registeredUsername);
        }
        passwordField.clear();
        confirmPasswordField.clear();
        stage.setScene(scene);
    }

    private void setBusy(boolean busy) {
        submitting = busy;
        usernameField.setDisable(busy);
        emailField.setDisable(busy);
        displayNameField.setDisable(busy);
        passwordField.setDisable(busy);
        confirmPasswordField.setDisable(busy);
        registerButton.setDisable(busy || registrationComplete);
        backButton.setDisable(busy);
        registerButton.setText(busy ? "Đang tạo tài khoản…" : "Tạo tài khoản");
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: " + (error ? "#f38ba8" : "#a6e3a1") + ";");
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean safeText(String value) {
        return value != null && !value.isBlank()
                && value.indexOf('|') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static boolean lengthBetween(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String timeoutMessage() {
        return "Đăng ký quá thời gian chờ. Tài khoản có thể đã được tạo; hãy thử đăng nhập trước khi đăng ký lại.";
    }
}
