package org.openmuc.j62056.a1700;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.openmuc.j62056.a1700.A1700Protocol.*;

/** Stateful native-register driver for the captured A1700 configuration; no runtime dependency on PMU logs. */
public final class A1700Client {
    public interface Observer {
        void wire(boolean tx, byte[] data, boolean partial) throws IOException;
        void event(String message) throws IOException;
    }
    public static final Observer QUIET = new Observer() {
        public void wire(boolean tx, byte[] bytes, boolean partial) { }
        public void event(String message) { }
    };
    public static final String[] CURRENT_REQUESTS = {
        "667001:20", "668001:08", "507001:40", "507002:10", "508001:40", "508002:40",
        "508003:40", "508004:40", "516001:20", "510001:40", "510002:40", "510003:40",
        "510004:40", "510005:20", "509001:40", "509002:08", "511001:40", "511002:38"
    };
    public static final Map<String, String> SELECTORS;
    static {
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("current", "1A2A4A00"); selectors.put("voltage", "1B2B4B00");
        selectors.put("active_power", "1C2C4C0C"); selectors.put("reactive_power", "1D2D4D0D");
        selectors.put("apparent_power", "1E2E4E0E"); selectors.put("power_factor", "13234303");
        selectors.put("frequency", "18284800"); selectors.put("phase_angle", "19294900");
        selectors.put("phase_rotation", "07000000");
        SELECTORS = java.util.Collections.unmodifiableMap(selectors);
    }

    public static final class Config {
        public String address = "005";
        public String expectedSerial = "21000568";
        public int timeoutMs = 8000;
        public int delayMs = 300;
        public int signOnAttempts = 3;
        public int pollTimeoutMs = 15000;
        public int maxProfileBlocks = 4095;
        public String profileSelector = "0100";
        public void validate() {
            if (address == null || !address.matches("[A-Za-z0-9]{0,32}")) throw new IllegalArgumentException("Invalid outstation address");
            if (expectedSerial == null || expectedSerial.isEmpty()) throw new IllegalArgumentException("Expected meter serial is required");
            if (timeoutMs < 1 || timeoutMs > 600000 || delayMs < 0 || delayMs > 60000
                    || pollTimeoutMs < 1 || pollTimeoutMs > 600000 || signOnAttempts < 1 || signOnAttempts > 10
                    || maxProfileBlocks < 1 || maxProfileBlocks > 4095) throw new IllegalArgumentException("Invalid timeout/delay/limit");
            if (profileSelector == null || !profileSelector.matches("[0-9A-Fa-f]{4}")) throw new IllegalArgumentException("Profile selector needs 4 hex digits");
        }
    }
    public static final class Capture {
        public final String group;
        public final Map<String, byte[]> blocks = new LinkedHashMap<>();
        public final Map<String, Object> metadata = new LinkedHashMap<>();
        public boolean complete;
        public String error;
        public Capture(String group) { this.group = group; }
        public Map<String, Object> rawHex() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> b : blocks.entrySet()) out.put(b.getKey(), hex(b.getValue()));
            return out;
        }
    }
    private final A1700Transport transport;
    private final Config config;
    private final Observer observer;
    private Capture capture;
    private String stage;
    private boolean sessionStarted;
    private int receivedBytes;

    public A1700Client(A1700Transport transport, Config config, Observer observer) {
        config.validate(); this.transport = transport; this.config = config;
        this.observer = observer == null ? QUIET : observer;
    }

    /** Starts a fresh authenticated session. A failed capture retains validated blocks and an explicit error. */
    public synchronized Capture capture(String group, byte[] key) {
        if (!Arrays.asList("current", "instantaneous", "historical", "load-profile", "profile-probe").contains(group)) {
            throw new IllegalArgumentException("Unknown group: " + group);
        }
        if (key == null || key.length != 8) throw new IllegalArgumentException("Eight-byte key required");
        capture = new Capture(group);
        capture.metadata.put("startedAt", OffsetDateTime.now().toString());
        capture.metadata.put("port", transport.name()); capture.metadata.put("address", config.address);
        sessionStarted = false;
        try {
            login(key);
            if (group.equals("current")) current();
            else if (group.equals("instantaneous")) instantaneous();
            else if (group.equals("historical")) historical();
            else profile(group.equals("profile-probe"));
            capture.complete = true;
        } catch (IOException e) {
            capture.error = stage + ": " + message(e);
        } finally {
            if (sessionStarted) {
                try { stage = "logoff"; send(frame("B0", null)); }
                catch (IOException e) {
                    capture.metadata.put("logoffError", message(e));
                    if (capture.error == null) capture.error = "logoff: " + message(e);
                }
            }
            capture.metadata.put("finishedAt", OffsetDateTime.now().toString());
            capture.metadata.put("dataTransferComplete", capture.complete);
        }
        return capture;
    }

    private void login(byte[] key) throws IOException {
        stage = "identification";
        byte[] id = null;
        for (int attempt = 1; attempt <= config.signOnAttempts; attempt++) {
            observer.event("Sign-on " + attempt + "/" + config.signOnAttempts);
            byte[] signOn = ("/?" + config.address + "!\r\n").getBytes(StandardCharsets.US_ASCII);
            send(signOn);
            try {
                long responseDeadline = deadline(config.timeoutMs);
                id = receive(true, responseDeadline);
                if (Arrays.equals(id, signOn)) {
                    observer.event("Ignoring exact sign-on echo; waiting for meter identification");
                    id = receive(true, responseDeadline);
                }
                break;
            }
            catch (SocketTimeoutException e) {
                // Retrying a partial line could mix frames. Only retry an entirely silent request.
                if (receivedBytes != 0 || attempt == config.signOnAttempts) throw e;
            }
        }
        String identity = new String(id, StandardCharsets.US_ASCII).trim();
        if (!identity.startsWith("/GEC5")) throw new IOException("Unexpected identification: " + identity);
        sessionStarted = true;
        capture.metadata.put("identification", identity);
        stage = "P0 challenge";
        exchangeDelay(); send(new byte[] {6, '0', '5', '1', '\r', '\n'});
        byte[] challenge = payload(receive(false, deadline(config.timeoutMs)), "P0", 8);
        stage = "level-0 authentication";
        exchangeDelay(); send(frame("P2", "(0000000000000000)"));
        requireAck(receive(false, deadline(config.timeoutMs)));
        byte[] serialBytes = read("798001", 16, "798001");
        byte[] modelBytes = read("795001", 8, "795001");
        String serialRaw = new String(serialBytes, StandardCharsets.US_ASCII);
        String serial = serialRaw.replaceFirst("^-+", "").replaceFirst("-+$", "");
        capture.metadata.put("serialRaw", serialRaw); capture.metadata.put("meterId", serial);
        capture.metadata.put("modelRaw", new String(modelBytes, StandardCharsets.US_ASCII));
        capture.metadata.put("model", new String(modelBytes, StandardCharsets.US_ASCII).replaceFirst("-+$", ""));
        stage = "meter identity validation";
        if (!config.expectedSerial.equals(serial)) throw new IOException("Credential is configured for "
                + config.expectedSerial + ", connected meter is " + serial);
        stage = "level-1 authentication";
        byte[] response = passwordResponse(key, challenge);
        try {
            exchangeDelay(); send(frame("P2", "(" + hex(response) + ")"));
            requireAck(receive(false, deadline(config.timeoutMs)));
        } finally { Arrays.fill(response, (byte) 0); Arrays.fill(challenge, (byte) 0); }
        observer.event("Authenticated meter " + serial + "; reading " + capture.group);
    }

    private void current() throws IOException {
        for (String request : CURRENT_REQUESTS) {
            String[] parts = request.split(":"); read(parts[0], Integer.parseInt(parts[1], 16), parts[0]);
        }
    }
    private void historical() throws IOException {
        capture.metadata.put("historicalSet", 1);
        read("667001", 32, "667001"); read("668001", 8, "668001");
        for (int i = 1; i <= 14; i++) {
            String address = "543" + String.format(Locale.ROOT, "%03x", i);
            read(address, i == 14 ? 31 : 64, address.toUpperCase(Locale.ROOT));
        }
        read("548001", 64, "548001");
    }
    private void instantaneous() throws IOException {
        for (Map.Entry<String, String> entry : SELECTORS.entrySet()) {
            String name = entry.getKey(); String selector = entry.getValue();
            select("605001", selector);
            long readyDeadline = deadline(config.pollTimeoutMs);
            int poll = 0;
            while (true) {
                if (System.nanoTime() >= readyDeadline) throw new SocketTimeoutException("Instantaneous " + name + " not ready");
                byte[] status = read("605001", 4, "605001@" + name + "@" + (++poll), readyDeadline);
                byte[] expected = unhex(selector); boolean ready = true;
                for (int i = 0; i < 4; i++) {
                    if ((status[i] & 127) != (expected[i] & 255)) throw new IOException("Status selector mismatch for " + name);
                    ready &= (status[i] & 128) != 0;
                }
                if (ready) break;
            }
            read("606001", 28, "606001@" + name);
            capture.metadata.put("polls_" + name, poll);
        }
    }
    private void profile(boolean probe) throws IOException {
        capture.metadata.put("selector", config.profileSelector.toUpperCase(Locale.ROOT));
        select("551001", config.profileSelector.toUpperCase(Locale.ROOT));
        byte[] countBytes = read("551001", 2, "551001");
        int count = (countBytes[0] & 255) * 256 + (countBytes[1] & 255);
        capture.metadata.put("expectedBlocks", count); capture.metadata.put("receivedBlocks", 0);
        if (probe) {
            capture.metadata.put("probeOnly", true);
            if (count > 0) {
                read("550001", 64, "550001");
                capture.metadata.put("receivedBlocks", 1);
            }
            return;
        }
        if (count > config.maxProfileBlocks) throw new IOException("Profile count " + count
                + " exceeds configured limit " + config.maxProfileBlocks + "; no blocks discarded as success");
        for (int i = 1; i <= count; i++) {
            String address = "550" + String.format(Locale.ROOT, "%03X", i);
            read(address, 64, address);
            capture.metadata.put("receivedBlocks", i);
            observer.event("Profile block " + i + "/" + count);
        }
    }
    private void select(String register, String selector) throws IOException {
        stage = "W1 " + register + "(" + selector + ")";
        exchangeDelay(); send(frame("W1", register + "(" + selector + ")"));
        requireAck(receive(false, deadline(config.timeoutMs)));
    }
    private byte[] read(String address, int length, String key) throws IOException {
        return read(address, length, key, Long.MAX_VALUE);
    }
    private byte[] read(String address, int length, String key, long outerDeadline) throws IOException {
        stage = "R1 " + address + String.format(Locale.ROOT, "(%02X)", length);
        exchangeDelay();
        if (System.nanoTime() >= outerDeadline) throw new SocketTimeoutException("Polling deadline exceeded");
        send(frame("R1", address + String.format(Locale.ROOT, "(%02X)", length)));
        byte[] value = payload(receive(false, Math.min(deadline(config.timeoutMs), outerDeadline)), null, length);
        capture.blocks.put(key, value);
        observer.event(stage + " received " + value.length + " bytes");
        return value;
    }
    private byte[] receive(boolean line, long deadline) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); receivedBytes = 0;
        boolean logged = false;
        try {
            boolean endSeen = false;
            while (data.size() < 1024) {
                int value = transport.readByte(deadline);
                if (value < 0 || value > 255) throw new IOException("Invalid serial byte / EOF");
                data.write(value); receivedBytes = data.size();
                if (line) {
                    byte[] bytes = data.toByteArray();
                    if (bytes.length >= 2 && bytes[bytes.length - 2] == 13 && value == 10) {
                        observer.wire(false, bytes, false); logged = true; return bytes;
                    }
                } else {
                    if (data.size() == 1) {
                        if (value == 6 || value == 21) { observer.wire(false, data.toByteArray(), false); return data.toByteArray(); }
                        if (value != 1 && value != 2) throw new IOException("Unexpected frame start: " + value);
                    } else if (endSeen) {
                        byte[] bytes = data.toByteArray();
                        // Complete but invalid frames remain in the original wire log.
                        observer.wire(false, bytes, false); logged = true; checkFrame(bytes); return bytes;
                    }
                    endSeen = value == 3;
                }
            }
            throw new IOException("A1700 frame exceeds 1024 bytes");
        } catch (IOException e) {
            try { if (!logged) observer.wire(false, data.toByteArray(), true); }
            catch (IOException logError) { e.addSuppressed(logError); }
            throw e;
        }
    }
    private void send(byte[] data) throws IOException { transport.send(data); observer.wire(true, data, false); }
    private void exchangeDelay() throws IOException {
        try { Thread.sleep(config.delayMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Exchange interrupted"); }
    }
    private static void requireAck(byte[] response) throws IOException {
        if (response.length != 1 || response[0] != 6) throw new IOException(response.length == 1 && response[0] == 21
                ? "Meter returned NAK" : "Expected ACK");
    }
    private static long deadline(int ms) { return System.nanoTime() + ms * 1000000L; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
