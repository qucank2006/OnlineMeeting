package com.example.onlinemeeting.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AudioServer {

    private static final int AUDIO_PORT = 6000;
    private static final int BUFFER_SIZE = 2048;

    // roomId -> danh sách địa chỉ UDP
    private final Map<String, Set<SocketAddress>> roomClients =
            new ConcurrentHashMap<>();

    public void start() {

        try (DatagramSocket socket = new DatagramSocket(AUDIO_PORT)) {

            System.out.println(
                    "Audio Server đang chạy tại port " + AUDIO_PORT
            );

            while (true) {

                byte[] buffer = new byte[BUFFER_SIZE];

                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);

                handlePacket(socket, packet);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePacket(
            DatagramSocket socket,
            DatagramPacket packet
    ) {

        try {

            ByteBuffer buffer = ByteBuffer.wrap(
                    packet.getData(),
                    0,
                    packet.getLength()
            );

            // Byte đầu tiên chứa độ dài Room ID
            int roomIdLength = Byte.toUnsignedInt(
                    buffer.get()
            );

            if (roomIdLength <= 0 ||
                    roomIdLength > buffer.remaining()) {
                return;
            }

            byte[] roomBytes =
                    new byte[roomIdLength];

            buffer.get(roomBytes);

            String roomId = new String(
                    roomBytes,
                    StandardCharsets.UTF_8
            );

            // Kiểm tra room có thực sự tồn tại
            if (RoomManager.getRoom(roomId) == null) {
                return;
            }

            InetSocketAddress sender =
                    new InetSocketAddress(
                            packet.getAddress(),
                            packet.getPort()
                    );

            roomClients
                    .computeIfAbsent(
                            roomId,
                            key -> ConcurrentHashMap.newKeySet()
                    )
                    .add(sender);

            int audioLength = buffer.remaining();

            if (audioLength <= 0) {
                return;
            }

            byte[] audioData =
                    new byte[audioLength];

            buffer.get(audioData);

            relayAudio(
                    socket,
                    roomId,
                    sender,
                    audioData
            );

        } catch (Exception ignored) {
        }
    }

    private void relayAudio(
            DatagramSocket socket,
            String roomId,
            SocketAddress sender,
            byte[] audioData
    ) {

        Set<SocketAddress> clients =
                roomClients.get(roomId);

        if (clients == null) {
            return;
        }

        for (SocketAddress client : clients) {

            if (client.equals(sender)) {
                continue;
            }

            InetSocketAddress target =
                    (InetSocketAddress) client;

            try {

                DatagramPacket packet =
                        new DatagramPacket(
                                audioData,
                                audioData.length,
                                target.getAddress(),
                                target.getPort()
                        );

                socket.send(packet);

            } catch (Exception ignored) {
            }
        }
    }
}