package com.example.onlinemeeting.client;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class TCPClient {

    private Socket socket;

    private BufferedReader reader;
    private PrintWriter writer;

    private Thread listenerThread;

    private volatile boolean running;

    private Consumer<String> eventListener;

    /*
     * Response cho các request đồng bộ như
     * LOGIN, CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM.
     */
    private final BlockingQueue<String> responses =
            new LinkedBlockingQueue<>();

    private final BlockingQueue<String> pendingEvents =
            new LinkedBlockingQueue<>();


    public void connect(
            String host,
            int port
    ) throws IOException {

        socket =
                new Socket(host, port);

        reader =
                new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream()
                        )
                );

        writer =
                new PrintWriter(
                        socket.getOutputStream(),
                        true
                );

        running = true;

        listenerThread =
                new Thread(this::listen);

        listenerThread.setDaemon(true);

        listenerThread.start();
    }


    private void listen() {

        try {

            String message;

            while (
                    running &&
                            (message = reader.readLine()) != null
            ) {

                if (isEvent(message)) {

                    Consumer<String> listener = eventListener;

                    if (listener != null) {
                        listener.accept(message);
                    } else {
                        pendingEvents.offer(message);
                    }

                } else {
                    responses.offer(message);
                }
            }

        } catch (IOException e) {

            if (running) {
                e.printStackTrace();
            }
        }
    }


    private boolean isEvent(String message) {

        return message.startsWith("MEMBERS|")
                || message.startsWith("USER_JOINED|")
                || message.startsWith("USER_LEFT|")
                || message.startsWith("CHAT|");
    }


    public void send(String message) {

        writer.println(message);
    }


    public String receive()
            throws InterruptedException {

        return responses.take();
    }


    public void setEventListener(
            Consumer<String> listener
    ) {
        this.eventListener = listener;

        String event;

        while ((event = pendingEvents.poll()) != null) {
            listener.accept(event);
        }
    }

    public void clearEventListener() {
        this.eventListener = null;
    }


    public void disconnect() {

        running = false;

        try {

            if (socket != null) {
                socket.close();
            }

        } catch (IOException ignored) {
        }
    }
}