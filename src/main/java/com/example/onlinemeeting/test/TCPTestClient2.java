package com.example.onlinemeeting.test;

import com.example.onlinemeeting.client.TCPClient;

public class TCPTestClient2 {

    public static void main(String[] args) {
        try {
            TCPClient client = new TCPClient();

            client.connect("localhost", 5000);

            client.send("LOGIN|MinhVy");
            System.out.println(client.receive());

            // Thay bằng Room ID mà TCPTestClient vừa tạo
            String roomId = "9D6B0F";

            client.send("JOIN_ROOM|" + roomId);
            System.out.println(client.receive());

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