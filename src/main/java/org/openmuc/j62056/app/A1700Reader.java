package org.openmuc.j62056.app;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openmuc.j62056.Iec21Port;
import org.openmuc.j62056.a1700.A1700Client;
import org.openmuc.j62056.a1700.A1700Decoder;
import org.openmuc.j62056.a1700.A1700Protocol;
import org.openmuc.j62056.a1700.A1700Data;

/** Linux live CLI using the existing Iec21Port device-stream transport, avoiding incompatible RXTX native calls. */
public final class A1700Reader {
    private static final byte[] DEFAULT_LEVEL_3_PASSWORD = "FEDC0003".getBytes(StandardCharsets.US_ASCII);
    private A1700Reader() { }
    public static void main(String[] args) {
        int code;
        try { code = run(args); }
        catch (IOException | IllegalArgumentException e) { System.err.println("A1700: " + e.getMessage()); code = 1; }
        if (code != 0) System.exit(code);
    }
    private static int run(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> allowed = Arrays.asList("--port", "--address", "--serial", "--key", "--group", "--output",
                "--timeout-ms", "--delay-ms", "--signon-attempts", "--poll-timeout-ms", "--profile-selector", "--max-profile-blocks");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help") || args[i].equals("-h")) { help(); return 0; }
            if (!allowed.contains(args[i]) || i + 1 == args.length) throw new IllegalArgumentException("Unknown/missing option: " + args[i] + "; use --help");
            String key = args[i++];
            if (options.put(key, args[i]) != null) throw new IllegalArgumentException("Duplicate option " + key);
        }
        A1700Client.Config config = new A1700Client.Config();
        config.address = options.getOrDefault("--address", "005");
        config.expectedSerial = options.getOrDefault("--serial", "21000568");
        config.timeoutMs = integer(options, "--timeout-ms", 8000);
        config.delayMs = integer(options, "--delay-ms", 300);
        config.signOnAttempts = integer(options, "--signon-attempts", 3);
        config.pollTimeoutMs = integer(options, "--poll-timeout-ms", 15000);
        config.maxProfileBlocks = integer(options, "--max-profile-blocks", 4095);
        config.profileSelector = options.getOrDefault("--profile-selector", "0100"); config.validate();
        String group = options.getOrDefault("--group", "current");
        List<String> groups = group.equals("all") ? Arrays.asList("current", "instantaneous", "historical", "load-profile") : Arrays.asList(group);
        for (String name : groups) if (!Arrays.asList("current", "instantaneous", "historical", "load-profile", "profile-probe").contains(name)) {
            throw new IllegalArgumentException("Unknown group " + name);
        }
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) throw new IOException("Live device-stream CLI currently supports Linux (stty -F)");
        String device = options.getOrDefault("--port", "/dev/ttyUSB0");
        byte[] key;
        if (options.containsKey("--key")) {
            Path keyPath = Paths.get(options.get("--key"));
            if (Files.size(keyPath) != 8) throw new IOException("Key file must contain exactly eight binary bytes");
            key = Files.readAllBytes(keyPath);
        }
        else {
            key = Arrays.copyOf(DEFAULT_LEVEL_3_PASSWORD, DEFAULT_LEVEL_3_PASSWORD.length);
        }
        try { return collect(options, config, groups, device, key); }
        finally { Arrays.fill(key, (byte) 0); }
    }
    private static int collect(Map<String, String> options, A1700Client.Config config,
            List<String> groups, String device, byte[] key) throws IOException {
        Path outputRoot = Paths.get(options.getOrDefault("--output", "captures")).toAbsolutePath();
        Files.createDirectories(outputRoot);
        String session = "a1700-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = outputRoot.resolve(session);
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path logPath = directory.resolve("wire.log"), resultPath = directory.resolve("result.json");
        Files.createFile(logPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        final List<Object> results = new ArrayList<>();
        Map<String, Object> report = A1700Data.map("format", "a1700-capture-v1", "status", "running",
                "startedAt", OffsetDateTime.now().toString(), "settings", A1700Data.map("port", device,
                    "baud", 9600, "dataBits", 7, "parity", "even", "stopBits", 1, "flowControl", "none",
                    "address", config.address, "expectedSerial", config.expectedSerial, "timeoutMs", config.timeoutMs,
                    "delayMs", config.delayMs, "profileSelector", config.profileSelector, "maxProfileBlocks", config.maxProfileBlocks),
                "results", results);
        writeJson(resultPath, report);
        System.out.println("Session: " + directory);
        System.out.println("9600 7E1; port " + device + "; address '" + config.address + "'; meter " + config.expectedSerial);
        Thread worker = Thread.currentThread();
        Thread hook = new Thread(() -> {
            worker.interrupt();
            try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "a1700-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        Iec21Port port = null; boolean failed = false;
        try (BufferedWriter wireLog = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            try {
                if (!Files.exists(Paths.get(device))) throw new IOException("Serial device does not exist: " + device);
                port = new Iec21Port.Builder(device).setInitialBaudrate(9600).setTimeout(config.timeoutMs)
                        .setDeviceAddress(config.address).enableA1700Direct(true).buildAndOpen();
                A1700Client.Observer observer = new A1700Client.Observer() {
                    @Override public void wire(boolean tx, byte[] bytes, boolean partial) throws IOException {
                        wireLog.write("[" + OffsetDateTime.now() + "] " + (tx ? device + "->  " : "<-" + device + "  ")
                                + A1700Protocol.render(bytes) + (partial ? " [PARTIAL/INVALID]" : ""));
                        wireLog.newLine(); wireLog.flush();
                    }
                    @Override public void event(String message) { System.out.println("[" + OffsetDateTime.now() + "] " + message); }
                };
                A1700Client client = port.a1700Client(config, observer);
                for (String group : groups) {
                    System.out.println("START " + group);
                    A1700Client.Capture capture = client.capture(group, key);
                    // Clear the interrupt while saving the partial capture: NIO writes on an interrupted
                    // thread can close their channels before persisting the result.
                    boolean interrupted = Thread.interrupted();
                    if (interrupted) capture.metadata.put("cancelled", true);
                    Map<String, Object> decoded = A1700Decoder.decode(capture);
                    results.add(decoded);
                    // A standalone file per group keeps long captures manageable and preserves completed groups.
                    writeJson(directory.resolve(group + ".json"), decoded);
                    writeJson(resultPath, report);
                    printValues(decoded.get("data"));
                    if (group.equals("profile-probe")) {
                        Map<?, ?> data = (Map<?, ?>) decoded.get("data");
                        System.out.println("Profile selector " + config.profileSelector.toUpperCase(java.util.Locale.ROOT)
                                + ": blocks=" + data.get("expectedBlocks") + ", first header=" + data.get("headers"));
                    }
                    if (!capture.complete || capture.error != null) {
                        failed = true; System.err.println("FAILED " + group + ": " + capture.error); break;
                    }
                    Object data = decoded.get("data");
                    if (data instanceof Map && "invalid_data".equals(((Map<?, ?>) data).get("decodeStatus"))) {
                        failed = true; System.err.println("INVALID DATA " + group); break;
                    }
                    System.out.println("DONE " + group + "; decode limits/quality are recorded in JSON");
                    if (interrupted) { failed = true; break; }
                }
            } catch (IOException e) {
                failed = true;
                if (Thread.interrupted()) report.put("cancelled", true);
                report.put("error", e.getMessage()); System.err.println("A1700: " + e.getMessage());
            }
            finally {
                if (port != null) port.close();
                report.put("status", failed ? "failed_or_partial" : "completed_with_documented_decode_limits");
                report.put("finishedAt", OffsetDateTime.now().toString());
                writeJson(resultPath, report);
            }
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException e) { /* shutdown in progress */ }
        }
        System.out.println("Result: " + resultPath + "\nOriginal TX/RX: " + logPath);
        return failed ? 2 : 0;
    }
    private static void printValues(Object object) {
        if (object instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) object;
            if (map.containsKey("id") && map.containsKey("value")) {
                System.out.println("  " + map.get("id") + " = " + (map.get("value") == null ? "unresolved" : map.get("value"))
                        + (map.get("unit") == null ? "" : " " + map.get("unit"))
                        + (map.get("quality") == null ? "" : " [" + map.get("quality") + "]"));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Profile values are in JSON; printing every interval would obscure live transfer progress.
                if (!entry.getKey().equals("intervals")) printValues(entry.getValue());
            }
        } else if (object instanceof Iterable) for (Object item : (Iterable<?>) object) printValues(item);
    }
    private static int integer(Map<String, String> options, String key, int fallback) {
        try { return Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected integer for " + key); }
    }
    private static void writeJson(Path path, Object data) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".a1700-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, (A1700Data.prettyJson(data) + "\n").getBytes(StandardCharsets.UTF_8));
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void help() {
        System.out.println("A1700 live reader (Linux, fixed 9600 7E1)\n"
                + "  --group current|instantaneous|historical|load-profile|profile-probe|all  (default current)\n"
                + "  --port /dev/ttyUSB0  --address 005  --serial 21000568\n"
                + "  --key FILE  (optional eight-byte credential; default is PMU password level 3)\n"
                + "  --profile-selector HEX4  (default 0100; exact selector semantics unverified)\n"
                + "  profile-probe reads only the count and first 64-byte block, then logs off.\n"
                + "  --max-profile-blocks 4095  --timeout-ms 8000  --delay-ms 300\n"
                + "  --signon-attempts 3  --poll-timeout-ms 15000  --output captures\n"
                + "Each group opens a new login session; Historical currently supports set 1.\n"
                + "The adapter's RS485 electrical mode must already be configured.\n"
                + "Exit 0: transfer finished, consult decode status; 2: failed/partial capture; 1: setup error.\n"
                + "Unknown fields retain raw bytes. PMU logs are never needed at runtime.");
    }
}
