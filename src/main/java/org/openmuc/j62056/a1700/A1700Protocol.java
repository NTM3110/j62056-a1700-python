package org.openmuc.j62056.a1700;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Wire helpers shared by the live driver and its tests. See doc/a1700-response-parsing-logic.md. */
public final class A1700Protocol {
    private A1700Protocol() { }

    public static byte[] frame(String command, String payload) {
        byte[] body = (command + (payload == null ? "" : "\002" + payload) + "\003")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        int check = 0;
        for (byte b : body) { out.write(b); check ^= b & 255; }
        out.write(check);
        return out.toByteArray();
    }

    public static void checkFrame(byte[] frame) throws IOException {
        if (frame.length < 3 || (frame[0] != 1 && frame[0] != 2) || frame[frame.length - 2] != 3) {
            throw new IOException("Invalid A1700 frame boundary");
        }
        int check = 0;
        for (int i = 1; i < frame.length - 1; i++) {
            if (frame[i] == 3 && i != frame.length - 2) throw new IOException("Early ETX in frame");
            check ^= frame[i] & 255;
        }
        if (check != (frame[frame.length - 1] & 255)) throw new IOException("A1700 BCC mismatch");
    }

    public static byte[] payload(byte[] frame, String command, int length) throws IOException {
        if (frame.length == 1 && frame[0] == 0x15) throw new IOException("Meter returned NAK");
        checkFrame(frame);
        String text = new String(frame, StandardCharsets.US_ASCII);
        String prefix = command == null ? "\002(" : "\001" + command + "\002(";
        if (!text.startsWith(prefix) || frame[frame.length - 3] != ')') {
            throw new IOException("Unexpected A1700 response command/payload");
        }
        String hex = text.substring(prefix.length(), frame.length - 3);
        if (hex.length() != length * 2) throw new IOException("Payload length: expected " + length
                + " bytes, received " + hex.length() + " hex characters");
        try { return unhex(hex); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid hexadecimal payload", e); }
    }

    /** Existing A1700 authentication transformation; the key never comes from replayed log frames. */
    public static byte[] passwordResponse(byte[] key, byte[] challenge) {
        if (key.length != 8 || challenge.length != 8) throw new IllegalArgumentException("Eight bytes required");
        byte[] mixed = new byte[8];
        for (int i = 0; i < 8; i++) mixed[i] = (byte) (key[i] ^ challenge[i]);
        int previous = mixed[7] & 255;
        for (int i = 0; i < 8; i++) { previous = ((mixed[i] & 255) + previous) & 255; mixed[i] = (byte) previous; }
        return mixed;
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        return out.toString();
    }

    public static byte[] unhex(String value) {
        if (!value.matches("(?:[0-9a-fA-F]{2})*")) throw new IllegalArgumentException("Invalid hex string");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    public static String render(byte[] bytes) {
        String[] names = ("NUL SOH STX ETX EOT ENQ ACK BEL BS HT LF VT FF CR SO SI DLE DC1 DC2 DC3 DC4 "
                + "NAK SYN ETB CAN EM SUB ESC FS GS RS US").split(" ");
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 255;
            if (c < 32) out.append('<').append(names[c]).append('>');
            else if (c == 127) out.append("<DEL>");
            else if (c < 127) out.append((char) c);
            else out.append(String.format(java.util.Locale.ROOT, "<0x%02X>", c));
        }
        return out.toString();
    }
}
