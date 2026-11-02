package com.example.onlinemeeting.server;

import com.example.onlinemeeting.model.Room;
import com.example.onlinemeeting.service.AuthService;
import com.example.onlinemeeting.service.AuthService.AuthResult;
import com.example.onlinemeeting.service.AuthService.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {

    private final Socket socket;

    private BufferedReader reader;
    private PrintWriter writer;

    private final AuthService authService = new AuthService();
    private volatile AuthenticatedUser authenticatedUser;

    private record AuthenticatedUser(long id, String username, String displayName) {
    }
    private String currentRoomId;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {

        try {

            reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8
                    )
            );

            writer = new PrintWriter(
                    socket.getOutputStream(),
                    true, StandardCharsets.UTF_8
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

        if (message.equals("REGISTER") || message.startsWith("REGISTER|")) {

            handleRegister(message);

        } else if (message.equals("LOGIN") || message.startsWith("LOGIN|")) {

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

        if (!requireAuthentication() || currentRoomId == null) {
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
                "CHAT|" + getUsername() + "|" + content
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
        if (authenticatedUser != null) {
            send("LOGIN_FAILED|ALREADY_AUTHENTICATED");
            return;
        }
        String[] fields;
        try {
            fields = decodeFields(message, 2);
        } catch (IllegalArgumentException e) {
            send("LOGIN_FAILED|MALFORMED_REQUEST");
            return;
        }
        try {
            AuthResult result = authService.login(fields[0], fields[1]);
            if (result.status() != Status.LOGGED_IN) {
                send("LOGIN_FAILED|" + result.status().name());
                return;
            }
            // Validate stored identity too, before placing it in legacy room messages.
            if (result.userId() == null || result.userId() <= 0
                    || !safeText(result.username()) || !safeText(result.displayName())) {
                send("LOGIN_FAILED|SERVICE_UNAVAILABLE");
                return;
            }
            String encodedName = Base64.getEncoder().encodeToString(
                    result.displayName().getBytes(StandardCharsets.UTF_8));
            authenticatedUser = new AuthenticatedUser(
                    result.userId(), result.username(), result.displayName());
            send("LOGIN_SUCCESS|" + result.userId() + "|"
                    + result.username() + "|" + encodedName);
        } catch (RuntimeException e) {
            // Never echo exception details or credential-bearing requests.
            send("LOGIN_FAILED|SERVICE_UNAVAILABLE");
        }
    }

    private void handleRegister(String message) {
        String[] fields;
        try {
            fields = decodeFields(message, 4);
        } catch (IllegalArgumentException e) {
            send("REGISTER_FAILED|MALFORMED_REQUEST");
            return;
        }
        try {
            AuthResult result = authService.register(
                    fields[0], fields[1], fields[2], fields[3]);
            // Registration never changes this connection's authenticated identity.
            send(result.status() == Status.REGISTERED
                    ? "REGISTER_SUCCESS" : "REGISTER_FAILED|" + result.status().name());
        } catch (RuntimeException e) {
            send("REGISTER_FAILED|SERVICE_UNAVAILABLE");
        }
    }

    private String[] decodeFields(String message, int count) {
        // Bounds comfortably cover the service's UTF-8 field limits.
        if (message.length() > 8192) {
            throw new IllegalArgumentException("Invalid request");
        }
        String[] parts = message.split("\\|", -1);
        if (parts.length != count + 1) {
            throw new IllegalArgumentException("Invalid field count");
        }
        String[] decoded = new String[count];
        for (int i = 0; i < count; i++) {
            String encoded = parts[i + 1];
            if (encoded.isEmpty() || encoded.length() > 2048) {
                throw new IllegalArgumentException("Invalid field size");
            }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            try {
                decoded[i] = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid UTF-8");
            }
            if (!safeText(decoded[i])) {
                throw new IllegalArgumentException("Invalid field");
            }
        }
        return decoded;
    }

    private boolean safeText(String value) {
        return value != null && !value.isBlank()
                && value.indexOf('|') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private boolean requireAuthentication() {
        if (authenticatedUser == null) {
            send("ERROR|NOT_LOGGED_IN");
            return false;
        }
        return true;
    }

    private void handleCreateRoom() {

        if (!requireAuthentication()) {
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

        if (!requireAuthentication()) {
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

        if (room == null) {
            send("JOIN_FAILED|ROOM_NOT_FOUND");
            return;
        }

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
                "USER_JOINED|" + getUsername()
        );

        /*
         * Gửi danh sách thành viên đầy đủ
         * cho client vừa join.
         */
        sendMembers(room);
    }

    private void handleLeaveRoom() {

        if (!requireAuthentication()) {
            return;
        }

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
                    "USER_LEFT|" + getUsername()
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
                    "USER_LEFT|" + getUsername()
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
        AuthenticatedUser user = authenticatedUser;
        return user == null ? null : user.username();
    }
}