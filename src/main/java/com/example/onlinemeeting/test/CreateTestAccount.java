package com.example.onlinemeeting.test;

import com.example.onlinemeeting.service.AuthService;

public class CreateTestAccount {

    public static void main(String[] args) {

        AuthService authService = new AuthService();

        var result = authService.register(
                "qank",
                "qank@example.com",
                "Qank@123456",
                "Qank"
        );

        System.out.println("Registration status: " + result.status());
    }
}