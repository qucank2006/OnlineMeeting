package com.example.onlinemeeting.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public class TCPClient {

    private final Object stateLock = new Object();
    // All connection state, queues, and listener access use stateLock.
    private Connection connection;
    private Consumer<String> eventListener;

    private static final class Connection {
        final Socket socket = new Socket();
        final Object sendLock = new Object();
        final Deque<String> responses = new ArrayDeque<>();
        final Deque<String> events = new ArrayDeque<>();
        BufferedReader reader;
        PrintWriter writer;
        boolean connected;
        boolean closed;
        IOException failure;
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

        Connection next = new Connection();
        Connection previous;
        synchronized (stateLock) {
            previous = connection;
            if (previous != null) {
                closeState(previous, null, true);
            }
            connection = next;
        }
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

    private void listen(Connection current) {
        IOException failure = null;
        try {
            String message;
            while ((message = current.reader.readLine()) != null) {
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
     * Throws IllegalStateException if disconnected or a write fails.
     */
    public void send(String message) {
        Connection current;
        synchronized (stateLock) {
            current = requireConnection();
        }
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
        synchronized (stateLock) {
            current = connection;
            if (current != null) {
                closeState(current, null, true);
            }
        }
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
        synchronized (stateLock) {
            closeState(current, failure, discardResponses);
        }
        closeSocket(current);
    }

    private void closeState(Connection current, IOException failure,
                            boolean discardResponses) {
        if (!current.closed) {
            current.closed = true;
            current.failure = failure;
        }
        current.events.clear();
        if (discardResponses) {
            current.responses.clear();
        }
        stateLock.notifyAll();
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

