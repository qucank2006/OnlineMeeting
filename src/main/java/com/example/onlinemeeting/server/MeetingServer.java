package com.example.onlinemeeting.server;

import com.example.onlinemeeting.config.AppConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MeetingServer {

    public static void main(String[] args) {
        AppConfig config = AppConfig.getInstance();
        AudioSessionManager audioSessionManager = new AudioSessionManager();
        VideoSessionManager videoSessionManager = new VideoSessionManager();
        VideoServer videoServer = new VideoServer(videoSessionManager);
        Thread videoThread = new Thread(videoServer::start, "video-server");
        videoThread.setDaemon(true);
        videoThread.start();

        //UDP Audio Server
        AudioServer audioServer = new AudioServer(audioSessionManager);
        Thread audioThread = new Thread(audioServer::start);
        audioThread.setDaemon(true);
        audioThread.start();


        //TCP Server
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(config.getServerBindHost(), config.getTcpPort()));

            System.out.println("Server đang chạy tại " + serverSocket.getLocalSocketAddress());

            while (true) {
                Socket socket = serverSocket.accept();

                ClientHandler clientHandler = new ClientHandler(socket, audioSessionManager, videoSessionManager);
                new Thread(clientHandler).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            videoServer.close();
        }
    }
}
