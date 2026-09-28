package com.example.onlinemeeting.server;

import com.example.onlinemeeting.model.Room;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

    private static final Map<String, Room> rooms =
            new ConcurrentHashMap<>();

    public static Room createRoom(ClientHandler client) {

        String roomId;

        do {
            roomId = UUID.randomUUID()
                    .toString()
                    .substring(0, 6)
                    .toUpperCase();

        } while (rooms.containsKey(roomId));

        Room room = new Room(roomId);

        room.addMember(client);

        rooms.put(roomId, room);

        return room;
    }

    public static Room joinRoom(
            String roomId,
            ClientHandler client
    ) {

        Room room = rooms.get(roomId);

        if (room == null) {
            return null;
        }

        room.addMember(client);

        return room;
    }

    public static void leaveRoom(
            String roomId,
            ClientHandler client
    ) {

        Room room = rooms.get(roomId);

        if (room == null) {
            return;
        }

        room.removeMember(client);

        if (room.isEmpty()) {
            rooms.remove(roomId);
        }
    }

    public static Room getRoom(String roomId) {
        return rooms.get(roomId);
    }
}