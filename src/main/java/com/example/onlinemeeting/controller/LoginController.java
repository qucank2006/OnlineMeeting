package com.example.onlinemeeting.controller;

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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Button createAccountButton;
    @FXML private Label errorLabel;

    // Accessed only on the JavaFX application thread.
    private boolean loginInProgress;

    @FXML
    private void handleCreateAccount() {
        if (loginInProgress) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/example/onlinemeeting/fxml/register-view.fxml"));
            Stage stage = (Stage) usernameField.getScene().getWindow();
            Scene scene = new Scene(loader.load(),
                    stage.getScene().getWidth(), stage.getScene().getHeight());
            passwordField.clear();
            stage.setScene(scene);
        } catch (Exception e) {
            showStatus("Không thể mở màn hình tạo tài khoản.", true);
        }
    }

    // Called on the JavaFX thread after returning from registration.
    void showRegistrationSuccess(String username) {
        usernameField.setText(username);
        passwordField.clear();
        showStatus("Tạo tài khoản thành công. Vui lòng đăng nhập.", false);
        errorLabel.setStyle("-fx-text-fill: #a6e3a1;");
        Platform.runLater(passwordField::requestFocus);
    }

    private record LoginIdentity(long userId, String username, String displayName) {
    }

    @FXML
    private void handleLogin() {
        if (loginInProgress) {
            return;
        }
        String identifier = usernameField.getText().strip();
        String password = passwordField.getText();
        if (!safeText(identifier) || !safeText(password)) {
            showStatus("Vui lòng nhập tài khoản và mật khẩu hợp lệ.", true);
            return;
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (identifier.codePointCount(0, identifier.length()) > 255
                || passwordBytes < 8 || passwordBytes > 72) {
            showStatus("Tài khoản hoặc mật khẩu không hợp lệ.", true);
            return;
        }

        Stage stage = (Stage) usernameField.getScene().getWindow();
        Scene loginScene = stage.getScene();
        setBusy(true);
        passwordField.clear();
        showStatus("Đang đăng nhập…", false);

        Thread worker = new Thread(
                () -> authenticate(identifier, password, stage, loginScene),
                "login-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void authenticate(String identifier, String password,
                              Stage stage, Scene loginScene) {
        TCPClient client = new TCPClient();
        AtomicBoolean settled = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        Thread deadline = Thread.ofVirtual().name("login-deadline").start(() -> {
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

        boolean handedToUi = false;
        try {
            client.connect("localhost", 5000);
            if (settled.get()) {
                throw new InterruptedException();
            }
            client.send("LOGIN|" + encode(identifier) + "|" + encode(password));
            String response = client.receive();
            LoginIdentity identity = parseResponse(response);
            if (!settled.compareAndSet(false, true)) {
                throw new InterruptedException();
            }
            Platform.runLater(() -> {
                if (!stage.isShowing() || stage.getScene() != loginScene) {
                    disconnectInBackground(client);
                    setBusy(false);
                    return;
                }
                try {
                    openHome(client, identity.username(), stage);
                } catch (Exception e) {
                    disconnectInBackground(client);
                    showStatus("Không thể mở màn hình chính. Vui lòng thử lại.", true);
                } finally {
                    setBusy(false);
                }
            });
            handedToUi = true;
        } catch (LoginFailure e) {
            publishFailure(e.getMessage(), stage, loginScene);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publishFailure("Đăng nhập quá thời gian chờ. Vui lòng thử lại.", stage, loginScene);
        } catch (Exception e) {
            publishFailure(settled.get()
                    ? "Đăng nhập quá thời gian chờ. Vui lòng thử lại."
                    : "Không thể kết nối hoặc đã mất kết nối tới máy chủ.",
                    stage, loginScene);
        } finally {
            settled.set(true);
            deadline.interrupt();
            if (!handedToUi) {
                client.disconnect();
            }
        }
    }

    private LoginIdentity parseResponse(String response) throws LoginFailure {
        if (response == null || response.length() > 4096) {
            throw malformedResponse();
        }
        String[] parts = response.split("\\|", -1);
        if (parts.length == 2 && "LOGIN_FAILED".equals(parts[0])
                && parts[1].matches("[A-Z_]{1,64}")) {
            throw new LoginFailure(switch (parts[1]) {
                case "INVALID_CREDENTIALS" -> "Tài khoản hoặc mật khẩu không đúng.";
                case "SERVICE_UNAVAILABLE" -> "Dịch vụ đăng nhập tạm thời không khả dụng.";
                case "ALREADY_AUTHENTICATED" -> "Kết nối đã đăng nhập. Vui lòng thử lại.";
                case "MALFORMED_REQUEST" -> "Máy chủ không chấp nhận yêu cầu đăng nhập.";
                default -> "Đăng nhập không thành công. Vui lòng thử lại.";
            });
        }
        if (parts.length != 4 || !"LOGIN_SUCCESS".equals(parts[0])) {
            throw malformedResponse();
        }
        try {
            long id = Long.parseLong(parts[1]);
            String displayName = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(parts[3]))).toString();
            if (id <= 0 || !safeText(parts[2]) || !safeText(displayName)
                    || parts[2].codePointCount(0, parts[2].length()) > 50
                    || displayName.codePointCount(0, displayName.length()) > 100) {
                throw malformedResponse();
            }
            return new LoginIdentity(id, parts[2], displayName);
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw malformedResponse();
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean safeText(String value) {
        return value != null && !value.isBlank()
                && value.indexOf('|') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static LoginFailure malformedResponse() {
        return new LoginFailure("Phản hồi đăng nhập không hợp lệ từ máy chủ.");
    }

    private static class LoginFailure extends Exception {
        LoginFailure(String message) {
            super(message);
        }
    }

    private void publishFailure(String message, Stage stage, Scene loginScene) {
        Platform.runLater(() -> {
            setBusy(false);
            if (stage.isShowing() && stage.getScene() == loginScene) {
                showStatus(message, true);
                passwordField.requestFocus();
            }
        });
    }

    private void setBusy(boolean busy) {
        loginInProgress = busy;
        usernameField.setDisable(busy);
        passwordField.setDisable(busy);
        loginButton.setDisable(busy);
        createAccountButton.setDisable(busy);
        loginButton.setText(busy ? "Đang đăng nhập…" : "Đăng nhập");
    }

    private void showStatus(String message, boolean error) {
        errorLabel.setText(message);
        errorLabel.setStyle("-fx-text-fill: " + (error ? "#f38ba8" : "#a6adc8") + ";");
    }

    private static void disconnectInBackground(TCPClient client) {
        Thread.ofVirtual().name("login-cleanup").start(client::disconnect);
    }

    private void openHome(TCPClient client, String username, Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/example/onlinemeeting/fxml/home-view.fxml"));
        Scene scene = new Scene(loader.load(), 900, 600);
        HomeController controller = loader.getController();
        controller.setData(client, username);
        stage.setScene(scene);
    }
}
