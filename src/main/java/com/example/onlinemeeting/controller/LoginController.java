package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.client.TCPClient;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private Label errorLabel;

    @FXML
    private void handleLogin() {

        String username = usernameField.getText().trim();

        if (username.isEmpty()) {
            errorLabel.setText("Vui lòng nhập tên của bạn.");
            return;
        }

        try {

            TCPClient client = new TCPClient();

            client.connect("localhost", 5000);

            client.send("LOGIN|" + username);

            String response = client.receive();

            if ("LOGIN_SUCCESS".equals(response)) {

                errorLabel.setText("");

                openHome(client, username);

                // Bước sau chuyển sang Home

            } else {
                errorLabel.setText("Không thể đăng nhập.");
            }

        } catch (Exception e) {
            errorLabel.setText(
                    "Không thể kết nối tới server."
            );
        }
    }

    private void openHome(
            TCPClient client,
            String username
    ) throws Exception {

        FXMLLoader loader =
                new FXMLLoader(
                        getClass().getResource(
                                "/com/example/onlinemeeting/fxml/home-view.fxml"
                        )
                );

        Scene scene =
                new Scene(loader.load(), 900, 600);

        HomeController controller =
                loader.getController();

        controller.setData(
                client,
                username
        );

        Stage stage =
                (Stage) usernameField
                        .getScene()
                        .getWindow();

        stage.setScene(scene);
    }
}