module com.example.onlinemeeting {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.sql;
    requires jbcrypt;


    exports com.example.onlinemeeting.client;
    exports com.example.onlinemeeting.server;
    exports com.example.onlinemeeting.model;

    opens com.example.onlinemeeting.controller to javafx.fxml;
}