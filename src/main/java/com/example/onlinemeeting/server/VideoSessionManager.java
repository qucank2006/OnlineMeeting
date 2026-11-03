package com.example.onlinemeeting.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Lock order: RoomManager membership lock, then this registry. No network I/O here. */
public final class VideoSessionManager {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();
    private static final long LIFETIME_MILLIS = 30 * 60_000L;

    static final class Session {
        final String id, roomId, username;
        final long userId, expiresAt;
        final ClientHandler owner;
        private final byte[] token;
        boolean revoked;
        VideoServer.Peer peer;
        Session(String id, byte[] token, ClientHandler owner, long userId, String roomId, String username) {
            this.id = id; this.token = token.clone(); this.owner = owner; this.userId = userId;
            this.roomId = roomId; this.username = username;
            this.expiresAt = System.currentTimeMillis() + LIFETIME_MILLIS;
        }
        @Override public String toString() { return "VideoSession[secret redacted]"; }
    }

    synchronized Session create(ClientHandler owner, long userId, String roomId, String username) {
        Objects.requireNonNull(owner);
        if (userId <= 0 || roomId == null || username == null) throw new IllegalArgumentException("Invalid video owner");
        removeExpired();
        // Idempotent issuance prevents duplicate credentials/connections for one owner.
        for (Session session : sessions.values())
            if (active(session) && session.owner == owner && session.roomId.equals(roomId)) return session;
        if (sessions.size() >= 512) throw new IllegalStateException("Video session capacity reached");
        byte[] id = new byte[16], token = new byte[32];
        String key;
        do { random.nextBytes(id); key = HexFormat.of().formatHex(id); } while (sessions.containsKey(key));
        random.nextBytes(token);
        try {
            Session session = new Session(key, token, owner, userId, roomId, username);
            sessions.put(key, session);
            return session;
        } finally { Arrays.fill(token, (byte) 0); }
    }
    synchronized byte[] token(Session session) {
        if (!active(session)) throw new IllegalStateException("Video session unavailable");
        return session.token.clone();
    }
    synchronized Session find(String id) { return sessions.get(id); }
    synchronized boolean active(Session session) {
        return session != null && sessions.get(session.id) == session && !session.revoked
                && System.currentTimeMillis() < session.expiresAt;
    }
    synchronized String claim(Session session, byte[] token, VideoServer.Peer peer) {
        if (!active(session) || !MessageDigest.isEqual(session.token, token)) return "UNAUTHORIZED";
        if (session.peer != null) return "ALREADY_CONNECTED";
        if (sessions.values().stream().anyMatch(s -> s != session && active(s) && s.peer != null
                && s.roomId.equals(session.roomId) && s.userId == session.userId)) return "ALREADY_CONNECTED";
        long count = sessions.values().stream().filter(s -> active(s) && s.peer != null
                && s.roomId.equals(session.roomId)).count();
        if (count >= 2) return "VIDEO_FULL";
        session.peer = peer;
        return null;
    }
    synchronized List<VideoServer.Peer> recipients(Session sender) {
        if (!active(sender)) return List.of();
        return sessions.values().stream().filter(s -> s != sender && active(s) && s.peer != null
                && s.roomId.equals(sender.roomId)).map(s -> s.peer).toList();
    }
    synchronized List<VideoServer.Peer> roomPeers(String roomId) {
        return sessions.values().stream().filter(s -> active(s) && s.peer != null
                && s.roomId.equals(roomId)).map(s -> s.peer).toList();
    }
    synchronized void release(Session session, VideoServer.Peer peer) {
        if (session != null && session.peer == peer) {
            session.peer = null;
            revoke(session);
            sessions.remove(session.id, session);
        }
    }
    public synchronized void revokeByClient(ClientHandler owner) {
        sessions.values().stream().filter(s -> s.owner == owner).forEach(this::revoke);
    }
    public synchronized void revokeByRoom(String roomId) {
        sessions.values().stream().filter(s -> s.roomId.equals(roomId)).forEach(this::revoke);
    }
    private void revoke(Session session) { session.revoked = true; Arrays.fill(session.token, (byte) 0); }
    public synchronized void removeExpired() {
        sessions.values().removeIf(s -> {
            if (s.revoked || System.currentTimeMillis() >= s.expiresAt) { revoke(s); return true; }
            return false;
        });
    }
}
