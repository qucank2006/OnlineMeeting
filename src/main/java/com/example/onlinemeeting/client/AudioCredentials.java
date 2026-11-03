package com.example.onlinemeeting.client;

import java.util.Arrays;

/** Short-lived client credentials; never persist or log the secret. */
public final class AudioCredentials {

    private final String sessionId;
    private final String roomId;
    private final byte[] token;
    private final long expiresAt;
    private final int udpPort;
    private boolean cleared;

    public AudioCredentials(String sessionId, String roomId, byte[] token,
                            long expiresAt, int udpPort) {
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid audio session ID");
        }
        if (roomId == null || !roomId.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Invalid audio room ID");
        }
        if (token == null || token.length != 32) {
            throw new IllegalArgumentException("Audio token must contain 32 bytes");
        }
        if (expiresAt <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Audio credentials have expired");
        }
        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP port");
        }
        this.sessionId = sessionId;
        this.roomId = roomId;
        this.token = token.clone();
        this.expiresAt = expiresAt;
        this.udpPort = udpPort;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRoomId() {
        return roomId;
    }

    /** Returns a copy; the caller must wipe that copy after use. */
    public synchronized byte[] getToken() {
        if (isExpired()) {
            throw new IllegalStateException("Audio credentials are no longer usable");
        }
        return token.clone();
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public synchronized boolean isExpired() {
        return cleared || System.currentTimeMillis() >= expiresAt;
    }

    /** Idempotently wipes the stored secret and makes these credentials unusable. */
    public synchronized void clear() {
        Arrays.fill(token, (byte) 0);
        cleared = true;
    }

    @Override
    public String toString() {
        return "AudioCredentials{secret=REDACTED}";
    }
}
