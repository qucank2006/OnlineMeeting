package com.example.onlinemeeting.server;

import com.example.onlinemeeting.model.Room;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class RoomManager {

    // Guards room lifecycle and all membership changes made through this class.
    // Never acquire a ClientHandler monitor or perform network I/O under this lock.
    private static final Object MEMBERSHIP_LOCK = new Object();

    private static final Map<String, Room> rooms =
            new ConcurrentHashMap<>();

    public static Room createRoom(ClientHandler client) {
        synchronized (MEMBERSHIP_LOCK) {
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
    }

    public static Room joinRoom(
            String roomId,
            ClientHandler client
    ) {

        synchronized (MEMBERSHIP_LOCK) {
            Room room = rooms.get(roomId);
            if (room == null) {
                return null;
            }
            room.addMember(client);
            return room;
        }
    }

    public static void leaveRoom(
            String roomId,
            ClientHandler client
    ) {

        synchronized (MEMBERSHIP_LOCK) {
            // Issuance takes this same lock before the session manager monitor.
            // Revoke even if the room has already disappeared.
            AudioSessionManager sessions = client.audioSessionManager();
            sessions.revokeByClient(client);
            VideoSessionManager video = client.videoSessionManager();
            if (video != null) video.revokeByClient(client);
            Room room = rooms.get(roomId);
            if (room == null) {
                return;
            }
            room.removeMember(client);
            if (room.isEmpty()) {
                sessions.revokeByRoom(roomId);
                if (video != null) video.revokeByRoom(roomId);
                rooms.remove(roomId);
            }
        }
    }

    public static Room getRoom(String roomId) {
        synchronized (MEMBERSHIP_LOCK) {
            // Existing API returns a live Room, not a membership authorization lease.
            return rooms.get(roomId);
        }
    }

    /** Unconditional audio/video revocation, including clients with no current room. */
    static void revokeAudioSessions(ClientHandler client) {
        Objects.requireNonNull(client, "Client is required");
        synchronized (MEMBERSHIP_LOCK) {
            client.audioSessionManager().revokeByClient(client);
            if (client.videoSessionManager() != null) client.videoSessionManager().revokeByClient(client);
        }
    }

    /**
     * Executes a short, trusted server-side operation only if the room currently
     * exists and contains this client. The check and operation share the same
     * lock as create/join/leave/delete. Empty means the operation was not run;
     * the operation must return a non-null result and exceptions propagate.
     *
     * Audio issuance can create a session here using the authenticated
     * identity obtained by ClientHandler. This does not itself authenticate users.
     * No Room or mutable member collection is passed to the operation.
     *
     * The operation MUST NOT do network/database I/O, wait for another thread,
     * acquire a ClientHandler monitor, mutate room membership, or return a live
     * Room. Send responses after this helper returns. Session-manager
     * integration must always acquire locks in membership -> session-manager
     * order, never the reverse. leaveRoom revokes before removing membership.
     * The returned result is not proof that membership remains valid afterward.
     */
    static <T> Optional<T> withMember(
            String roomId, ClientHandler client, Supplier<T> operation) {
        Objects.requireNonNull(client, "Client is required");
        Objects.requireNonNull(operation, "Membership operation is required");
        synchronized (MEMBERSHIP_LOCK) {
            Room room = rooms.get(roomId);
            if (room == null || !room.getMembers().contains(client)) {
                return Optional.empty();
            }
            return Optional.of(Objects.requireNonNull(
                    operation.get(), "Membership operation must return a result"));
        }
    }
}