package com.example.onlinemeeting.client;

import java.util.Arrays;

/** Owned by VideoClient after construction; no server-side class dependency. */
public final class VideoCredentials {
    private final String sessionId;
    private final String roomId;
    private final int port;
    private final long expiresAt;
    private final byte[] token;
    private boolean cleared;

    public VideoCredentials(String sessionId, String roomId, byte[] token, long expiresAt, int port) {
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}")
                || roomId == null || !roomId.matches("[0-9A-F]{6}")
                || token == null || token.length != 32 || expiresAt <= System.currentTimeMillis()
                || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid video credentials");
        this.sessionId = sessionId;
        this.roomId = roomId;
        this.token = token.clone();
        this.expiresAt = expiresAt;
        this.port = port;
    }
    public String getSessionId() { return sessionId; }
    public String getRoomId() { return roomId; }
    public int getPort() { return port; }
    public long getExpiresAt() { return expiresAt; }
    public synchronized boolean isExpired() { return cleared || System.currentTimeMillis() >= expiresAt; }
    public synchronized byte[] getToken() {
        if (isExpired()) throw new IllegalStateException("Video credentials expired or cleared");
        return token.clone();
    }
    public synchronized void clear() { Arrays.fill(token, (byte) 0); cleared = true; }
    @Override public String toString() { return "VideoCredentials[secret redacted]"; }
}
