package com.example.onlinemeeting.client;

import com.example.onlinemeeting.video.VideoProtocol;
import com.example.onlinemeeting.video.VideoProtocol.Message;
import com.github.sarxos.webcam.Webcam;
import com.github.eduramiba.webcamcapture.drivers.NativeDriver;
import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Single-use video transport. Owns credentials; camera is initially OFF. */
public final class VideoClient {
    public interface Listener {
        void onFrame(String username, BufferedImage image, boolean local);
        void onCameraState(String username, boolean on, boolean local);
        void onStatus(String message);
        void onDisconnected();
    }
    private record Captured(BufferedImage image, long generation) { }
    private static final Semaphore CAMERA = new Semaphore(1);
    private static boolean driverInstalled;
    private final String host;
    private final VideoCredentials credentials;
    private final Listener listener;
    private final Socket socket = new Socket();
    private final Object queueLock = new Object();
    private final Deque<Message> controls = new ArrayDeque<>();
    private Message latestFrame;
    private final ArrayBlockingQueue<Captured> captured = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<Message> received = new ArrayBlockingQueue<>(1);
    private final ConcurrentMap<Long, String> identities = new ConcurrentHashMap<>();
    private final Set<Long> cameras = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong cameraGeneration = new AtomicLong();
    private final Object cameraSignal = new Object();
    private volatile boolean running, cameraWanted, cameraOn;
    private volatile long ownId, writeStarted, lastRead = System.nanoTime();
    private volatile Webcam webcam;
    private volatile String startupFailure;
    private CompletableFuture<Void> startup;
    private final CompletableFuture<Void> shutdown = new CompletableFuture<>();

    public VideoClient(String host, VideoCredentials credentials, Listener listener) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Video server host required");
        this.host = host;
        this.credentials = Objects.requireNonNull(credentials);
        this.listener = Objects.requireNonNull(listener);
        if (credentials.isExpired()) throw new IllegalArgumentException("Video credentials expired");
    }
    public synchronized CompletableFuture<Void> startAsync() {
        if (startup != null) return startup;
        startup = new CompletableFuture<>();
        worker("video-start", () -> {
            try {
                if (stopped.get()) throw new IOException("Video stopped");
                socket.connect(new InetSocketAddress(host, credentials.getPort()), 5000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] token = credentials.getToken();
                byte[] auth = new byte[49];
                try {
                    ByteBuffer.wrap(auth).put((byte) VideoProtocol.VERSION)
                            .put(HexFormat.of().parseHex(credentials.getSessionId())).put(token);
                    writeStarted = System.nanoTime();
                    VideoProtocol.write(output, new Message(VideoProtocol.AUTH, auth));
                    writeStarted = 0;
                } finally { Arrays.fill(token, (byte) 0); Arrays.fill(auth, (byte) 0); }
                Message response = VideoProtocol.read(input, false);
                if (response.type() == VideoProtocol.ERROR)
                    throw new IOException("Video rejected: " + safeError(response.payload()));
                if (response.type() != VideoProtocol.AUTH_OK) throw new IOException("Invalid video acknowledgment");
                ownId = ByteBuffer.wrap(response.payload()).getLong();
                if (ownId <= 0 || stopped.get()) throw new IOException("Video startup cancelled");
                socket.setSoTimeout(15_000);
                lastRead = System.nanoTime();
                running = true;
                worker("video-write", () -> writeLoop(output));
                worker("video-read", () -> readLoop(input));
                worker("video-decode", this::decodeLoop);
                worker("video-camera", this::captureLoop);
                worker("video-encode", this::encodeLoop);
                worker("video-heartbeat", this::heartbeat);
                CompletableFuture.runAsync(() -> startup.complete(null));
            } catch (Exception | LinkageError e) {
                String message = e instanceof IOException && e.getMessage() != null
                        && e.getMessage().startsWith("Video rejected:") ? e.getMessage()
                        : "Unable to connect video. Chat and audio remain available.";
                startupFailure = message;
                status(message);
                CompletableFuture.runAsync(() -> startup.completeExceptionally(new IOException(message)));
                stopAsync();
            }
        });
        // Covers a blocked handshake write as well as connect/read deadlines.
        worker("video-start-deadline", () -> {
            try { Thread.sleep(10_000); if (!startup.isDone()) stopAsync(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        return startup;
    }

    private static String safeError(byte[] bytes) {
        String value = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        return Set.of("VIDEO_FULL", "ALREADY_CONNECTED", "UNAUTHORIZED", "NOT_IN_ROOM").contains(value)
                ? value : "UNAVAILABLE";
    }
    public boolean isRunning() { return running && !stopped.get(); }
    public boolean isCameraOn() { return cameraOn; }
    public boolean isCameraRequested() { return cameraWanted; }

    /** Only flags and queues on caller thread; webcam operations belong to captureLoop. */
    public void setCameraEnabled(boolean enabled) {
        if (!isRunning()) return;
        synchronized (queueLock) {
            cameraWanted = enabled;
            cameraGeneration.incrementAndGet();
            if (!enabled) {
                cameraOn = false;
                captured.clear();
                latestFrame = null;
                offer(new Message(VideoProtocol.CAMERA_OFF, new byte[0]));
            }
        }
        if (!enabled) cameraState("", false, true);
        synchronized (cameraSignal) { cameraSignal.notifyAll(); }
    }

    private void offer(Message message) {
        boolean overflow = false;
        synchronized (queueLock) {
            if (stopped.get()) return;
            if (message.type() == VideoProtocol.VIDEO_FRAME) latestFrame = message;
            else if (controls.size() < 16) controls.addLast(message);
            else overflow = true;
            queueLock.notifyAll();
        }
        if (overflow) stopAsync();
    }
    private void writeLoop(DataOutputStream output) {
        try {
            while (isRunning()) {
                Message message;
                synchronized (queueLock) {
                    while (isRunning() && controls.isEmpty() && latestFrame == null) queueLock.wait();
                    if (!isRunning()) break;
                    if (!controls.isEmpty()) message = controls.removeFirst();
                    else { message = latestFrame; latestFrame = null; }
                }
                if (message.type() == VideoProtocol.VIDEO_FRAME && !cameraOn) continue;
                writeStarted = System.nanoTime();
                VideoProtocol.write(output, message);
                writeStarted = 0;
            }
        } catch (IOException | InterruptedException e) {
            if (isRunning()) status("Video sending stopped.");
        } finally { stopAsync(); }
    }
    private void readLoop(DataInputStream input) {
        try {
            while (isRunning()) {
                Message message = VideoProtocol.read(input, false);
                lastRead = System.nanoTime();
                switch (message.type()) {
                    case VideoProtocol.VIDEO_FRAME -> {
                        long id = ByteBuffer.wrap(message.payload()).getLong();
                        if (id == ownId || !identities.containsKey(id) || !cameras.contains(id)) continue;
                        if (!received.offer(message)) { received.poll(); received.offer(message); }
                    }
                    case VideoProtocol.CAMERA_ON, VideoProtocol.CAMERA_OFF -> {
                        VideoProtocol.Identity identity = VideoProtocol.identity(message.payload());
                        if (identity.userId() == ownId) continue;
                        if (!identities.containsKey(identity.userId()) && identities.size() >= 32)
                            throw new IOException("Too many video identities");
                        identities.put(identity.userId(), identity.username());
                        boolean on = message.type() == VideoProtocol.CAMERA_ON;
                        if (on) cameras.add(identity.userId()); else cameras.remove(identity.userId());
                        if (!on) received.clear();
                        cameraState(identity.username(), on, identity.userId() == ownId);
                    }
                    case VideoProtocol.PEER_LEFT -> {
                        long id = ByteBuffer.wrap(message.payload()).getLong();
                        cameras.remove(id);
                        String name = identities.remove(id);
                        received.clear();
                        if (name != null) cameraState(name, false, false);
                    }
                    case VideoProtocol.HEARTBEAT -> { }
                    default -> throw new IOException("Unexpected video message");
                }
            }
        } catch (IOException | RuntimeException e) {
            if (isRunning()) status("Video disconnected. Chat and audio remain available.");
        } finally { stopAsync(); }
    }
    private void decodeLoop() {
        try {
            while (isRunning()) {
                Message message = received.take();
                long id = ByteBuffer.wrap(message.payload()).getLong();
                BufferedImage image = decode(message.payload(), 8);
                String name = identities.get(id);
                if (name != null && cameras.contains(id) && isRunning()) frame(name, image, false);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (isRunning()) status("Invalid video image received.");
            stopAsync();
        }
    }
    private static BufferedImage decode(byte[] jpeg, int offset) throws IOException {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(jpeg, offset, jpeg.length - offset))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid JPEG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())
                        || reader.getWidth(0) != 320 || reader.getHeight(0) != 240)
                    throw new IOException("Unsupported video dimensions");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Invalid video image");
                return image;
            } finally { reader.dispose(); }
        }
    }

    private static synchronized void installDriver() {
        if (!driverInstalled) {
            Webcam.setDriver(new NativeDriver());
            driverInstalled = true;
        }
    }
    private void captureLoop() {
        boolean acquired = false;
        try {
            while (isRunning()) {
                if (!cameraWanted) {
                    closeCamera();
                    if (acquired) { CAMERA.release(); acquired = false; }
                    synchronized (cameraSignal) {
                        while (isRunning() && !cameraWanted) cameraSignal.wait();
                    }
                }
                if (!isRunning()) break;
                if (!acquired) {
                    if (!CAMERA.tryAcquire()) {
                        cameraWanted = false;
                        status("Camera is busy. Incoming video remains enabled.");
                        cameraState("", false, true);
                        continue;
                    }
                    acquired = true;
                }
                long generation = cameraGeneration.get();
                if (webcam == null) {
                    installDriver();
                    Webcam device = Webcam.getDefault();
                    if (device == null) throw new IOException("No webcam");
                    webcam = device; // Cleanup can find a partially opened device.
                    // Capture at a supported mode, then scale to the fixed wire resolution.
                    Dimension chosen = Arrays.stream(device.getViewSizes())
                            .min(Comparator.comparingLong(d -> Math.abs((long) d.width * d.height - 320L * 240)))
                            .orElse(new Dimension(320, 240));
                    device.setViewSize(chosen);
                    if (!device.open()) throw new IOException("Unable to open webcam");
                    if (!isRunning()) {
                        device.close(); // Covers stop racing with a native open.
                        break;
                    }
                }
                boolean opened = false;
                synchronized (queueLock) {
                    if (!cameraWanted || generation != cameraGeneration.get() || !isRunning()) continue;
                    if (!cameraOn) {
                        cameraOn = true;
                        offer(new Message(VideoProtocol.CAMERA_ON, new byte[0]));
                        opened = true;
                    }
                }
                if (opened) {
                    cameraState("", true, true);
                }
                long started = System.nanoTime();
                Webcam device = webcam;
                if (device == null) continue;
                BufferedImage source = device.getImage();
                if (source != null && cameraWanted && generation == cameraGeneration.get()) {
                    BufferedImage copy = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = copy.createGraphics();
                    try { graphics.drawImage(source, 0, 0, 320, 240, null); }
                    finally { graphics.dispose(); }
                    Captured next = new Captured(copy, generation);
                    if (!captured.offer(next)) { captured.poll(); captured.offer(next); }
                }
                long remaining = 100_000_000L - (System.nanoTime() - started);
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception | LinkageError e) {
            cameraWanted = false; cameraOn = false;
            captured.clear();
            offer(new Message(VideoProtocol.CAMERA_OFF, new byte[0]));
            cameraState("", false, true);
            status("Webcam unavailable. Incoming video remains enabled; toggle Camera to retry.");
        } finally {
            closeCamera();
            if (acquired) CAMERA.release();
            // A native camera failure must not stop receiving; restart only this idle worker.
            if (isRunning()) worker("video-camera-retry", this::captureLoop);
        }
    }

    private void closeCamera() {
        Webcam device;
        synchronized (cameraSignal) {
            device = webcam;
            webcam = null;
        }
        if (device != null) try { device.close(); } catch (RuntimeException | LinkageError ignored) { }
    }
    private void encodeLoop() {
        try {
            while (isRunning()) {
                Captured next = captured.take();
                if (!cameraOn || next.generation() != cameraGeneration.get()) continue;
                byte[] jpeg = encode(next.image());
                if (jpeg.length > VideoProtocol.MAX_JPEG) continue;
                synchronized (queueLock) {
                    if (!cameraOn || next.generation() != cameraGeneration.get() || !isRunning()) continue;
                    latestFrame = new Message(VideoProtocol.VIDEO_FRAME, jpeg);
                    queueLock.notifyAll();
                }
                frame("", next.image(), true);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (isRunning()) { setCameraEnabled(false); status("Camera encoding stopped. Incoming video remains enabled."); }
        } finally {
            if (isRunning()) worker("video-encode-retry", this::encodeLoop);
        }
    }
    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.65f);
            writer.write(null, new IIOImage(image, null, null), parameters);
            stream.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
    private void heartbeat() {
        try {
            long next = 0;
            while (isRunning()) {
                long now = System.nanoTime();
                if (credentials.isExpired() || now - lastRead > 15_000_000_000L
                        || writeStarted != 0 && now - writeStarted > 5_000_000_000L) {
                    status("Video session expired or connection stalled.");
                    stopAsync(); return;
                }
                if (now >= next) {
                    offer(new Message(VideoProtocol.HEARTBEAT, new byte[0]));
                    next = now + 5_000_000_000L;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private void worker(String name, Runnable action) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try { action.run(); } finally { workers.remove(Thread.currentThread()); }
        });
        workers.add(thread);
        thread.start();
    }
    private void status(String message) { try { listener.onStatus(message); } catch (RuntimeException ignored) { } }
    private void frame(String name, BufferedImage image, boolean local) {
        try { listener.onFrame(name, image, local); } catch (RuntimeException ignored) { }
    }
    private void cameraState(String name, boolean on, boolean local) {
        try { listener.onCameraState(name, on, local); } catch (RuntimeException ignored) { }
    }
    public CompletableFuture<Void> stopAsync() {
        if (!stopped.compareAndSet(false, true)) return shutdown;
        running = false; cameraWanted = false; cameraOn = false;
        cameraGeneration.incrementAndGet();
        credentials.clear();
        Thread.ofVirtual().name("video-cleanup").start(() -> {
            try {
                try { socket.close(); } catch (IOException ignored) { }
                synchronized (queueLock) { controls.clear(); latestFrame = null; queueLock.notifyAll(); }
                synchronized (cameraSignal) { cameraSignal.notifyAll(); }
                captured.clear(); received.clear(); identities.clear(); cameras.clear();
                workers.forEach(Thread::interrupt);
                // Native device close runs separately: it must not prevent socket/worker cleanup.
                Thread.ofVirtual().name("video-camera-close").start(this::closeCamera);
                CompletableFuture<Void> pending;
                synchronized (this) { pending = startup; }
                if (pending != null) CompletableFuture.runAsync(() ->
                        pending.completeExceptionally(new IOException(startupFailure == null
                                ? "Video stopped" : startupFailure)));
                long deadline = System.nanoTime() + 2_000_000_000L;
                for (Thread worker : workers) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    try { worker.join(java.time.Duration.ofNanos(left)); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
                try { listener.onDisconnected(); } catch (RuntimeException ignored) { }
            } finally { shutdown.complete(null); }
        });
        return shutdown;
    }
}
