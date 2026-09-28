package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.client.TCPClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class HomeController {

    @FXML
    private Label welcomeLabel;

    @FXML
    private TextField roomIdField;

    @FXML
    private Label errorLabel;

    private TCPClient client;
    private String username;

    public void setData(TCPClient client, String username) {
        this.client = client;
        this.username = username;

        client.clearEventListener();

        welcomeLabel.setText("Xin chào, " + username);
    }

    @FXML
    private void handleCreateRoom() {

        try {
            client.send("CREATE_ROOM");

            String response = client.receive();

            if (response != null &&
                    response.startsWith("ROOM_CREATED|")) {

                String roomId =
                        response.substring("ROOM_CREATED|".length());

                openMeeting(roomId);

            } else {
                errorLabel.setText("Không thể tạo phòng.");
            }

        } catch (Exception e) {
            errorLabel.setText("Mất kết nối tới server.");
        }
    }

    @FXML
    private void handleJoinRoom() {

        String roomId =
                roomIdField.getText()
                        .trim()
                        .toUpperCase();

        if (roomId.isEmpty()) {
            errorLabel.setText("Vui lòng nhập Room ID.");
            return;
        }

        try {
            client.send("JOIN_ROOM|" + roomId);

            String response = client.receive();

            if (response != null &&
                    response.startsWith("JOIN_SUCCESS|")) {

                openMeeting(roomId);

            } else if (response != null &&
                    response.contains("ROOM_NOT_FOUND")) {

                errorLabel.setText("Không tìm thấy phòng.");

            } else {
                errorLabel.setText("Không thể tham gia phòng.");
            }

        } catch (Exception e) {
            errorLabel.setText("Mất kết nối tới server.");
        }
    }

    private void openMeeting(String roomId) {

        try {

            FXMLLoader loader =
                    new FXMLLoader(
                            getClass().getResource(
                                    "/com/example/onlinemeeting/fxml/meeting-view.fxml"
                            )
                    );

            Scene scene =
                    new Scene(
                            loader.load(),
                            1000,
                            650
                    );

            MeetingController controller =
                    loader.getController();

            controller.setData(
                    client,
                    username,
                    roomId
            );

            Stage stage =
                    (Stage) welcomeLabel
                            .getScene()
                            .getWindow();

            stage.setScene(scene);

        } catch (Exception e) {

            errorLabel.setText(
                    "Không thể mở phòng họp."
            );

            e.printStackTrace();
        }
    }
}