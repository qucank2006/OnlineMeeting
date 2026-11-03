package com.example.onlinemeeting.server;

import com.example.onlinemeeting.config.AppConfig;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public class AudioServer {

    private static final int VERSION = 1;
    private static final int REGISTER = 1;
    private static final int HEARTBEAT = 2;
    private static final int AUDIO = 3;
    private static final int REGISTER_OK = 4;
    private static final int SESSION_ID_BYTES = 16;
    private static final int TOKEN_BYTES = 32;
    private static final int CLIENT_HEADER_BYTES = 2 + SESSION_ID_BYTES + TOKEN_BYTES;
    private static final int SERVER_HEADER_BYTES = 2 + SESSION_ID_BYTES;
    private static final int MAX_PCM_BYTES = 1024;
    private static final int MAX_PACKET_BYTES = CLIENT_HEADER_BYTES + MAX_PCM_BYTES;

    private final AudioSessionManager audioSessionManager;

    public AudioServer(AudioSessionManager audioSessionManager) {
        this.audioSessionManager = Objects.requireNonNull(
                audioSessionManager, "Audio session manager is required");
    }

    public void start() {
        AppConfig config = AppConfig.getInstance();
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(config.getServerBindHost(), config.getUdpPort()));
            socket.setSoTimeout(1000);
            System.out.println("Audio Server đang chạy tại " + socket.getLocalSocketAddress());
            // One extra byte exposes oversize/truncated datagrams for rejection.
            byte[] receiveBuffer = new byte[MAX_PACKET_BYTES + 1];
            long lastCleanup = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(
                        receiveBuffer, receiveBuffer.length);
                try {
                    socket.receive(packet);
                    handlePacket(socket, packet);
                } catch (SocketTimeoutException ignored) {
                    // Allow cleanup even when no UDP traffic arrives.
                } finally {
                    Arrays.fill(receiveBuffer, (byte) 0);
                }
                if (System.nanoTime() - lastCleanup >= 1_000_000_000L) {
                    audioSessionManager.removeExpiredSessions();
                    lastCleanup = System.nanoTime();
                }
            }
        } catch (IOException e) {
            // Do not log packet contents, credentials, or exception payloads.
            System.err.println("Audio server stopped because of a UDP socket error.");
        }
    }

    private void handlePacket(DatagramSocket socket, DatagramPacket packet) {
        byte[] token = null;
        try {
            int length = packet.getLength();
            if (length < CLIENT_HEADER_BYTES || length > MAX_PACKET_BYTES) {
                return;
            }
            ByteBuffer buffer = ByteBuffer.wrap(
                    packet.getData(), packet.getOffset(), length);
            int version = Byte.toUnsignedInt(buffer.get());
            int type = Byte.toUnsignedInt(buffer.get());
            if (version != VERSION || (type != REGISTER && type != HEARTBEAT && type != AUDIO)) {
                return;
            }
            int pcmLength = length - CLIENT_HEADER_BYTES;
            if (type == AUDIO) {
                if (pcmLength == 0 || pcmLength > MAX_PCM_BYTES || (pcmLength & 1) != 0) {
                    return;
                }
            } else if (pcmLength != 0) {
                return;
            }

            byte[] sessionBytes = new byte[SESSION_ID_BYTES];
            buffer.get(sessionBytes);
            String sessionId = HexFormat.of().formatHex(sessionBytes);
            token = new byte[TOKEN_BYTES];
            buffer.get(token);
            InetSocketAddress source = new InetSocketAddress(
                    packet.getAddress(), packet.getPort());

            // Initial lookup releases the manager monitor before acquiring
            // membership. Every operation below revalidates inside withMember.
            AudioSession session = audioSessionManager.validateSession(sessionId, token)
                    .orElse(null);
            if (session == null) {
                return;
            }
            byte[] suppliedToken = token;
            if (type == REGISTER) {
                boolean registered = RoomManager.withMember(
                        session.getRoomId(), session.getOwner(),
                        () -> audioSessionManager.registerEndpoint(
                                sessionId, suppliedToken, source)).orElse(false);
                if (registered && senderStillAuthorized(session, suppliedToken, source)) {
                    byte[] reply = serverPacket(REGISTER_OK, session, null);
                    sendPacket(socket, reply, source);
                }
                return;
            }

            Map<String, InetSocketAddress> recipients = RoomManager.withMember(
                    session.getRoomId(), session.getOwner(), () -> {
                        if (!audioSessionManager.recordAuthenticatedActivity(
                                sessionId, suppliedToken, source)) {
                            return Map.<String, InetSocketAddress>of();
                        }
                        return type == AUDIO
                                ? audioSessionManager.getActiveRegisteredEndpoints(
                                        sessionId, suppliedToken, source)
                                : Map.<String, InetSocketAddress>of();
                    }).orElse(Map.of());
            if (type == HEARTBEAT || recipients.isEmpty()) {
                return;
            }

            byte[] pcm = new byte[pcmLength];
            buffer.get(pcm);
            byte[] forwarded = serverPacket(AUDIO, session, pcm);
            for (Map.Entry<String, InetSocketAddress> recipient : recipients.entrySet()) {
                if (canRelay(session, suppliedToken, source,
                        recipient.getKey(), recipient.getValue())) {
                    // No membership/session-manager lock is held during send.
                    sendPacket(socket, forwarded, recipient.getValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Invalid datagrams must not terminate the receive loop.
        } finally {
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private boolean senderStillAuthorized(
            AudioSession sender, byte[] token, InetSocketAddress source) {
        return RoomManager.withMember(sender.getRoomId(), sender.getOwner(),
                () -> validRegisteredSender(sender, token, source)).orElse(false);
    }

    // Called only while the RoomManager membership lock is held.
    private boolean validRegisteredSender(
            AudioSession sender, byte[] token, InetSocketAddress source) {
        return audioSessionManager.validateSession(sender.getSessionId(), token, source)
                .filter(current -> current == sender).isPresent()
                && audioSessionManager.findActiveEndpoint(sender.getSessionId())
                .filter(source::equals).isPresent();
    }

    private boolean canRelay(AudioSession sender, byte[] token, InetSocketAddress source,
                             String recipientId, InetSocketAddress endpoint) {
        if (sender.getSessionId().equals(recipientId)) {
            return false;
        }
        AudioSession recipient = audioSessionManager.findSession(recipientId).orElse(null);
        if (recipient == null || !sender.getRoomId().equals(recipient.getRoomId())) {
            return false;
        }
        // Nested membership checks use the same reentrant membership lock.
        // All manager accesses follow membership -> session-manager ordering.
        return RoomManager.withMember(sender.getRoomId(), sender.getOwner(),
                () -> RoomManager.withMember(recipient.getRoomId(), recipient.getOwner(),
                        () -> validRegisteredSender(sender, token, source)
                                && audioSessionManager.findSession(recipientId)
                                .filter(current -> current == recipient).isPresent()
                                && audioSessionManager.findActiveEndpoint(recipientId)
                                .filter(endpoint::equals).isPresent())
                        .orElse(false)).orElse(false);
    }

    private byte[] serverPacket(int type, AudioSession sender, byte[] pcm) {
        // Stored hexadecimal IDs are converted to the required 16-byte wire ID.
        byte[] sessionId = HexFormat.of().parseHex(sender.getSessionId());
        ByteBuffer packet = ByteBuffer.allocate(
                SERVER_HEADER_BYTES + (pcm == null ? 0 : pcm.length));
        packet.put((byte) VERSION).put((byte) type).put(sessionId);
        if (pcm != null) {
            packet.put(pcm);
        }
        return packet.array();
    }

    private void sendPacket(DatagramSocket socket, byte[] data, InetSocketAddress target) {
        try {
            socket.send(new DatagramPacket(data, data.length, target));
        } catch (IOException ignored) {
            // A failed recipient must not stop forwarding to remaining members.
        }
    }
}
