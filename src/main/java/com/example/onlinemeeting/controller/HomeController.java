package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.client.TCPClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.VBox;
import java.util.Locale;
import javafx.stage.Stage;

public class HomeController {

    @FXML
    private Label welcomeLabel;

    @FXML
    private TextField roomIdField;

    @FXML
    private Label errorLabel;

    @FXML private Label accountLabel;
    @FXML private Label accountAvatar;
    @FXML private Label connectionLabel;
    @FXML private GridPane homeActionGrid;
    @FXML private VBox newMeetingCard;
    @FXML private VBox joinMeetingCard;

    @FXML
    private void initialize() {
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());
        homeActionGrid.widthProperty().addListener((observable, before, width) ->
                arrangeActionCards(width.doubleValue()));
        arrangeActionCards(0);
    }

    private void arrangeActionCards(double width) {
        boolean stacked = width < 620;
        int count = stacked ? 1 : 2;
        if (homeActionGrid.getColumnConstraints().size() != count) {
            homeActionGrid.getColumnConstraints().clear();
            for (int i = 0; i < count; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(100.0 / count);
                column.setMinWidth(0);
                column.setHgrow(javafx.scene.layout.Priority.ALWAYS);
                homeActionGrid.getColumnConstraints().add(column);
            }
        }
        GridPane.setColumnIndex(newMeetingCard, 0);
        GridPane.setRowIndex(newMeetingCard, 0);
        GridPane.setColumnIndex(joinMeetingCard, stacked ? 0 : 1);
        GridPane.setRowIndex(joinMeetingCard, stacked ? 1 : 0);
    }

    private TCPClient client;
    private String username;

    public void setData(TCPClient client, String username) {
        this.client = client;
        this.username = username;

        client.clearEventListener();

        welcomeLabel.setText("Xin chào, " + username);
        accountLabel.setText(username);
        accountAvatar.setText(username.isEmpty() ? "U"
                : username.substring(0, username.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
        try {
            connectionLabel.setText("Đã kết nối · " + client.getConnectedServerAddress().getHostAddress());
            connectionLabel.getStyleClass().remove("disconnected");
        } catch (IllegalStateException e) {
            connectionLabel.setText("Không có kết nối");
            if (!connectionLabel.getStyleClass().contains("disconnected"))
                connectionLabel.getStyleClass().add("disconnected");
        }
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

            Stage stage =
                    (Stage) welcomeLabel
                            .getScene()
                            .getWindow();

            Scene scene =
                    new Scene(
                            loader.load(),
                            stage.getScene().getWidth(),
                            stage.getScene().getHeight()
                    );

            MeetingController controller =
                    loader.getController();

            controller.setData(
                    client,
                    username,
                    roomId
            );

            stage.setScene(scene);

        } catch (Exception e) {

            errorLabel.setText(
                    "Không thể mở phòng họp."
            );

            e.printStackTrace();
        }
    }
}