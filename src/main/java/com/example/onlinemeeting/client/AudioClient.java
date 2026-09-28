package com.example.onlinemeeting.client;

import javax.sound.sampled.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class AudioClient {

    private static final int SERVER_PORT = 6000;
    private static final int BUFFER_SIZE = 1024;

    private final String serverHost;
    private final String roomId;

    private DatagramSocket socket;

    private TargetDataLine microphone;
    private SourceDataLine speaker;

    private volatile boolean running;
    private volatile boolean muted;

    public AudioClient(
            String serverHost,
            String roomId
    ) {
        this.serverHost = serverHost;
        this.roomId = roomId;
    }

    private AudioFormat getAudioFormat() {

        return new AudioFormat(
                16000.0f,
                16,
                1,
                true,
                false
        );
    }

    public void start() throws Exception {

        AudioFormat format = getAudioFormat();
        DataLine.Info microphoneInfo = new DataLine.Info(TargetDataLine.class, format);
        microphone = (TargetDataLine) AudioSystem.getLine(microphoneInfo);
        microphone.open(format);
        microphone.start();


        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
        speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speaker.open(format);
        speaker.start();

        socket = new DatagramSocket();

        running = true;
        muted = false;

        Thread sendThread = new Thread(this::sendAudio);

        Thread receiveThread = new Thread(this::receiveAudio);

        sendThread.setDaemon(true);
        receiveThread.setDaemon(true);

        sendThread.start();
        receiveThread.start();

        System.out.println("Audio started - Room: " + roomId);
    }

    private void sendAudio() {
        try {
            byte[] audioBuffer = new byte[BUFFER_SIZE];
            InetAddress serverAddress = InetAddress.getByName(serverHost);

            byte[] roomBytes = roomId.getBytes(StandardCharsets.UTF_8);
            while (running) {
                int bytesRead = microphone.read(
                        audioBuffer,
                        0,
                        audioBuffer.length
                );
                if (muted) {
                    continue;
                }

                ByteBuffer packetBuffer = ByteBuffer.allocate(1 + roomBytes.length + bytesRead);

                packetBuffer.put((byte) roomBytes.length);
                packetBuffer.put(roomBytes);
                packetBuffer.put(audioBuffer, 0, bytesRead);

                byte[] data = packetBuffer.array();
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        serverAddress,
                        SERVER_PORT);

                socket.send(packet);
            }
        } catch (Exception e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    private void receiveAudio() {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];

            while (running) {
                DatagramPacket packet = new DatagramPacket(
                        buffer,
                        buffer.length);

                socket.receive(packet);
                speaker.write(packet.getData(), 0, packet.getLength());
            }

        } catch (Exception e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    public void mute() {
        muted = true;
    }

    public void unmute() {
        muted = false;
    }

    public boolean isMuted() {
        return muted;
    }

    public void stop() {

        running = false;

        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }

        if (speaker != null) {
            speaker.stop();
            speaker.close();
        }

        if (socket != null) {
            socket.close();
        }
    }
}