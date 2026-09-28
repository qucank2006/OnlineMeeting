package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.client.AudioClient;
import com.example.onlinemeeting.client.TCPClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.application.Platform;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.Set;

public class MeetingController {

    @FXML
    private Label roomIdLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private Button micButton;

    @FXML
    private GridPane participantsGrid;

    @FXML
    private VBox chatBox;

    @FXML
    private TextField messageField;

    private final Set<String> participants =
            new LinkedHashSet<>();

    private TCPClient tcpClient;
    private AudioClient audioClient;

    private String username;
    private String roomId;

    private boolean muted = false;

    public void setData(
            TCPClient tcpClient,
            String username,
            String roomId
    ) {

        this.tcpClient = tcpClient;
        this.username = username;
        this.roomId = roomId;

        roomIdLabel.setText(
                "Room ID: " + roomId
        );

        participants.clear();
        participants.add(username);

        renderParticipants();

        tcpClient.setEventListener(
                this::handleServerEvent
        );

        startAudio();
    }
    private void handleServerEvent(String message) {

        Platform.runLater(() -> {

            if (message.startsWith("MEMBERS|")) {

                participants.clear();

                String data =
                        message.substring(
                                "MEMBERS|".length()
                        );

                if (!data.isBlank()) {

                    String[] users =
                            data.split(",");

                    for (String user : users) {
                        participants.add(user);
                    }
                }

                renderParticipants();

            } else if (
                    message.startsWith("USER_JOINED|")
            ) {

                String user =
                        message.substring(
                                "USER_JOINED|".length()
                        );

                participants.add(user);

                renderParticipants();

            } else if (
                    message.startsWith("USER_LEFT|")
            ) {

                String user =
                        message.substring(
                                "USER_LEFT|".length()
                        );

                participants.remove(user);

                renderParticipants();
            } else if (message.startsWith("CHAT|")) {

                handleChatEvent(message);
            }
        });
    }

    private void handleChatEvent(String message) {

        String[] parts =
                message.split("\\|", 3);

        if (parts.length < 3) {
            return;
        }

        String sender = parts[1];
        String content = parts[2];

        addChatMessage(
                sender,
                content
        );
    }

    private void addChatMessage(
            String sender,
            String content
    ) {

        Label senderLabel =
                new Label(sender);

        senderLabel.setStyle(
                "-fx-text-fill: white;" +
                        "-fx-font-weight: bold;"
        );

        Label messageLabel =
                new Label(content);

        messageLabel.setWrapText(true);

        messageLabel.setStyle(
                "-fx-text-fill: #dddddd;"
        );

        VBox messageBox =
                new VBox(
                        3,
                        senderLabel,
                        messageLabel
                );

        messageBox.setStyle(
                "-fx-background-color: #3c4043;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 8;"
        );

        chatBox.getChildren().add(
                messageBox
        );
    }

    private void renderParticipants() {

        participantsGrid.getChildren().clear();

        int index = 0;

        for (String participant : participants) {

            VBox tile =
                    createParticipantTile(
                            participant
                    );

            int column;
            int row;

            if (participants.size() <= 2) {

                column = index;
                row = 0;

            } else {

                column = index % 2;
                row = index / 2;
            }

            participantsGrid.add(
                    tile,
                    column,
                    row
            );

            index++;
        }
    }
    private VBox createParticipantTile(
            String participant
    ) {

        Label nameLabel =
                new Label(participant);

        nameLabel.setStyle(
                "-fx-text-fill: white;" +
                        "-fx-font-size: 24px;"
        );

        Label cameraLabel =
                new Label("Camera đang tắt");

        cameraLabel.setStyle(
                "-fx-text-fill: #aaaaaa;"
        );

        VBox tile =
                new VBox(
                        10,
                        nameLabel,
                        cameraLabel
                );

        tile.setAlignment(
                javafx.geometry.Pos.CENTER
        );

        tile.setStyle(
                "-fx-background-color: #292a2d;" +
                        "-fx-background-radius: 12;"
        );

        tile.setMinSize(
                300,
                220
        );

        tile.setMaxSize(
                Double.MAX_VALUE,
                Double.MAX_VALUE
        );

        GridPane.setHgrow(
                tile,
                javafx.scene.layout.Priority.ALWAYS
        );

        GridPane.setVgrow(
                tile,
                javafx.scene.layout.Priority.ALWAYS
        );

        return tile;
    }

    private void startAudio() {

        try {

            audioClient = new AudioClient(
                    "localhost",
                    roomId
            );

            audioClient.start();

            statusLabel.setText("Âm thanh đã kết nối");

        } catch (Exception e) {

            statusLabel.setText(
                    "Không thể khởi động âm thanh"
            );

            e.printStackTrace();
        }
    }

    @FXML
    private void handleMic() {

        if (audioClient == null) {
            return;
        }

        if (muted) {

            audioClient.unmute();

            muted = false;

            micButton.setText("🎤 Mic");

        } else {

            audioClient.mute();

            muted = true;

            micButton.setText("🔇 Bật mic");
        }
    }

    @FXML
    private void handleLeave() {

        try {
            if (audioClient != null) {
                audioClient.stop();
            }

            if (tcpClient != null) {
                tcpClient.send("LEAVE_ROOM");

                String response = tcpClient.receive();

                if (!"LEAVE_SUCCESS".equals(response)) {
                    statusLabel.setText("Không thể rời phòng.");
                    return;
                }

                // Quan trọng
                tcpClient.clearEventListener();
            }

            openHome();

        } catch (Exception e) {
            statusLabel.setText("Lỗi khi rời phòng.");
            e.printStackTrace();
        }
    }

    private void openHome() throws Exception {

        FXMLLoader loader =
                new FXMLLoader(
                        getClass().getResource(
                                "/com/example/onlinemeeting/fxml/home-view.fxml"
                        )
                );

        Scene scene =
                new Scene(
                        loader.load(),
                        900,
                        600
                );

        HomeController controller =
                loader.getController();

        // Dùng lại TCP connection hiện tại
        controller.setData(
                tcpClient,
                username
        );

        Stage stage =
                (Stage) roomIdLabel
                        .getScene()
                        .getWindow();

        stage.setScene(scene);
    }

    @FXML
    private void handleSendMessage() {

        String message =
                messageField.getText().trim();

        if (message.isEmpty()) {
            return;
        }

        if (tcpClient == null) {
            return;
        }

        tcpClient.send(
                "CHAT|" + message
        );

        messageField.clear();
    }
}