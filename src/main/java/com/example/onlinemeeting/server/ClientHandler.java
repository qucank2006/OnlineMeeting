package com.example.onlinemeeting.server;

import com.example.onlinemeeting.model.Room;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {

    private final Socket socket;

    private BufferedReader reader;
    private PrintWriter writer;

    private String username;
    private String currentRoomId;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {

        try {

            reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream()
                    )
            );

            writer = new PrintWriter(
                    socket.getOutputStream(),
                    true
            );

            String message;

            while ((message = reader.readLine()) != null) {
                handleMessage(message);
            }

        } catch (IOException e) {

            System.out.println("Client đã ngắt kết nối.");

        } finally {

            leaveCurrentRoom();

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleMessage(String message) {

        if (message.startsWith("LOGIN|")) {

            handleLogin(message);

        } else if (message.equals("CREATE_ROOM")) {

            handleCreateRoom();

        } else if (message.startsWith("JOIN_ROOM|")) {

            handleJoinRoom(message);

        } else if (message.equals("LEAVE_ROOM")) {

            handleLeaveRoom();
        } else if (message.startsWith("CHAT|")) {

            handleChat(message);
        }
    }

    private void handleChat(String message) {

        if (username == null || currentRoomId == null) {
            return;
        }

        String content =
                message.substring("CHAT|".length())
                        .trim();

        if (content.isEmpty()) {
            return;
        }

        Room room =
                RoomManager.getRoom(currentRoomId);

        if (room == null) {
            return;
        }

        broadcastToRoom(
                room,
                "CHAT|" + username + "|" + content
        );
    }

    private void broadcastToRoom(
            Room room,
            String message
    ) {

        for (ClientHandler client : room.getMembers()) {
            client.send(message);
        }
    }

    private void handleLogin(String message) {

        username =
                message.substring("LOGIN|".length())
                        .trim();

        if (username.isEmpty()) {
            send("LOGIN_FAILED");
            return;
        }

        send("LOGIN_SUCCESS");

        System.out.println(
                username + " đã kết nối."
        );
    }

    private void handleCreateRoom() {

        if (username == null) {
            send("ERROR|NOT_LOGGED_IN");
            return;
        }

        leaveCurrentRoom();

        Room room =
                RoomManager.createRoom(this);

        currentRoomId =
                room.getRoomId();

        send(
                "ROOM_CREATED|" +
                        currentRoomId
        );

        sendMembers(room);
    }

    private void handleJoinRoom(String message) {

        if (username == null) {
            send("ERROR|NOT_LOGGED_IN");
            return;
        }

        String roomId =
                message.substring(
                                "JOIN_ROOM|".length()
                        )
                        .trim()
                        .toUpperCase();

        Room room =
                RoomManager.getRoom(roomId);

        if (room == null) {
            send("JOIN_FAILED|ROOM_NOT_FOUND");
            return;
        }

        leaveCurrentRoom();

        room =
                RoomManager.joinRoom(
                        roomId,
                        this
                );

        currentRoomId = roomId;

        send(
                "JOIN_SUCCESS|" +
                        roomId
        );

        /*
         * Báo cho những client khác rằng
         * có người mới vào.
         */
        broadcastToOthers(
                room,
                "USER_JOINED|" + username
        );

        /*
         * Gửi danh sách thành viên đầy đủ
         * cho client vừa join.
         */
        sendMembers(room);
    }

    private void handleLeaveRoom() {

        if (currentRoomId == null) {
            send("LEAVE_FAILED|NOT_IN_ROOM");
            return;
        }

        String roomId = currentRoomId;

        Room room =
                RoomManager.getRoom(roomId);

        currentRoomId = null;

        RoomManager.leaveRoom(
                roomId,
                this
        );

        if (room != null) {

            broadcastToOthers(
                    room,
                    "USER_LEFT|" + username
            );
        }

        send("LEAVE_SUCCESS");
    }

    private void leaveCurrentRoom() {

        if (currentRoomId == null) {
            return;
        }

        String roomId =
                currentRoomId;

        Room room =
                RoomManager.getRoom(roomId);

        currentRoomId = null;

        RoomManager.leaveRoom(
                roomId,
                this
        );

        if (room != null) {

            broadcastToOthers(
                    room,
                    "USER_LEFT|" + username
            );
        }
    }

    private void sendMembers(Room room) {

        String members =
                room.getMembers()
                        .stream()
                        .map(ClientHandler::getUsername)
                        .collect(
                                Collectors.joining(",")
                        );

        send("MEMBERS|" + members);
    }

    private void broadcastToOthers(
            Room room,
            String message
    ) {

        for (ClientHandler client :
                room.getMembers()) {

            if (client != this) {
                client.send(message);
            }
        }
    }

    public synchronized void send(
            String message
    ) {

        if (writer != null) {
            writer.println(message);
        }
    }

    public String getUsername() {
        return username;
    }
}