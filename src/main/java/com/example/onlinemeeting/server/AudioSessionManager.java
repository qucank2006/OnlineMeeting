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
    public static final Duration ENDPOINT_IDLE_TIMEOUT = Duration.ofSeconds(15);

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
     * Basic bearer-session authorization only: the transport supplies the source
     * address observed on the UDP packet, never an address claimed in its payload.
     * This does not verify packet integrity or prove endpoint reachability.
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
     * Records activity after validating the session token and registered source.
     * Supports audio or heartbeats while the microphone is muted. This is basic
     * bearer authorization, not cryptographic packet authentication.
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

    /**
     * Trusted server-side lookup. roomId must come from server-owned session
     * state, never directly from a UDP client's claimed room.
     *
     * Returns an immutable snapshot of session IDs and registered endpoints,
     * excluding the sender, expired/revoked sessions and idle endpoints.
     * No tokens, owners or mutable AudioSession objects are exposed.
     * A snapshot is not a continuing authorization lease: revocation can occur
     * after this method returns. Perform network I/O outside this monitor.
     */
    public synchronized Map<String, InetSocketAddress> getActiveRegisteredEndpoints(
            String roomId, String senderSessionId) {
        Objects.requireNonNull(roomId, "Server-owned room ID is required");
        Objects.requireNonNull(senderSessionId, "Sender session ID is required");
        return recipientSnapshot(roomId, senderSessionId, Instant.now());
    }

    /**
     * UDP-facing lookup: validates the sender's token, registered source and
     * activity window, then derives the room from its stored AudioSession.
     * Invalid senders receive an empty snapshot. This lookup does not itself
     * refresh activity; call recordAuthenticatedActivity for accepted packets.
     */
    public synchronized Map<String, InetSocketAddress> getActiveRegisteredEndpoints(
            String senderSessionId, byte[] token, InetSocketAddress source) {
        Instant now = Instant.now();
        AudioSession sender = validatedSession(senderSessionId, token, now);
        if (sender == null || source == null || !source.equals(sender.getEndpoint())
                || !endpointActive(sender, now)) {
            return Map.of();
        }
        return recipientSnapshot(sender.getRoomId(), sender.getSessionId(), now);
    }

    /**
     * Trusted server-side endpoint lookup with the same inactivity policy used
     * by recipient selection. Session ID knowledge alone does not authorize UDP.
     */
    public synchronized Optional<InetSocketAddress> findActiveEndpoint(String sessionId) {
        Instant now = Instant.now();
        AudioSession session = activeSession(sessionId, now);
        return session != null && endpointActive(session, now)
                ? Optional.of(session.getEndpoint()) : Optional.empty();
    }

    private Map<String, InetSocketAddress> recipientSnapshot(
            String roomId, String senderSessionId, Instant now) {
        Map<String, InetSocketAddress> recipients = new HashMap<>();
        for (AudioSession session : sessions.values()) {
            if (!session.getSessionId().equals(senderSessionId)
                    && session.getRoomId().equals(roomId)
                    && endpointActive(session, now)) {
                recipients.put(session.getSessionId(), session.getEndpoint());
            }
        }
        return Map.copyOf(recipients);
    }

    /**
     * Inactivity expires delivery eligibility, not the TCP-authorized session.
     * Retain the binding so another endpoint cannot take over. Existing
     * same-endpoint registration/activity may refresh eligibility; absolute
     * session expiry and explicit revocation still invalidate the session.
     * Checked on lookup, so no scheduler or background thread is needed.
     */
    private boolean endpointActive(AudioSession session, Instant now) {
        Instant lastActivity = session.getLastAuthenticatedActivity();
        return session.isActive(now) && session.getEndpoint() != null
                && lastActivity != null
                && now.isBefore(lastActivity.plus(ENDPOINT_IDLE_TIMEOUT));
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
