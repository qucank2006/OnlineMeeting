package com.example.onlinemeeting.model;

import com.example.onlinemeeting.server.ClientHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {

    private final String roomId;

    private final Set<ClientHandler> members =
            ConcurrentHashMap.newKeySet();

    public Room(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void addMember(ClientHandler client) {
        members.add(client);
    }

    public void removeMember(ClientHandler client) {
        members.remove(client);
    }

    public Set<ClientHandler> getMembers() {
        return Set.copyOf(members);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}