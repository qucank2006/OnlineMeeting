package com.example.onlinemeeting.server;

import com.example.onlinemeeting.config.AppConfig;
import com.example.onlinemeeting.video.VideoProtocol;
import com.example.onlinemeeting.video.VideoProtocol.Message;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated video relay. Encoded JPEGs are never decoded by the server. */
public final class VideoServer implements AutoCloseable {
    private final VideoSessionManager sessions;
    private final Semaphore connections = new Semaphore(32);
    private final Semaphore handshakes = new Semaphore(8);
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile ServerSocket listener;

    public VideoServer(VideoSessionManager sessions) { this.sessions = Objects.requireNonNull(sessions); }

    public void start() {
        AppConfig config = AppConfig.getInstance();
        try (ServerSocket server = new ServerSocket()) {
            listener = server;
            server.bind(new InetSocketAddress(config.getServerBindHost(), config.getVideoPort()));
            Thread.ofVirtual().name("video-watchdog").start(this::watchdog);
            System.out.println("Video server listening on " + server.getLocalSocketAddress());
            while (!closed) {
                Socket socket = server.accept();
                if (!connections.tryAcquire()) { socket.close(); continue; }
                if (!handshakes.tryAcquire()) { connections.release(); socket.close(); continue; }
                Peer peer = new Peer(socket);
                peers.add(peer);
                Thread.ofVirtual().name("video-connection").start(() -> serve(peer));
            }
        } catch (IOException e) {
            if (!closed) System.err.println("Video server stopped: socket or bind failure.");
        } finally { close(); }
    }

    private void serve(Peer peer) {
        boolean handshakePermit = true;
        try {
            peer.socket.setTcpNoDelay(true);
            peer.socket.setSoTimeout(5000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(peer.socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(peer.socket.getOutputStream()));
            Message auth = VideoProtocol.read(input, true);
            if (auth.type() != VideoProtocol.AUTH) throw new IOException("Video authentication required");
            byte[] token = new byte[32];
            String failure;
            try {
                ByteBuffer bytes = ByteBuffer.wrap(auth.payload());
                int version = Byte.toUnsignedInt(bytes.get());
                byte[] id = new byte[16]; bytes.get(id); bytes.get(token);
                VideoSessionManager.Session session = sessions.find(HexFormat.of().formatHex(id));
                if (version != VideoProtocol.VERSION || session == null) failure = "UNAUTHORIZED";
                else {
                    peer.session = session;
                    failure = RoomManager.withMember(session.roomId, session.owner, () -> {
                        String result = sessions.claim(session, token, peer);
                        if (result != null) return result;
                        peer.authorized = true;
                        peer.offer(new Message(VideoProtocol.AUTH_OK,
                                ByteBuffer.allocate(8).putLong(session.userId).array()));
                        try {
                            peer.offer(state(peer));
                            for (Peer other : sessions.recipients(session)) {
                                peer.offer(state(other));
                                other.offer(state(peer));
                            }
                        } catch (IOException e) { return "INVALID_IDENTITY"; }
                        return "";
                    }).orElse("NOT_IN_ROOM");
                }
            } finally {
                Arrays.fill(token, (byte) 0);
                Arrays.fill(auth.payload(), (byte) 0);
            }
            if (!failure.isEmpty()) {
                peer.writeStarted = System.nanoTime();
                VideoProtocol.write(output, new Message(VideoProtocol.ERROR,
                        failure.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                throw new IOException("Video authentication rejected");
            }
            handshakes.release(); handshakePermit = false;
            peer.socket.setSoTimeout(15_000);
            peer.lastRead = System.nanoTime();
            Thread.ofVirtual().name("video-writer").start(() -> write(peer, output));
            while (!peer.stopped.get()) {
                Message message = VideoProtocol.read(input, true);
                peer.lastRead = System.nanoTime();
                VideoSessionManager.Session session = peer.session;
                boolean authorized = RoomManager.withMember(session.roomId, session.owner, () -> {
                    if (!sessions.active(session)) return false;
                    switch (message.type()) {
                        case VideoProtocol.VIDEO_FRAME -> {
                            if (!peer.cameraOn) return false;
                            long now = System.nanoTime();
                            if (now - peer.lastFrame < 75_000_000L) return true;
                            peer.lastFrame = now;
                            byte[] payload = ByteBuffer.allocate(8 + message.payload().length)
                                    .putLong(session.userId).put(message.payload()).array();
                            relay(peer, new Message(VideoProtocol.VIDEO_FRAME, payload));
                        }
                        case VideoProtocol.CAMERA_ON, VideoProtocol.CAMERA_OFF -> {
                            peer.cameraOn = message.type() == VideoProtocol.CAMERA_ON;
                            try { relay(peer, state(peer)); }
                            catch (IOException e) { return false; }
                        }
                        case VideoProtocol.HEARTBEAT -> peer.offer(new Message(VideoProtocol.HEARTBEAT, new byte[0]));
                        default -> { return false; }
                    }
                    return true;
                }).orElse(false);
                if (!authorized) throw new IOException("Video authorization ended");
            }
        } catch (IOException | RuntimeException ignored) {
            // Never log credentials, images, or credential-bearing exceptions.
        } finally {
            if (handshakePermit) handshakes.release();
            peer.close();
            VideoSessionManager.Session session = peer.session;
            if (session != null && peer.authorized) {
                List<Peer> remaining = sessions.roomPeers(session.roomId);
                sessions.release(session, peer);
                boolean replaced = remaining.stream().anyMatch(other -> other != peer
                        && other.session.userId == session.userId);
                for (Peer other : remaining) if (other != peer && !replaced)
                    other.offer(new Message(VideoProtocol.PEER_LEFT,
                            ByteBuffer.allocate(8).putLong(session.userId).array()));
            }
            peers.remove(peer);
            connections.release();
        }
    }

    private Message state(Peer peer) throws IOException {
        return new Message(peer.cameraOn ? VideoProtocol.CAMERA_ON : VideoProtocol.CAMERA_OFF,
                VideoProtocol.identity(peer.session.userId, peer.session.username));
    }

    // Called with membership lock held; queue offers are bounded and never do I/O.
    private void relay(Peer sender, Message message) {
        for (Peer recipient : sessions.recipients(sender.session)) {
            VideoSessionManager.Session target = recipient.session;
            RoomManager.withMember(target.roomId, target.owner, () -> {
                if (sessions.active(target)) recipient.offer(message);
                return true;
            });
        }
    }

    private void write(Peer peer, DataOutputStream output) {
        try {
            while (!peer.stopped.get()) {
                Message message = peer.take();
                if (message == null) return;
                if (!sessions.active(peer.session)) return;
                peer.writeStarted = System.nanoTime();
                VideoProtocol.write(output, message);
                peer.writeStarted = 0;
            }
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally { peer.close(); }
    }

    private void watchdog() {
        try {
            while (!closed) {
                Thread.sleep(500);
                long now = System.nanoTime();
                for (Peer peer : peers) {
                    VideoSessionManager.Session session = peer.session;
                    boolean authorized = session == null || RoomManager.withMember(session.roomId, session.owner,
                            () -> sessions.active(session)).orElse(false);
                    if (!authorized || peer.overflow || now - peer.lastRead > 15_000_000_000L
                            || peer.writeStarted != 0 && now - peer.writeStarted > 5_000_000_000L)
                        peer.close();
                }
                sessions.removeExpired();
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() {
        closed = true;
        ServerSocket server = listener;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        peers.forEach(Peer::close);
    }

    static final class Peer {
        final Socket socket;
        final AtomicBoolean stopped = new AtomicBoolean();
        final ArrayDeque<Message> controls = new ArrayDeque<>();
        Message latestFrame;
        volatile VideoSessionManager.Session session;
        volatile boolean cameraOn;
        volatile boolean authorized;
        volatile boolean overflow;
        volatile long writeStarted, lastRead = System.nanoTime(), lastFrame;
        Peer(Socket socket) { this.socket = socket; }
        synchronized void offer(Message message) {
            if (stopped.get()) return;
            if (message.type() == VideoProtocol.VIDEO_FRAME) latestFrame = message;
            else {
                if (controls.size() >= 16) { // Watchdog/reader will close a stalled peer.
                    overflow = true;
                    return;
                }
                if (message.type() == VideoProtocol.CAMERA_OFF || message.type() == VideoProtocol.PEER_LEFT)
                    latestFrame = null;
                controls.addLast(message);
            }
            notifyAll();
        }
        synchronized Message take() throws InterruptedException {
            while (!stopped.get() && controls.isEmpty() && latestFrame == null) wait();
            if (stopped.get()) return null;
            if (!controls.isEmpty()) return controls.removeFirst();
            Message result = latestFrame; latestFrame = null; return result;
        }
        void close() {
            if (!stopped.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) { }
            synchronized (this) { controls.clear(); latestFrame = null; notifyAll(); }
        }
    }
}
