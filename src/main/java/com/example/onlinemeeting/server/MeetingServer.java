package com.example.onlinemeeting.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class MeetingServer {
    private static final int PORT = 5000;

    public static void main(String[] args) {
        //UDP Audio Server
        AudioServer audioServer = new AudioServer();
        Thread audioThread = new Thread(audioServer::start);
        audioThread.setDaemon(true);
        audioThread.start();


        //TCP Server
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            System.out.println("Server đang chạy tại port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();

                ClientHandler clientHandler = new ClientHandler(socket);
                new Thread(clientHandler).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
