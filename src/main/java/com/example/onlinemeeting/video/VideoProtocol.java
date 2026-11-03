package com.example.onlinemeeting.video;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Version 1: length includes type + payload; all integer fields are big-endian. */
public final class VideoProtocol {
    public static final int VERSION = 1, MAX_JPEG = 128 * 1024;
    public static final int AUTH = 1, AUTH_OK = 2, VIDEO_FRAME = 3,
            CAMERA_OFF = 4, CAMERA_ON = 5, HEARTBEAT = 6, ERROR = 7, PEER_LEFT = 8;
    public record Message(int type, byte[] payload) { }
    public record Identity(long userId, String username) { }
    private VideoProtocol() { }

    public static Message read(DataInputStream input, boolean serverSide) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_JPEG + 9) throw new IOException("Invalid video message length");
        int type = input.readUnsignedByte();
        int size = length - 1;
        boolean valid = switch (type) {
            case AUTH -> serverSide && size == 49;
            case AUTH_OK -> !serverSide && size == 8;
            case VIDEO_FRAME -> size >= (serverSide ? 4 : 12)
                    && size <= MAX_JPEG + (serverSide ? 0 : 8);
            case CAMERA_OFF, CAMERA_ON -> serverSide ? size == 0 : size >= 11 && size <= 210;
            case HEARTBEAT -> size == 0;
            case ERROR -> !serverSide && size >= 1 && size <= 64;
            case PEER_LEFT -> !serverSide && size == 8;
            default -> false;
        };
        if (!valid) throw new IOException("Invalid video message type or payload");
        byte[] payload = new byte[size];
        input.readFully(payload);
        return new Message(type, payload);
    }

    public static void write(DataOutputStream output, Message message) throws IOException {
        output.writeInt(1 + message.payload().length);
        output.writeByte(message.type());
        output.write(message.payload());
        output.flush();
    }

    // Identity accompanies camera state, never supplied by the video client.
    public static byte[] identity(long id, String username) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(id);
            byte[] name = username.getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length > 200) throw new IOException("Invalid video identity");
            output.writeShort(name.length);
            output.write(name);
        }
        return bytes.toByteArray();
    }

    public static Identity identity(byte[] payload) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        long id = input.readLong();
        int length = input.readUnsignedShort();
        if (id <= 0 || length < 1 || length > 200 || input.available() != length)
            throw new IOException("Invalid video identity");
        byte[] name = new byte[length];
        input.readFully(name);
        return new Identity(id, new String(name, StandardCharsets.UTF_8));
    }
}
