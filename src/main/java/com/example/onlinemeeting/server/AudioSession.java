package com.example.onlinemeeting.server;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;

/**
 * Server-owned session metadata. Only AudioSessionManager changes session state.
 * A retained session reference is not proof of current authorization: callers
 * must validate through the manager before each operation.
 */
public final class AudioSession {

    private final String sessionId;
    private final byte[] token;
    private final long userId;
    private final String roomId;
    private final ClientHandler owner;
    private final Instant createdAt;
    private final Instant expiresAt;

    private InetSocketAddress endpoint;
    private Instant lastAuthenticatedActivity;
    private boolean revoked;

    AudioSession(String sessionId, byte[] token, long userId, String roomId,
                 ClientHandler owner, Instant createdAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.token = token.clone();
        this.userId = userId;
        this.roomId = roomId;
        this.owner = owner;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * For credential delivery over the owning TCP session only.
     * Never include this value in logs or send it as plaintext UDP credentials.
     */
    public synchronized byte[] getToken() {
        if (revoked || !Instant.now().isBefore(expiresAt)) {
            throw new IllegalStateException("Audio session is no longer active");
        }
        return token.clone();
    }

    public long getUserId() {
        return userId;
    }

    public String getRoomId() {
        return roomId;
    }

    public ClientHandler getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public synchronized InetSocketAddress getEndpoint() {
        return endpoint;
    }

    /** Null until the first successful endpoint registration. */
    public synchronized Instant getLastAuthenticatedActivity() {
        return lastAuthenticatedActivity;
    }

    public synchronized boolean isRevoked() {
        return revoked;
    }

    synchronized boolean isActive(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    synchronized boolean matchesToken(byte[] candidate) {
        return candidate != null && candidate.length == 32
                && MessageDigest.isEqual(token, candidate);
    }

    synchronized boolean registerEndpoint(InetSocketAddress candidate, Instant now) {
        if (!isActive(now) || (endpoint != null && !endpoint.equals(candidate))) {
            return false;
        }
        endpoint = candidate;
        lastAuthenticatedActivity = now;
        return true;
    }

    synchronized void recordActivity(Instant now) {
        lastAuthenticatedActivity = now;
    }

    synchronized void revoke() {
        revoked = true;
        endpoint = null;
        Arrays.fill(token, (byte) 0);
    }

    @Override
    public String toString() {
        return "AudioSession{credentials=REDACTED}";
    }
}
