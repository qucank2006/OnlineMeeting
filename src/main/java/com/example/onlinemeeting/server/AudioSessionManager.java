package com.example.onlinemeeting.server;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory registry foundation, not UDP packet authentication or encryption.
 * Share one instance across TCP handlers, room management, and the audio server.
 * No network I/O or calls into ClientHandler/RoomManager occur under its monitor.
 */
public final class AudioSessionManager {

    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, AudioSession> sessions = new HashMap<>();
    private final Duration lifetime;

    public AudioSessionManager() {
        this(DEFAULT_LIFETIME);
    }

    public AudioSessionManager(Duration lifetime) {
        this.lifetime = Objects.requireNonNull(lifetime, "Session lifetime is required");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("Session lifetime must be positive");
        }
    }

    /**
     * Trusted TCP-side API. The caller MUST obtain userId and roomId from
     * authenticated server state and authorize current membership, coordinated
     * with leave/disconnect. Never call this with identity supplied by UDP.
     * Atomically revokes/replaces the previous session for this owner and room.
     */
    public synchronized AudioSession createSession(
            ClientHandler owner, long userId, String roomId) {
        Objects.requireNonNull(owner, "Session owner is required");
        if (userId <= 0) {
            throw new IllegalArgumentException("Authenticated user ID must be positive");
        }
        if (roomId == null || roomId.isBlank() || !roomId.equals(roomId.strip())
                || roomId.indexOf('|') >= 0 || roomId.indexOf('\r') >= 0
                || roomId.indexOf('\n') >= 0 || roomId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid server-owned room ID is required");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(lifetime);
        String id;
        do {
            byte[] idBytes = new byte[16];
            random.nextBytes(idBytes);
            id = HexFormat.of().formatHex(idBytes);
        } while (sessions.containsKey(id));

        byte[] token = new byte[32];
        random.nextBytes(token);
        AudioSession next;
        try {
            next = new AudioSession(id, token, userId, roomId, owner, now, expiresAt);
        } finally {
            Arrays.fill(token, (byte) 0);
        }

        Iterator<AudioSession> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            AudioSession existing = iterator.next();
            if (!existing.isActive(now)
                    || (existing.getOwner() == owner && existing.getRoomId().equals(roomId))) {
                existing.revoke();
                iterator.remove();
            }
        }
        sessions.put(id, next);
        return next;
    }

    /** Lookup only; session ID knowledge does not authorize UDP access. */
    public synchronized Optional<AudioSession> findSession(String sessionId) {
        return Optional.ofNullable(activeSession(sessionId, Instant.now()));
    }

    /**
     * Checks token possession and lifetime only, not current TCP membership,
     * packet integrity, replay protection, or endpoint reachability.
     * Failed checks do not refresh authenticated activity.
     */
    public synchronized Optional<AudioSession> validateSession(String sessionId, byte[] token) {
        return Optional.ofNullable(validatedSession(sessionId, token, Instant.now()));
    }

    /**
     * Also requires the previously registered source endpoint.
     * Validation alone does not refresh the activity timestamp.
     */
    public synchronized Optional<AudioSession> validateSession(
            String sessionId, byte[] token, InetSocketAddress source) {
        AudioSession session = validatedSession(sessionId, token, Instant.now());
        return session != null && source != null && source.equals(session.getEndpoint())
                ? Optional.of(session) : Optional.empty();
    }

    /**
     * The transport must FIRST authenticate the registration packet and prove
     * endpoint reachability with a challenge. A bearer token alone is insufficient.
     * Endpoint replacement is rejected; use a newly issued session for migration.
     */
    public synchronized boolean registerEndpoint(
            String sessionId, byte[] token, InetSocketAddress endpoint) {
        if (!validEndpoint(endpoint)) {
            return false;
        }
        Instant now = Instant.now();
        AudioSession session = validatedSession(sessionId, token, now);
        return session != null && session.registerEndpoint(endpoint, now);
    }

    /**
     * Call only after packet authentication and replay checks succeed.
     * Supports audio or heartbeats while the microphone is muted.
     */
    public synchronized boolean recordAuthenticatedActivity(
            String sessionId, byte[] token, InetSocketAddress source) {
        Instant now = Instant.now();
        AudioSession session = validatedSession(sessionId, token, now);
        if (session == null || source == null || !source.equals(session.getEndpoint())) {
            return false;
        }
        session.recordActivity(now);
        return true;
    }

    public synchronized boolean revokeSession(String sessionId) {
        AudioSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.revoke();
        return true;
    }

    public synchronized int revokeByClient(ClientHandler owner) {
        Objects.requireNonNull(owner, "Session owner is required");
        int removed = 0;
        Iterator<AudioSession> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            AudioSession session = iterator.next();
            if (session.getOwner() == owner) {
                session.revoke();
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int revokeByRoom(String roomId) {
        Objects.requireNonNull(roomId, "Room ID is required");
        int removed = 0;
        Iterator<AudioSession> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            AudioSession session = iterator.next();
            if (session.getRoomId().equals(roomId)) {
                session.revoke();
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Invoke periodically; validation rejects expiration without waiting for cleanup. */
    public synchronized int removeExpiredSessions() {
        Instant now = Instant.now();
        int removed = 0;
        Iterator<AudioSession> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            AudioSession session = iterator.next();
            if (!session.isActive(now)) {
                session.revoke();
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    // All private registry helpers are called while holding this manager's monitor.
    private AudioSession activeSession(String sessionId, Instant now) {
        AudioSession session = sessions.get(sessionId);
        if (session != null && !session.isActive(now)) {
            session.revoke();
            sessions.remove(sessionId);
            return null;
        }
        return session;
    }

    private AudioSession validatedSession(String sessionId, byte[] token, Instant now) {
        AudioSession session = activeSession(sessionId, now);
        return session != null && session.matchesToken(token) ? session : null;
    }

    private static boolean validEndpoint(InetSocketAddress endpoint) {
        return endpoint != null && !endpoint.isUnresolved() && endpoint.getPort() > 0
                && !endpoint.getAddress().isAnyLocalAddress()
                && !endpoint.getAddress().isMulticastAddress();
    }
}
