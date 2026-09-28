package com.example.onlinemeeting.test;

import com.example.onlinemeeting.client.AudioClient;

public class AudioTestClient {

    public static void main(String[] args) {
        try {
            AudioClient audioClient =
                    new AudioClient("localhost", "9D6B0F");

            audioClient.start();

            System.out.println("Nhấn Enter để dừng...");
            System.in.read();

            audioClient.stop();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}