package com.example.onlinemeeting.test;

import com.example.onlinemeeting.client.TCPClient;

public class TCPTestClient {

    public static void main(String[] args) {
        try {
            TCPClient client = new TCPClient();

            client.connect("localhost", 5000);

            client.send("LOGIN|Qank");
            System.out.println(client.receive());

            client.send("CREATE_ROOM");

            String response = client.receive();
            System.out.println(response);

            System.out.println("Nhấn Enter để thoát...");
            System.in.read();

            client.send("LEAVE_ROOM");
            System.out.println(client.receive());

            client.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}