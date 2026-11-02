package com.example.onlinemeeting.test;

import com.example.onlinemeeting.database.DatabaseConnection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConnectionTest {

    public static void main(String[] args) {
        try (Connection connection = DatabaseConnection.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (result.next() && result.getInt(1) == 1) {
                System.out.println("Database connection succeeded: SELECT 1 returned 1.");
            } else {
                System.out.println("Database connection check failed: unexpected query result.");
            }
        } catch (SQLException e) {
            // Exception details may contain sensitive connection information.
            System.out.println("Database connection check failed.");
        }
    }
}
