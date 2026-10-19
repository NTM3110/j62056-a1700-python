package org.openmuc.j62056.a1700;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.openmuc.j62056.a1700.A1700Protocol.*;

/** Offline protocol/decoder regression runner, deliberately independent of JUnit/Gradle downloads. */
public final class A1700DriverTest {
    private static final Path FIXTURES = Paths.get("src/test/resources/a1700");
    private static final byte[] KEY = unhex("0102030405060708");
    private static final byte[] CHALLENGE = unhex("1020304050607080");
    private static int assertions;
    private static final class Step {
        byte[] tx, rx;
        Step(byte[] tx, byte[] rx) { this.tx = tx; this.rx = rx; }
    }
    private static final class Script implements A1700Transport {
        final List<Step> steps; int sent; byte[] reply = new byte[0]; int offset;
        boolean failLogoff;
        Script(List<Step> steps) { this.steps = steps; }
        public String name() { return "simulated-PMU"; }
        public void send(byte[] bytes) throws IOException {
            if (Arrays.equals(bytes, frame("B0", null)) && failLogoff) throw new IOException("simulated close failure");
            if (offset != reply.length) throw new IOException("Request sent before entire response consumed");
            if (sent >= steps.size()) throw new IOException("Unexpected extra request " + render(bytes));
            Step step = steps.get(sent++);
            if (!Arrays.equals(bytes, step.tx)) throw new IOException("Request mismatch: expected " + render(step.tx) + ", got " + render(bytes));
            reply = step.rx; offset = 0;
        }
        public int readByte(long deadline) throws IOException {
            if (offset == reply.length) throw new SocketTimeoutException("simulated no response");
            return reply[offset++] & 255;
        }
    }
    private static final class Trace implements A1700Client.Observer {
        final List<byte[]> partial = new ArrayList<>();
        public void event(String event) { }
        public void wire(boolean tx, byte[] bytes, boolean incomplete) { if (!tx && incomplete) partial.add(bytes); }
    }
    private static void check(boolean condition, String text) {
        assertions++; if (!condition) throw new AssertionError(text);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static void number(Object value, String expected, String message) {
        check(value != null && new BigDecimal(value.toString()).compareTo(new BigDecimal(expected)) == 0, message + ": " + value);
    }
    private static A1700Client.Config config() {
        A1700Client.Config config = new A1700Client.Config(); config.delayMs = 0; return config;
    }
    private static List<Step> plan(String name) throws Exception {
        List<Step> requests = new ArrayList<>();
        for (String row : Files.readAllLines(FIXTURES.resolve(name + ".tsv"), StandardCharsets.UTF_8)) {
            if (row.startsWith("#")) continue;
            String[] f = row.split("\t");
            requests.add(new Step(frame(f[1], f[2] + "(" + f[3] + ")"), f[4].equals("-") ? new byte[0] : unhex(f[4])));
        }
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("/?005!\r\n".getBytes(StandardCharsets.US_ASCII), "/GEC5090100301113@000\r\n".getBytes(StandardCharsets.US_ASCII)));
        steps.add(new Step(new byte[] {6,'0','5','1',13,10}, frame("P0", "(" + hex(CHALLENGE) + ")")));
        steps.add(new Step(frame("P2", "(0000000000000000)"), new byte[] {6}));
        steps.add(requests.remove(0)); steps.add(requests.remove(0));
        steps.add(new Step(frame("P2", "(99BBEE3287ED64EC)"), new byte[] {6}));
        steps.addAll(requests); steps.add(new Step(frame("B0", null), new byte[0]));
        return steps;
    }
    private static A1700Client.Capture run(String group, String name) throws Exception {
        Script script = new Script(plan(name)); A1700Client.Config cfg = config();
        if (name.equals("profile-long")) cfg.profileSelector = "FFFF";
        A1700Client.Capture result = new A1700Client(script, cfg, A1700Client.QUIET).capture(group, KEY);
        check(script.sent == script.steps.size(), "All expected PMU requests including B0 consumed: " + name + " " + result.error);
        if (!name.equals("profile-long")) check(result.complete && result.error == null, name + " complete: " + result.error);
        Path output = Paths.get("build/a1700-test-results"); Files.createDirectories(output);
        Files.write(output.resolve(name + ".json"), (A1700Data.json(A1700Decoder.decode(result)) + "\n").getBytes(StandardCharsets.UTF_8));
        return result;
    }
    public static void main(String[] args) throws Exception {
        check(hex(passwordResponse(KEY, CHALLENGE)).equals("99BBEE3287ED64EC"), "Known synthetic challenge-response vector");
        testCurrent(); testInstant(); testHistorical(); testProfiles(); testProfileProbe(); testErrors(); testTransport();
        System.out.println("PASS: " + assertions + " assertions; live workflows match sanitized PMU fixtures; decoders and failure paths checked.");
    }
    private static void testCurrent() throws Exception {
        A1700Client.Capture c = run("current", "current"); Map<String, Object> d = A1700Decoder.current(c.blocks);
        String[] totals = {"27186.688","35767486.896","149.456","27439821.848","87.184","21204.696","45248629.144","25918347.304","1563278.792","0"};
        List<Object> values = list(d.get("totals")); check(values.size() == 10, "10 totals");
        for (int i = 0; i < totals.length; i++) number(obj(values.get(i)).get("value"), totals[i], "Total " + i);
        String[] rates = {"13886.800","5961.960","7337.928","20450261.160","6480644.304","8836406.904"};
        values = list(d.get("rates")); check(values.size() == 32, "32 rates");
        for (int i = 0; i < 32; i++) {
            Map<String, Object> row = obj(values.get(i)); number(row.get("value"), i < 6 ? rates[i] : "0", "Rate " + i);
            check(row.get("label").equals(i < 3 ? "Import Wh" : i < 6 ? "Export Wh" : "No Source"), "Rate source " + i);
        }
        String[] md = {"890.848","704.096","608.096","640.096","95706.928","68256","65662.752","64800"};
        values = list(d.get("cumulativeMaximumDemands")); check(values.size() == 8, "8 CMD");
        for (int i = 0; i < 8; i++) { number(obj(values.get(i)).get("value"), md[i], "CMD " + i); check(obj(values.get(i)).get("unit").equals("W"), "CMD unit W"); }
        check(obj(values.get(7)).get("offset").equals(63), "CMD 8 crosses block boundary");
        for (String family : Arrays.asList("multiUtility","maximumDemands","coincidentDemands")) check(obj(d.get(family)).get("decodeStatus").equals("unsupported"), "Unverified family " + family);
        check(list(d.get("missingFamilies")).isEmpty(), "Complete raw families");
        c.blocks.get("507001")[0] = (byte) 0xfa;
        check(obj(list(A1700Decoder.current(c.blocks).get("totals")).get(0)).get("quality").equals("invalid_bcd"), "Invalid BCD never becomes numeric");
        c.blocks.remove("508002"); check(list(A1700Decoder.current(c.blocks).get("rates")).isEmpty(), "Missing rate block cannot shift following records");
    }
    private static void testInstant() throws Exception {
        A1700Client.Capture c = run("instantaneous", "instantaneous");
        Map<String, Object> d = A1700Decoder.instantaneous(c.blocks); List<Object> groups = list(d.get("groups"));
        check(groups.size() == 9, "9 instantaneous groups"); int n = 0;
        for (Object group : groups) n += list(obj(group).get("values")).size(); check(n == 29, "29 selected displayed fields, no unused slots");
        number(obj(list(obj(groups.get(1)).get("values")).get(0)).get("value"), "222.4633", "Voltage A empirical value");
        number(obj(list(obj(groups.get(6)).get("values")).get(0)).get("value"), "50.2492", "Frequency full precision retained");
        check(obj(list(obj(groups.get(6)).get("values")).get(0)).get("displayValueCandidate").equals("50.24"), "Frequency display candidate");
        check(obj(list(obj(groups.get(6)).get("values")).get(1)).get("quality").equals("unavailable"), "Frequency B unavailable");
        for (Object row : list(obj(groups.get(5)).get("values"))) number(obj(row).get("value"), "1", "PF");
        check(obj(list(obj(groups.get(7)).get("values")).get(0)).get("negativeZero").equals(true), "Angle signed zero retained");
        check(c.metadata.get("polls_reactive_power").equals(3), "Reactive ready in 3 polls");
        check(c.metadata.get("polls_phase_angle").equals(4), "Repeated status accepted");
        c.blocks.get("606001@active_power")[6] = 1;
        check(obj(list(obj(list(A1700Decoder.instantaneous(c.blocks).get("groups")).get(2)).get("values")).get(0)).get("value") == null, "Nonzero power scale not guessed");
    }
    private static void testHistorical() throws Exception {
        A1700Client.Capture c = run("historical", "historical"); Map<String, Object> d = A1700Decoder.historical(c.blocks);
        Map<String, Object> b = obj(d.get("billing")); number(b.get("resetNumber"), "29", "Reset counter");
        check(b.get("periodStart").equals("2025-03-01T00:00"), "Period start");
        check(b.get("periodEnd").equals("2025-04-01T00:00"), "Period end");
        check(b.get("resetTime").equals("2026-09-15T14:20:38"), "Reset timestamp, no timezone shift");
        number(obj(list(d.get("totals")).get(1)).get("value"), "35767486.896", "Historical export");
        number(obj(list(d.get("cumulativeMaximumDemands")).get(7)).get("value"), "64800", "Historical CMD boundary");
        check(!c.blocks.containsKey("544001"), "No invented 544 read");
        check(c.blocks.get("548001").length == 64, "548 auxiliary preserved");
    }
    private static void testProfiles() throws Exception {
        A1700Client.Capture shortCapture = run("load-profile", "profile-short");
        Map<String, Object> small = A1700Decoder.profile(shortCapture);
        check(list(small.get("intervals")).size() == 33, "33 recent intervals");
        check(small.get("remainingBytes").equals(11), "11 FF padding bytes");
        check(obj(list(small.get("intervals")).get(32)).get("end").equals("2026-09-16T16:30"), "Last recent interval");
        A1700Client.Capture largeCapture = run("load-profile", "profile-long");
        check(!largeCapture.complete && largeCapture.error.contains("55015B"), "Incomplete profile error includes first missing block");
        check(largeCapture.metadata.get("expectedBlocks").equals(1349), "No 256-block truncation");
        check(largeCapture.metadata.get("receivedBlocks").equals(346), "346 complete blocks preserved");
        Map<String, Object> large = A1700Decoder.profile(largeCapture); List<Object> rows = list(large.get("intervals"));
        check(rows.size() == 1265, "1265 structurally supported intervals before event");
        check(large.get("stopOffset").equals(16661), "Stop at first unknown event");
        check(list(large.get("eventCandidates")).size() == 4, "Four contiguous raw events retained");
        List<String> reference = Files.readAllLines(FIXTURES.resolve("profile-day-reference.tsv"), StandardCharsets.UTF_8);
        BigDecimal export = BigDecimal.ZERO;
        java.time.LocalDateTime start = java.time.LocalDateTime.of(2023,6,19,0,0);
        for (int i = 0; i < 48; i++) {
            Map<String, Object> row = obj(rows.get(i)); List<Object> values = list(row.get("values"));
            String[] csv = reference.get(i + 1).split("\t");
            for (int j = 0; j < 4; j++) number(obj(values.get(j)).get("value"), csv[j + 2], "Profile row " + i + ", channel " + j);
            export = export.add((BigDecimal) obj(values.get(1)).get("value"));
            check(row.get("start").equals(start.plusMinutes(30L * i).toString()), "Start time " + i);
            check(row.get("end").equals(start.plusMinutes(30L * (i + 1)).toString()), "End time " + i);
            java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.US);
            check(row.get("start").equals(start.toLocalDate().atTime(java.time.LocalTime.parse(csv[0], clock)).toString()), "CSV start time " + i);
            if (i < 47) check(row.get("end").equals(start.toLocalDate().atTime(java.time.LocalTime.parse(csv[1], clock)).toString()), "CSV end time " + i);
            else check(csv[1].equals("12:00:00 PM"), "Known source noon/midnight anomaly retained in fixture");
            check(list(row.get("flags")).get(0).equals(csv[6]), "Flags " + i);
        }
        number(export.multiply(new BigDecimal("0.5")), "60.480", "Derived day export energy");
        check(obj(rows.get(47)).get("end").equals("2023-06-20T00:00"), "Correct midnight despite CSV noon typo");
        largeCapture.blocks.remove("550002"); check(A1700Decoder.profile(largeCapture).get("blockGap").equals(true), "Missing block gap detected");
        byte[] one = unhex("E4809A8F6403039940000000192001201602000000");
        Map<String, Object> field = obj(list(obj(list(A1700Decoder.profileStream(one).get("intervals")).get(0)).get("values")).get(1));
        check(field.get("value") == null, "Tag 01 unresolved");
        one[5] = 0; check(list(A1700Decoder.profileStream(one).get("intervals")).isEmpty(), "Unsupported profile configuration rejected");
    }
    private static int requestIndex(List<Step> plan, String register) {
        for (int i = 0; i < plan.size(); i++) if (render(plan.get(i).tx).contains(register + "(")) return i;
        throw new AssertionError(register);
    }
    private static Script failingRead(String kind, Trace trace) throws Exception {
        List<Step> steps = plan("current"); int index = requestIndex(steps, "507001"); Step broken = steps.get(index);
        if (kind.equals("bcc")) broken.rx[broken.rx.length - 1] ^= 1;
        if (kind.equals("partial")) broken.rx = Arrays.copyOf(broken.rx, 5);
        if (kind.equals("nak")) broken.rx = new byte[] {21};
        if (kind.equals("short")) broken.rx = dataFrame("00");
        if (kind.equals("hex")) broken.rx = dataFrame(String.join("", java.util.Collections.nCopies(128, "G")));
        steps = new ArrayList<>(steps.subList(0, index + 1)); steps.add(new Step(frame("B0", null), new byte[0]));
        Script script = new Script(steps);
        A1700Client.Capture c = new A1700Client(script, config(), trace).capture("current", KEY);
        check(!c.complete && c.error.contains("507001"), "Failure retains stage " + kind);
        check(c.blocks.containsKey("667001") && !c.blocks.containsKey("507001"), "Only valid prior blocks retained " + kind);
        check(script.sent == steps.size(), "B0 attempted after " + kind);
        if (kind.equals("partial")) check(trace.partial.size() > 0 && trace.partial.get(0).length == 5, "Partial raw bytes logged");
        return script;
    }
    private static byte[] dataFrame(String hex) {
        byte[] body = ("(" + hex + ")\003").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(2); int bcc = 0;
        for (byte b : body) { out.write(b); bcc ^= b & 255; } out.write(bcc); return out.toByteArray();
    }
    private static void testErrors() throws Exception {
        for (String kind : Arrays.asList("bcc","partial","nak","short","hex")) failingRead(kind, new Trace());
        List<Step> retry = plan("current"); retry.add(0, new Step(retry.get(0).tx, new byte[0]));
        Script script = new Script(retry); A1700Client.Capture c = new A1700Client(script, config(), A1700Client.QUIET).capture("current", KEY);
        check(c.complete && script.sent == retry.size(), "Silent sign-on retried");
        List<Step> echo = plan("current");
        ByteArrayOutputStream echoed = new ByteArrayOutputStream(); echoed.write(echo.get(0).tx); echoed.write(echo.get(0).rx);
        echo.get(0).rx = echoed.toByteArray();
        c = new A1700Client(new Script(echo), config(), A1700Client.QUIET).capture("current", KEY);
        check(c.complete && c.error == null, "Exact adapter sign-on echo does not masquerade as meter identification");
        List<Step> partialId = new ArrayList<>(); partialId.add(new Step("/?005!\r\n".getBytes(StandardCharsets.US_ASCII), new byte[] {'/','G'}));
        script = new Script(partialId); c = new A1700Client(script, config(), A1700Client.QUIET).capture("current", KEY);
        check(!c.complete && script.sent == 1, "Partial identification not mixed with a retry");
        List<Step> wrongMeter = new ArrayList<>(plan("current").subList(0,5)); wrongMeter.add(new Step(frame("B0",null),new byte[0]));
        A1700Client.Config cfg = config(); cfg.expectedSerial = "different-meter";
        script = new Script(wrongMeter); c = new A1700Client(script,cfg,A1700Client.QUIET).capture("current",KEY);
        check(c.error.contains("connected meter") && script.sent == 6, "Wrong meter rejected before credential response");
        List<Step> badSelector = plan("instantaneous"); int ix = requestIndex(badSelector,"605001"); // W1, followed by first status read
        badSelector.get(ix + 1).rx = dataFrame("9B2A4A00");
        badSelector = new ArrayList<>(badSelector.subList(0,ix + 2)); badSelector.add(new Step(frame("B0",null),new byte[0]));
        c = new A1700Client(new Script(badSelector),config(),A1700Client.QUIET).capture("instantaneous",KEY);
        check(c.error.contains("selector mismatch"), "Stale ready data rejected");
        List<Step> limit = plan("profile-long"); ix = requestIndex(limit,"551001") + 1;
        limit = new ArrayList<>(limit.subList(0,ix + 1)); limit.add(new Step(frame("B0",null),new byte[0]));
        cfg = config(); cfg.profileSelector="FFFF"; cfg.maxProfileBlocks=10;
        c = new A1700Client(new Script(limit),cfg,A1700Client.QUIET).capture("load-profile",KEY);
        check(c.error.contains("exceeds configured limit") && !c.complete,"Resource limit never reports partial history as full");
        List<Step> closeFailure = plan("current"); int bad = requestIndex(closeFailure, "507001");
        closeFailure.get(bad).rx[closeFailure.get(bad).rx.length - 1] ^= 1;
        script = new Script(new ArrayList<>(closeFailure.subList(0, bad + 1))); script.failLogoff = true;
        c = new A1700Client(script, config(), A1700Client.QUIET).capture("current", KEY);
        check(c.error.contains("BCC mismatch") && c.metadata.containsKey("logoffError"), "Cleanup failure preserves original protocol failure");
    }
    private static void testProfileProbe() throws Exception {
        List<Step> steps = plan("profile-short");
        int countRequest = requestIndex(steps, "551001") + 1;
        steps = new ArrayList<>(steps.subList(0, countRequest + 2));
        steps.get(countRequest - 1).tx = frame("W1", "551001(0A00)");
        steps.add(new Step(frame("B0", null), new byte[0]));
        Script script = new Script(steps);
        A1700Client.Config cfg = config(); cfg.profileSelector = "0A00";
        A1700Client.Capture c = new A1700Client(script, cfg, A1700Client.QUIET).capture("profile-probe", KEY);
        check(c.complete && c.error == null && script.sent == steps.size(), "Probe logs off after first block");
        check(c.blocks.containsKey("551001") && c.blocks.containsKey("550001") && c.blocks.size() == 4,
                "Probe stores count and first block only");
        Map<String, Object> data = obj(A1700Decoder.decode(c).get("data"));
        check(!list(data.get("headers")).isEmpty(), "Probe decodes first timestamp");
        check(data.get("decodeStatus").equals("partial"), "Probe does not mark limited read as full profile");
    }
    private static void testTransport() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        A1700Transport transport = new A1700Transport.Streams(new ByteArrayInputStream(new byte[]{1,2,3}),out,"test",true);
        for (int i=1;i<=3;i++) check(transport.readByte(System.nanoTime()+100000000L)==i,"Stream byte " + i);
        long start=System.nanoTime(); boolean timedOut=false;
        try { transport.readByte(start+20000000L); } catch(SocketTimeoutException e) { timedOut=true; }
        check(timedOut && System.nanoTime()-start<500000000L,"Bounded stream deadline");
        transport.send(new byte[]{9,8}); check(Arrays.equals(out.toByteArray(),new byte[]{9,8}),"Stream output");
        check(A1700Data.json(A1700Data.map("text","a\n\"b\\","value",new BigDecimal("27186.688"))).equals("{\"text\":\"a\\u000a\\\"b\\\\\",\"value\":27186.688}"),"JSON escapes and exact decimals");
        check(A1700Data.prettyJson(A1700Data.map("name", "A1700", "values", Arrays.asList(1, 2))).equals(
                "{\n  \"name\": \"A1700\",\n  \"values\": [\n    1,\n    2\n  ]\n}"),
                "Pretty JSON uses newlines and two-space indentation");
    }
}
