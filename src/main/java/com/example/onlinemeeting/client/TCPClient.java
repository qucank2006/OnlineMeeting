package com.example.onlinemeeting.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class TCPClient {

    // No retained pool/scheduler: each short-lived task owns a virtual thread.
    // Completions initiated here never run callbacks on the reader or under stateLock.
    private static final Executor AUDIO_COMPLETIONS =
            task -> Thread.ofVirtual().name("tcp-audio-completion").start(task);

    private final Object stateLock = new Object();
    // All connection state, queues, and listener access use stateLock.
    private Connection connection;
    private Consumer<String> eventListener;
    private long nextConnectionId;

    @FunctionalInterface
    public interface ConnectionLifecycleListener {
        void onDisconnected(long connectionId);
    }

    /**
     * Registration bound permanently to one connection, never to its replacement.
     * close() suppresses queued delivery; an already executing callback may finish.
     */
    public final class ConnectionSubscription implements AutoCloseable {
        private final Connection observed;
        private final ConnectionLifecycleListener listener;
        private boolean removed;
        private boolean deliveryQueued;

        private ConnectionSubscription(Connection observed, ConnectionLifecycleListener listener) {
            this.observed = observed;
            this.listener = listener;
        }

        public long getConnectionId() {
            return observed.id;
        }

        @Override
        public void close() {
            synchronized (stateLock) {
                removed = true;
                observed.lifecycleListeners.remove(this);
            }
        }
    }

    /**
     * Registers for the current established connection. If it has already closed,
     * asynchronously reports that disconnect, closing the registration race.
     * Re-register after reconnect. Consumers must retain connection/generation
     * identity when scheduling their own asynchronous follow-up work.
     */
    public ConnectionSubscription addConnectionLifecycleListener(
            ConnectionLifecycleListener listener) {
        Objects.requireNonNull(listener, "Connection lifecycle listener is required");
        ConnectionSubscription subscription;
        boolean disconnected;
        synchronized (stateLock) {
            if (connection == null || !connection.connected) {
                throw new IllegalStateException("No established TCP connection");
            }
            subscription = new ConnectionSubscription(connection, listener);
            disconnected = connection.closed;
            if (disconnected) {
                subscription.deliveryQueued = true;
            } else {
                connection.lifecycleListeners.add(subscription);
            }
        }
        if (disconnected) {
            dispatchDisconnect(subscription);
        }
        return subscription;
    }

    public void removeConnectionLifecycleListener(ConnectionSubscription subscription) {
        Objects.requireNonNull(subscription, "Connection subscription is required").close();
    }

    private static final class Connection {
        final long id;
        final List<ConnectionSubscription> lifecycleListeners = new ArrayList<>();
        boolean disconnectNotified;
        final Socket socket = new Socket();
        final Object sendLock = new Object();
        final Deque<String> responses = new ArrayDeque<>();
        final Deque<String> events = new ArrayDeque<>();
        BufferedReader reader;
        PrintWriter writer;
        boolean connected;
        boolean closed;
        IOException failure;
        PendingAudio pendingAudio;
        PendingVideo pendingVideo;

        Connection(long id) {
            this.id = id;
        }
    }

    private static final class VideoRequestFuture extends CompletableFuture<VideoCredentials> {
        Runnable beforeCancel = () -> { };

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            // Cleanup precedes user callbacks, which cancel() may run synchronously.
            beforeCancel.run();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class PendingVideo {
        final String requestId = UUID.randomUUID().toString();
        final String expectedRoomId;
        final VideoRequestFuture future = new VideoRequestFuture();
        final long startedAt = System.nanoTime();
        final long timeoutNanos;
        Thread timeoutThread;
        boolean responseClaimed;

        PendingVideo(String expectedRoomId, long timeoutNanos) {
            this.expectedRoomId = expectedRoomId;
            this.timeoutNanos = timeoutNanos;
        }

        boolean timedOut() {
            return System.nanoTime() - startedAt >= timeoutNanos;
        }
    }

    private static final class AudioRequestFuture extends CompletableFuture<AudioCredentials> {
        Runnable beforeCancel = () -> { };

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            // Cleanup precedes user callbacks, which cancel() may run synchronously.
            beforeCancel.run();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class PendingAudio {
        final String requestId = UUID.randomUUID().toString();
        final String expectedRoomId;
        final AudioRequestFuture future = new AudioRequestFuture();
        final long startedAt = System.nanoTime();
        final long timeoutNanos;
        Thread timeoutThread;
        boolean responseClaimed;

        PendingAudio(String expectedRoomId, long timeoutNanos) {
            this.expectedRoomId = expectedRoomId;
            this.timeoutNanos = timeoutNanos;
        }

        boolean timedOut() {
            return System.nanoTime() - startedAt >= timeoutNanos;
        }
    }

    /**
     * The positive system property onlinemeeting.tcp.connectTimeoutMillis
     * configures the connect timeout (default: 5000 milliseconds).
     */
    public void connect(String host, int port) throws IOException {
        int timeout = Integer.getInteger(
                "onlinemeeting.tcp.connectTimeoutMillis", 5000);
        if (timeout <= 0) {
            throw new IllegalArgumentException("Connection timeout must be positive");
        }

        Connection next;
        Connection previous;
        PendingAudio previousAudio = null;
        synchronized (stateLock) {
            next = new Connection(++nextConnectionId);
            previous = connection;
            if (previous != null) {
                previousAudio = closeState(previous, null, true);
            }
            connection = next;
        }
        failDetachedAudio(previousAudio);
        notifyDisconnected(previous);
        closeSocket(previous);

        try {
            next.socket.connect(new InetSocketAddress(host, port), timeout);
            next.reader = new BufferedReader(
                    new InputStreamReader(next.socket.getInputStream()));
            next.writer = new PrintWriter(next.socket.getOutputStream(), true);

            synchronized (stateLock) {
                if (connection != next || next.closed) {
                    throw new IOException("Connection attempt was cancelled");
                }
                next.connected = true;
                Thread listener = new Thread(() -> listen(next), "tcp-client-listener");
                Thread dispatcher = new Thread(
                        () -> dispatchEvents(next), "tcp-client-events");
                listener.setDaemon(true);
                dispatcher.setDaemon(true);
                listener.start();
                dispatcher.start();
            }
        } catch (IOException | RuntimeException | Error e) {
            closeConnection(next,
                    e instanceof IOException ? (IOException) e
                            : new IOException("Connection initialization failed", e),
                    true);
            throw e;
        }
    }

    /** Returns the active socket's remote address at the time of this call. */
    public InetAddress getConnectedServerAddress() {
        synchronized (stateLock) {
            Connection current = connection;
            if (current == null || !current.connected || current.closed
                    || current.socket == null || current.socket.isClosed()
                    || !current.socket.isConnected()
                    || current.socket.isInputShutdown() || current.socket.isOutputShutdown()) {
                throw new IllegalStateException("TCP client is disconnected; no connected server address");
            }
            InetAddress address = current.socket.getInetAddress();
            if (address == null) {
                throw new IllegalStateException("TCP connection has no remote server address");
            }
            return address;
        }
    }

    private void listen(Connection current) {
        IOException failure = null;
        try {
            String message;
            while ((message = current.reader.readLine()) != null) {
                if (isVideoResponse(message)) {
                    routeVideoResponse(current, message);
                    continue;
                }
                if (isAudioResponse(message)) {
                    routeAudioResponse(current, message);
                    continue;
                }
                synchronized (stateLock) {
                    if (current.closed || connection != current) {
                        return;
                    }
                    if (isEvent(message)) {
                        current.events.addLast(message);
                    } else {
                        current.responses.addLast(message);
                    }
                    stateLock.notifyAll();
                }
            }
            failure = new IOException("Server closed the connection");
        } catch (IOException e) {
            failure = e;
        } finally {
            closeConnection(current, failure, false);
        }
    }

    private void dispatchEvents(Connection current) {
        try {
            while (true) {
                Consumer<String> listener;
                String event;
                synchronized (stateLock) {
                    while (!current.closed && connection == current
                            && (eventListener == null || current.events.isEmpty())) {
                        stateLock.wait();
                    }
                    if (current.closed || connection != current) {
                        return;
                    }
                    listener = eventListener;
                    event = current.events.removeFirst();
                }
                // Never invoke application code while holding the state lock.
                // A callback already selected may finish after clear/disconnect.
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isEvent(String message) {
        return message.startsWith("MEMBERS|")
                || message.startsWith("USER_JOINED|")
                || message.startsWith("USER_LEFT|")
                || message.startsWith("CHAT|");
    }

    /**
     * Requests credentials without consuming the legacy response/event queues.
     * No network I/O runs on the caller's thread. Cancel the returned future to
     * abandon the local request; cancellation cannot undo server-side issuance.
     * The caller owns successful credentials and must clear them after use.
     */
    public CompletableFuture<AudioCredentials> requestAudioSession(
            String expectedRoomId, Duration timeout) {
        if (expectedRoomId == null || !expectedRoomId.matches("[0-9A-F]{6}")
                || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid audio request arguments"));
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Audio request timeout is too large"));
        }
        Connection current;
        PendingAudio pending = new PendingAudio(expectedRoomId, nanos);
        synchronized (stateLock) {
            current = connection;
            if (current == null || !current.connected || current.closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("TCP client is not connected"));
            }
            if (current.pendingAudio != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("An audio session request is already pending"));
            }
            pending.timeoutThread = Thread.ofVirtual().name("tcp-audio-timeout")
                    .unstarted(() -> {
                        try {
                            Thread.sleep(timeout);
                            finishAudio(current, pending, null,
                                    new TimeoutException("Audio session request timed out"));
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    });
            pending.future.beforeCancel = () -> {
                synchronized (stateLock) {
                    if (current.pendingAudio == pending) {
                        current.pendingAudio = null;
                    }
                    pending.timeoutThread.interrupt();
                }
            };
            current.pendingAudio = pending;
            pending.timeoutThread.start();
        }
        // This internal hook also removes requests cancelled by the caller.
        pending.future.whenComplete((credentials, failure) -> {
            synchronized (stateLock) {
                if (current.pendingAudio == pending) {
                    current.pendingAudio = null;
                }
                pending.timeoutThread.interrupt();
            }
        });
        Thread.ofVirtual().name("tcp-audio-send").start(() -> {
            try {
                synchronized (current.sendLock) {
                    synchronized (stateLock) {
                        if (current.pendingAudio != pending || current.closed
                                || connection != current || pending.future.isDone()) {
                            return;
                        }
                    }
                    if (pending.timedOut()) {
                        finishAudio(current, pending, null,
                                new TimeoutException("Audio session request timed out"));
                        return;
                    }
                    // Bound to the original connection, never a replacement.
                    send(current, "AUDIO_SESSION_REQUEST|" + pending.requestId);
                }
            } catch (RuntimeException e) {
                finishAudio(current, pending, null,
                        new IOException("Failed to send audio session request"));
            }
        });
        return pending.future;
    }

    private static boolean isAudioResponse(String message) {
        return message.equals("AUDIO_SESSION") || message.startsWith("AUDIO_SESSION|")
                || message.equals("AUDIO_SESSION_FAILED")
                || message.startsWith("AUDIO_SESSION_FAILED|");
    }

    private void routeAudioResponse(Connection current, String message) {
        int first = message.indexOf('|');
        int second = first < 0 ? -1 : message.indexOf('|', first + 1);
        if (first < 0) {
            return;
        }
        int end = second < 0 ? message.length() : second;
        if (end - first - 1 > 64) {
            return;
        }
        String requestId = message.substring(first + 1, end);
        PendingAudio pending;
        synchronized (stateLock) {
            pending = current.pendingAudio;
            if (current.closed || connection != current || pending == null
                    || pending.responseClaimed || !pending.requestId.equals(requestId)) {
                return;
            }
            pending.responseClaimed = true;
        }

        AudioCredentials credentials = null;
        Exception failure = null;
        try {
            if (message.length() > 4096) {
                throw new IllegalArgumentException();
            }
            String[] fields = message.split("\\|", -1);
            if ("AUDIO_SESSION_FAILED".equals(fields[0])) {
                if (fields.length != 3 || !isAudioFailureStatus(fields[2])) {
                    throw new IllegalArgumentException();
                }
                failure = new IOException("Audio session request rejected: " + fields[2]);
            } else {
                if (fields.length != 7 || !fields[3].equals(pending.expectedRoomId)
                        || !fields[4].matches("[A-Za-z0-9_-]{43}")
                        || !fields[5].matches("[0-9]{1,19}")
                        || !fields[6].matches("[0-9]{1,5}")) {
                    throw new IllegalArgumentException();
                }
                byte[] token = Base64.getUrlDecoder().decode(fields[4]);
                try {
                    // Reject noncanonical encodings, including nonzero pad bits.
                    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(token)
                            .equals(fields[4])) {
                        throw new IllegalArgumentException();
                    }
                    credentials = new AudioCredentials(fields[2], fields[3], token,
                            Long.parseLong(fields[5]), Integer.parseInt(fields[6]));
                } finally {
                    Arrays.fill(token, (byte) 0);
                }
            }
        } catch (IllegalArgumentException e) {
            // Never attach parser exceptions: they may contain credential fields.
            failure = new IOException("Malformed audio session response");
        }
        finishAudio(current, pending, credentials, failure);
    }

    private static boolean isAudioFailureStatus(String status) {
        return switch (status) {
            case "NOT_LOGGED_IN", "NOT_IN_ROOM", "INVALID_REQUEST", "INTERNAL_ERROR" -> true;
            default -> false;
        };
    }

    private void finishAudio(Connection current, PendingAudio pending,
                             AudioCredentials credentials, Exception failure) {
        AUDIO_COMPLETIONS.execute(() -> {
            Exception outcome = failure;
            boolean ownsRequest;
            synchronized (stateLock) {
                ownsRequest = current.pendingAudio == pending;
                if (ownsRequest) {
                    current.pendingAudio = null;
                    pending.timeoutThread.interrupt();
                    if (current.closed || connection != current) {
                        outcome = new IOException("TCP connection closed during audio session request");
                    } else if (pending.timedOut()) {
                        outcome = new TimeoutException("Audio session request timed out");
                    } else if (credentials != null && credentials.isExpired()) {
                        outcome = new IOException("Audio credentials expired before delivery");
                    }
                }
            }
            if (!ownsRequest) {
                if (credentials != null) {
                    credentials.clear();
                }
                return;
            }
            if (outcome != null) {
                if (credentials != null) {
                    credentials.clear();
                }
                pending.future.completeExceptionally(outcome);
            } else if (!pending.future.complete(credentials) && credentials != null) {
                credentials.clear();
            }
        });
    }

    /**
     * Requests credentials without consuming the legacy response/event queues.
     * No network I/O runs on the caller's thread. Cancel the returned future to
     * abandon the local request; cancellation cannot undo server-side issuance.
     * The caller owns successful credentials and must clear them after use.
     */
    public CompletableFuture<VideoCredentials> requestVideoSession(
            String expectedRoomId, Duration timeout) {
        if (expectedRoomId == null || !expectedRoomId.matches("[0-9A-F]{6}")
                || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid video request arguments"));
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Video request timeout is too large"));
        }
        Connection current;
        PendingVideo pending = new PendingVideo(expectedRoomId, nanos);
        synchronized (stateLock) {
            current = connection;
            if (current == null || !current.connected || current.closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("TCP client is not connected"));
            }
            if (current.pendingVideo != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("A video session request is already pending"));
            }
            pending.timeoutThread = Thread.ofVirtual().name("tcp-video-timeout")
                    .unstarted(() -> {
                        try {
                            Thread.sleep(timeout);
                            finishVideo(current, pending, null,
                                    new TimeoutException("Video session request timed out"));
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    });
            pending.future.beforeCancel = () -> {
                synchronized (stateLock) {
                    if (current.pendingVideo == pending) {
                        current.pendingVideo = null;
                    }
                    pending.timeoutThread.interrupt();
                }
            };
            current.pendingVideo = pending;
            pending.timeoutThread.start();
        }
        // This internal hook also removes requests cancelled by the caller.
        pending.future.whenComplete((credentials, failure) -> {
            synchronized (stateLock) {
                if (current.pendingVideo == pending) {
                    current.pendingVideo = null;
                }
                pending.timeoutThread.interrupt();
            }
        });
        Thread.ofVirtual().name("tcp-video-send").start(() -> {
            try {
                synchronized (current.sendLock) {
                    synchronized (stateLock) {
                        if (current.pendingVideo != pending || current.closed
                                || connection != current || pending.future.isDone()) {
                            return;
                        }
                    }
                    if (pending.timedOut()) {
                        finishVideo(current, pending, null,
                                new TimeoutException("Video session request timed out"));
                        return;
                    }
                    // Bound to the original connection, never a replacement.
                    send(current, "VIDEO_SESSION_REQUEST|" + pending.requestId);
                }
            } catch (RuntimeException e) {
                finishVideo(current, pending, null,
                        new IOException("Failed to send video session request"));
            }
        });
        return pending.future;
    }

    private static boolean isVideoResponse(String message) {
        return message.equals("VIDEO_SESSION") || message.startsWith("VIDEO_SESSION|")
                || message.equals("VIDEO_SESSION_FAILED")
                || message.startsWith("VIDEO_SESSION_FAILED|");
    }

    private void routeVideoResponse(Connection current, String message) {
        int first = message.indexOf('|');
        int second = first < 0 ? -1 : message.indexOf('|', first + 1);
        if (first < 0) {
            return;
        }
        int end = second < 0 ? message.length() : second;
        if (end - first - 1 > 64) {
            return;
        }
        String requestId = message.substring(first + 1, end);
        PendingVideo pending;
        synchronized (stateLock) {
            pending = current.pendingVideo;
            if (current.closed || connection != current || pending == null
                    || pending.responseClaimed || !pending.requestId.equals(requestId)) {
                return;
            }
            pending.responseClaimed = true;
        }

        VideoCredentials credentials = null;
        Exception failure = null;
        try {
            if (message.length() > 4096) {
                throw new IllegalArgumentException();
            }
            String[] fields = message.split("\\|", -1);
            if ("VIDEO_SESSION_FAILED".equals(fields[0])) {
                if (fields.length != 3 || !isVideoFailureStatus(fields[2])) {
                    throw new IllegalArgumentException();
                }
                failure = new IOException("Video session request rejected: " + fields[2]);
            } else {
                if (fields.length != 7 || !fields[3].equals(pending.expectedRoomId)
                        || !fields[4].matches("[A-Za-z0-9_-]{43}")
                        || !fields[5].matches("[0-9]{1,19}")
                        || !fields[6].matches("[0-9]{1,5}")) {
                    throw new IllegalArgumentException();
                }
                byte[] token = Base64.getUrlDecoder().decode(fields[4]);
                try {
                    // Reject noncanonical encodings, including nonzero pad bits.
                    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(token)
                            .equals(fields[4])) {
                        throw new IllegalArgumentException();
                    }
                    credentials = new VideoCredentials(fields[2], fields[3], token,
                            Long.parseLong(fields[5]), Integer.parseInt(fields[6]));
                } finally {
                    Arrays.fill(token, (byte) 0);
                }
            }
        } catch (IllegalArgumentException e) {
            // Never attach parser exceptions: they may contain credential fields.
            failure = new IOException("Malformed video session response");
        }
        finishVideo(current, pending, credentials, failure);
    }

    private static boolean isVideoFailureStatus(String status) {
        return switch (status) {
            case "NOT_LOGGED_IN", "NOT_IN_ROOM", "INVALID_REQUEST", "INTERNAL_ERROR" -> true;
            default -> false;
        };
    }

    private void finishVideo(Connection current, PendingVideo pending,
                             VideoCredentials credentials, Exception failure) {
        AUDIO_COMPLETIONS.execute(() -> {
            Exception outcome = failure;
            boolean ownsRequest;
            synchronized (stateLock) {
                ownsRequest = current.pendingVideo == pending;
                if (ownsRequest) {
                    current.pendingVideo = null;
                    pending.timeoutThread.interrupt();
                    if (current.closed || connection != current) {
                        outcome = new IOException("TCP connection closed during video session request");
                    } else if (pending.timedOut()) {
                        outcome = new TimeoutException("Video session request timed out");
                    } else if (credentials != null && credentials.isExpired()) {
                        outcome = new IOException("Video credentials expired before delivery");
                    }
                }
            }
            if (!ownsRequest) {
                if (credentials != null) {
                    credentials.clear();
                }
                return;
            }
            if (outcome != null) {
                if (credentials != null) {
                    credentials.clear();
                }
                pending.future.completeExceptionally(outcome);
            } else if (!pending.future.complete(credentials) && credentials != null) {
                credentials.clear();
            }
        });
    }

    /**
     * Throws IllegalStateException if disconnected or a write fails.
     */
    public void send(String message) {
        Connection current;
        synchronized (stateLock) {
            current = requireConnection();
        }
        send(current, message);
    }

    private void send(Connection current, String message) {
        synchronized (current.sendLock) {
            synchronized (stateLock) {
                if (current.closed || connection != current
                        || current.socket.isClosed()
                        || current.socket.isOutputShutdown()) {
                    throw disconnected(current);
                }
            }
            current.writer.println(message);
            if (current.writer.checkError()) {
                IOException failure = new IOException("Failed to send TCP message");
                closeConnection(current, failure, false);
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    /**
     * Waits for a response from the current connection. Queued responses are
     * drained after remote EOF; then IllegalStateException reports disconnect.
     * Concurrent callers must coordinate requests because the protocol has no IDs.
     */
    public String receive() throws InterruptedException {
        synchronized (stateLock) {
            Connection current = requireConnection();
            while (current.responses.isEmpty()) {
                if (current.closed || connection != current) {
                    throw disconnected(current);
                }
                stateLock.wait();
            }
            return current.responses.removeFirst();
        }
    }

    public void setEventListener(Consumer<String> listener) {
        synchronized (stateLock) {
            eventListener = listener;
            stateLock.notifyAll();
        }
    }

    public void clearEventListener() {
        setEventListener(null);
    }

    public void disconnect() {
        Connection current;
        PendingAudio pending = null;
        synchronized (stateLock) {
            current = connection;
            if (current != null) {
                pending = closeState(current, null, true);
            }
        }
        failDetachedAudio(pending);
        notifyDisconnected(current);
        closeSocket(current);
    }

    private Connection requireConnection() {
        if (connection == null || !connection.connected) {
            throw new IllegalStateException("TCP client is not connected");
        }
        return connection;
    }

    private IllegalStateException disconnected(Connection current) {
        return new IllegalStateException("TCP connection is closed", current.failure);
    }

    private void closeConnection(Connection current, IOException failure,
                                 boolean discardResponses) {
        PendingAudio pending;
        synchronized (stateLock) {
            pending = closeState(current, failure, discardResponses);
        }
        failDetachedAudio(pending);
        notifyDisconnected(current);
        closeSocket(current);
    }

    private PendingAudio closeState(Connection current, IOException failure,
                                    boolean discardResponses) {
        if (!current.closed) {
            current.closed = true;
            current.failure = failure;
        }
        PendingVideo video = current.pendingVideo;
        current.pendingVideo = null;
        if (video != null) {
            video.timeoutThread.interrupt();
            AUDIO_COMPLETIONS.execute(() -> video.future.completeExceptionally(
                    new IOException("TCP connection closed during video session request")));
        }
        PendingAudio pending = current.pendingAudio;
        current.pendingAudio = null;
        if (pending != null) {
            pending.timeoutThread.interrupt();
        }
        current.events.clear();
        if (discardResponses) {
            current.responses.clear();
        }
        stateLock.notifyAll();
        return pending;
    }

    private static void failDetachedAudio(PendingAudio pending) {
        if (pending != null) {
            AUDIO_COMPLETIONS.execute(() -> pending.future.completeExceptionally(
                    new IOException("TCP connection closed during audio session request")));
        }
    }

    private void notifyDisconnected(Connection current) {
        List<ConnectionSubscription> listeners;
        synchronized (stateLock) {
            if (current == null || !current.connected || !current.closed
                    || current.disconnectNotified) {
                return;
            }
            current.disconnectNotified = true;
            listeners = new ArrayList<>(current.lifecycleListeners);
            current.lifecycleListeners.clear();
            for (ConnectionSubscription subscription : listeners) {
                subscription.deliveryQueued = true;
            }
        }
        for (ConnectionSubscription subscription : listeners) {
            dispatchDisconnect(subscription);
        }
    }

    private void dispatchDisconnect(ConnectionSubscription subscription) {
        AUDIO_COMPLETIONS.execute(() -> {
            synchronized (stateLock) {
                if (subscription.removed || !subscription.deliveryQueued) {
                    return;
                }
                subscription.deliveryQueued = false;
            }
            try {
                subscription.listener.onDisconnected(subscription.observed.id);
            } catch (RuntimeException ignored) {
                // A listener must not interrupt socket cleanup or other listeners.
            }
        });
    }

    private void closeSocket(Connection current) {
        if (current != null) {
            try {
                // Closing the socket also closes both underlying streams and
                // unblocks I/O without waiting for reader/writer monitor locks.
                current.socket.close();
            } catch (IOException ignored) {
                // State is already closed and all waiting receivers were notified.
            }
        }
    }
}
