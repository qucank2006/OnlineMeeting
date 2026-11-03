package com.example.onlinemeeting.client;

import com.example.onlinemeeting.config.AppConfig;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-use, basic bearer-session UDP client. start/stop do no device or network
 * work on the caller's thread. This client owns the supplied credentials and
 * clears them on stop; use a new instance and new session after stopping.
 */
public class AudioClient {

    private static final int VERSION = 1;
    private static final int REGISTER = 1;
    private static final int HEARTBEAT = 2;
    private static final int AUDIO = 3;
    private static final int REGISTER_OK = 4;
    private static final int CLIENT_HEADER_BYTES = 50;
    private static final int SERVER_HEADER_BYTES = 18;
    private static final int BUFFER_SIZE = 1024;
    private static final int REGISTER_ATTEMPTS = 3;
    private static final long REGISTER_TIMEOUT_NANOS = 1_000_000_000L;

    private final String serverHost;
    private final AudioCredentials credentials;
    private final byte[] sessionId;
    private final Object stateLock = new Object();
    private final List<Thread> workers = new ArrayList<>();
    private final ArrayBlockingQueue<byte[]> playback = new ArrayBlockingQueue<>(16);
    private final AtomicLong muteGeneration = new AtomicLong();

    private DatagramSocket socket;
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private CompletableFuture<Void> startup;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean stopRequested;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile boolean microphoneAvailable;
    private volatile String failureMessage;

    public AudioClient(String serverHost, AudioCredentials credentials) {
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("Audio server host is required");
        }
        this.serverHost = serverHost.strip();
        this.credentials = Objects.requireNonNull(credentials, "Audio credentials are required");
        if (credentials.isExpired()) {
            throw new IllegalArgumentException("Audio credentials have expired");
        }
        this.sessionId = HexFormat.of().parseHex(credentials.getSessionId());
    }

    /**
     * Retained for old callers to compile. Room ID alone cannot authorize audio;
     * startAsync fails and this constructor never enables the old UDP protocol.
     */
    @Deprecated
    public AudioClient(String serverHost, String roomId) {
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("Audio server host is required");
        }
        this.serverHost = serverHost.strip();
        this.credentials = null;
        this.sessionId = new byte[16];
    }

    private AudioFormat getAudioFormat() {
        return new AudioFormat(16000.0f, 16, 1, true, false);
    }

    /** Starts asynchronously. Observe startAsync() for readiness or failure. */
    public void start() throws Exception {
        startAsync();
    }

    /**
     * Completes after registration, speaker startup, and microphone startup (or
     * receive-only fallback). Never call get/join on the JavaFX thread.
     */
    public CompletableFuture<Void> startAsync() {
        synchronized (stateLock) {
            if (startup != null) {
                return startup;
            }
            if (stopRequested || credentials == null || credentials.isExpired()) {
                startup = CompletableFuture.failedFuture(
                        new IOException("Valid audio credentials are required"));
                return startup;
            }
            startup = new CompletableFuture<>();
            startup.whenComplete((ignored, failure) -> {
                if (startup.isCancelled()) {
                    stop();
                }
            });
            startWorker("audio-startup", this::initializeAudio);
            return startup;
        }
    }

    private void initializeAudio() {
        try {
            InetAddress address = InetAddress.getByName(serverHost);
            int port = credentials == null
                    ? AppConfig.getInstance().getUdpPort() : credentials.getUdpPort();
            InetSocketAddress server = new InetSocketAddress(address, port);
            DatagramSocket udp = new DatagramSocket();
            boolean cancelled;
            synchronized (stateLock) {
                cancelled = stopRequested;
                if (!cancelled) {
                    socket = udp;
                }
            }
            if (cancelled) {
                udp.close();
                return;
            }
            // Connecting UDP filters incoming traffic to this server endpoint.
            udp.connect(server);
            register(udp, server);
            udp.setSoTimeout(1000);
            synchronized (stateLock) {
                ensureActive();
                running = true;
                startWorker("audio-receive", () -> receiveAudio(udp, server));
                startWorker("audio-heartbeat", () -> heartbeat(udp));
            }

            SourceDataLine output = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, getAudioFormat()));
            try {
                output.open(getAudioFormat());
                output.start();
                synchronized (stateLock) {
                    ensureActive();
                    speaker = output;
                    startWorker("audio-playback", () -> playAudio(output));
                }
            } finally {
                boolean retained;
                synchronized (stateLock) {
                    retained = speaker == output;
                }
                if (!retained) {
                    closeLine(output);
                }
            }

            openMicrophone(udp);
            synchronized (stateLock) {
                ensureActive();
            }
            // Do not let user future callbacks delay startup/resource handling.
            Thread.ofVirtual().name("audio-ready").start(() -> {
                if (!running || credentials.isExpired()) {
                    startup.completeExceptionally(new IOException("Audio startup was stopped"));
                } else {
                    startup.complete(null);
                }
            });
        } catch (Exception e) {
            fail("Unable to register audio or initialize audio playback");
        }
    }

    private void register(DatagramSocket udp, InetSocketAddress server) throws IOException {
        byte[] buffer = new byte[SERVER_HEADER_BYTES + BUFFER_SIZE + 1];
        try {
            for (int attempt = 0; attempt < REGISTER_ATTEMPTS; attempt++) {
                ensureActive();
                sendPacket(udp, REGISTER, null, 0);
                long deadline = System.nanoTime() + REGISTER_TIMEOUT_NANOS;
                while (true) {
                    ensureActive();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    udp.setSoTimeout((int) Math.max(1, Math.min(1000,
                            (remaining + 999_999) / 1_000_000)));
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        udp.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    if (fromServer(packet, server) && packet.getLength() == SERVER_HEADER_BYTES) {
                        ByteBuffer reply = ByteBuffer.wrap(buffer, 0, packet.getLength());
                        if (Byte.toUnsignedInt(reply.get()) == VERSION
                                && Byte.toUnsignedInt(reply.get()) == REGISTER_OK) {
                            byte[] acknowledgedId = new byte[16];
                            reply.get(acknowledgedId);
                            if (Arrays.equals(sessionId, acknowledgedId)) {
                                return;
                            }
                        }
                    }
                    // AUDIO and unrelated/malformed packets are never acknowledgments.
                }
            }
            throw new IOException("Audio registration timed out");
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void openMicrophone(DatagramSocket udp) {
        TargetDataLine input = null;
        boolean installed = false;
        try {
            input = (TargetDataLine) AudioSystem.getLine(
                    new DataLine.Info(TargetDataLine.class, getAudioFormat()));
            input.open(getAudioFormat());
            input.start();
            TargetDataLine capture = input;
            synchronized (stateLock) {
                ensureActive();
                microphone = input;
                microphoneAvailable = true;
                startWorker("audio-send", () -> sendAudio(udp, capture));
                installed = true;
            }
        } catch (Exception ignored) {
            // Speaker, receiving and heartbeats remain available without a mic.
            microphoneAvailable = false;
        } finally {
            if (!installed && input != null) {
                closeLine(input);
            }
        }
    }

    private void heartbeat(DatagramSocket udp) {
        try {
            long nextHeartbeat = System.nanoTime();
            while (running) {
                ensureActive();
                if (System.nanoTime() - nextHeartbeat >= 0) {
                    sendPacket(udp, HEARTBEAT, null, 0);
                    nextHeartbeat = System.nanoTime() + 5_000_000_000L;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fail("Audio session expired or UDP sending failed");
        }
    }

    private void sendAudio(DatagramSocket udp, TargetDataLine input) {
        byte[] pcm = new byte[BUFFER_SIZE];
        long previousGeneration = -1;
        try {
            while (running) {
                ensureActive();
                long generation = muteGeneration.get();
                if (generation != previousGeneration) {
                    // Only the capture worker touches the potentially blocking line.
                    input.flush();
                    previousGeneration = generation;
                }
                int bytesRead = input.read(pcm, 0, pcm.length);
                if (!running) {
                    return;
                }
                if (bytesRead <= 0 || (bytesRead & 1) != 0) {
                    throw new IllegalStateException("Microphone capture stopped");
                }
                // Reads continue while muted; discard chunks spanning a toggle.
                if (muted || generation != muteGeneration.get()) {
                    continue;
                }
                sendPacket(udp, AUDIO, pcm, bytesRead, generation);
            }
        } catch (IOException e) {
            fail("Audio session expired or UDP sending failed");
        } catch (RuntimeException e) {
            microphoneAvailable = false;
            synchronized (stateLock) {
                if (microphone == input) {
                    microphone = null;
                }
            }
            closeLine(input);
        } finally {
            Arrays.fill(pcm, (byte) 0);
        }
    }

    private void receiveAudio(DatagramSocket udp, InetSocketAddress server) {
        byte[] buffer = new byte[SERVER_HEADER_BYTES + BUFFER_SIZE + 1];
        try {
            while (running) {
                ensureActive();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udp.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                int length = packet.getLength();
                int pcmLength = length - SERVER_HEADER_BYTES;
                if (!fromServer(packet, server) || length > SERVER_HEADER_BYTES + BUFFER_SIZE
                        || pcmLength <= 0 || (pcmLength & 1) != 0) {
                    continue;
                }
                ByteBuffer frame = ByteBuffer.wrap(buffer, 0, length);
                if (Byte.toUnsignedInt(frame.get()) != VERSION
                        || Byte.toUnsignedInt(frame.get()) != AUDIO) {
                    continue;
                }
                byte[] senderId = new byte[16];
                frame.get(senderId);
                if (Arrays.equals(senderId, sessionId)) {
                    continue;
                }
                byte[] pcm = new byte[pcmLength];
                frame.get(pcm);
                if (!playback.offer(pcm)) {
                    // Bound latency/memory if playback cannot keep up.
                    playback.poll();
                    playback.offer(pcm);
                }
            }
        } catch (Exception e) {
            fail("Audio receiving stopped or credentials expired");
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void playAudio(SourceDataLine output) {
        try {
            while (running) {
                byte[] pcm = playback.take();
                int offset = 0;
                while (running && offset < pcm.length) {
                    int written = output.write(pcm, offset, pcm.length - offset);
                    if (written <= 0) {
                        throw new IOException("Audio playback stopped");
                    }
                    offset += written;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fail("Audio playback stopped");
        }
    }

    private boolean fromServer(DatagramPacket packet, InetSocketAddress server) {
        return packet.getAddress().equals(server.getAddress())
                && packet.getPort() == server.getPort();
    }

    private void sendPacket(DatagramSocket udp, int type, byte[] pcm, int length)
            throws IOException {
        sendPacket(udp, type, pcm, length, -1);
    }

    private void sendPacket(DatagramSocket udp, int type, byte[] pcm, int length,
                            long captureGeneration) throws IOException {
        ensureActive();
        if (type == AUDIO && (muted || captureGeneration != muteGeneration.get())) {
            return;
        }
        byte[] token = credentials.getToken();
        byte[] bytes = new byte[CLIENT_HEADER_BYTES + length];
        try {
            ByteBuffer packet = ByteBuffer.wrap(bytes);
            packet.put((byte) VERSION).put((byte) type).put(sessionId).put(token);
            if (length > 0) {
                packet.put(pcm, 0, length);
            }
            ensureActive();
            if (type != AUDIO || (!muted && captureGeneration == muteGeneration.get())) {
                udp.send(new DatagramPacket(bytes, bytes.length));
            }
        } finally {
            Arrays.fill(token, (byte) 0);
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void ensureActive() throws IOException {
        synchronized (stateLock) {
            if (stopRequested || credentials == null || credentials.isExpired()) {
                throw new IOException("Audio session is not active");
            }
        }
    }

    // Called under stateLock; starting is serialized with stop().
    private void startWorker(String name, Runnable action) {
        if (stopRequested) {
            return;
        }
        Thread worker = Thread.ofVirtual().name(name).unstarted(action);
        workers.add(worker);
        worker.start();
    }

    public void mute() {
        muted = true;
        muteGeneration.incrementAndGet();
    }

    public void unmute() {
        muteGeneration.incrementAndGet();
        muted = false;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isReceiveOnly() {
        return running && !microphoneAvailable;
    }

    /** Generic status only; never contains credentials or raw exception details. */
    public String getFailureMessage() {
        return failureMessage;
    }

    private void fail(String message) {
        if (failureMessage == null) {
            failureMessage = message;
        }
        stop();
    }

    public void stop() {
        stopAsync();
    }

    /** Returns immediately; device closes and bounded worker waits run in background. */
    public CompletableFuture<Void> stopAsync() {
        synchronized (stateLock) {
            if (stopRequested) {
                return stopped;
            }
            stopRequested = true;
            running = false;
            microphoneAvailable = false;
        }
        Thread.ofVirtual().name("audio-cleanup").start(this::cleanup);
        return stopped;
    }

    private void cleanup() {
        DatagramSocket udp;
        TargetDataLine input;
        SourceDataLine output;
        List<Thread> threads;
        CompletableFuture<Void> starting;
        synchronized (stateLock) {
            udp = socket;
            socket = null;
            input = microphone;
            microphone = null;
            output = speaker;
            speaker = null;
            threads = List.copyOf(workers);
            workers.clear();
            starting = startup;
        }
        if (udp != null) {
            udp.close(); // Unblocks both registration and normal UDP receive.
        }
        for (Thread worker : threads) {
            worker.interrupt();
        }
        if (credentials != null) {
            credentials.clear();
        }
        if (starting != null && !starting.isDone()) {
            Thread.ofVirtual().name("audio-startup-failure").start(() ->
                    starting.completeExceptionally(new IOException(
                            failureMessage == null ? "Audio startup cancelled" : failureMessage)));
        }
        closeLine(input);
        closeLine(output);
        playback.clear();
        long deadline = System.nanoTime() + 1_000_000_000L;
        for (Thread worker : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                worker.join(Math.max(1, remaining / 1_000_000));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stopped.complete(null);
    }

    private static void closeLine(javax.sound.sampled.DataLine line) {
        if (line != null) {
            try {
                line.stop();
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    line.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
