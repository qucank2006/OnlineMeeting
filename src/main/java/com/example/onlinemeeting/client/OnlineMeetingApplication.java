package com.example.onlinemeeting.client;

import com.example.onlinemeeting.config.AppConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class OnlineMeetingApplication extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Fail clearly before showing forms if network configuration is invalid.
        AppConfig.getInstance();

        FXMLLoader loader = new FXMLLoader(
                OnlineMeetingApplication.class.getResource(
                        "/com/example/onlinemeeting/fxml/login-view.fxml"
                )
        );

        Scene scene = new Scene(loader.load(), 900, 600);

        stage.setTitle("OnlineMeeting");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}