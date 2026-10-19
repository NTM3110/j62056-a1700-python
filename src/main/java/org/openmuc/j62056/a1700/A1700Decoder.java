package org.openmuc.j62056.a1700;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.openmuc.j62056.a1700.A1700Data.map;
import static org.openmuc.j62056.a1700.A1700Protocol.hex;

/** Empirical decoding, with raw/quality retained when the captured data does not establish a format. */
public final class A1700Decoder {
    private A1700Decoder() { }
    private static final String[] TOTAL_NAMES = {"Import Wh", "Export Wh", "Q1 varh", "Q2 varh", "Q3 varh", "Q4 varh", "VAh", "CD 1", "CD 2", "CD 3"};
    private static final String[] TOTAL_UNITS = {"Wh", "Wh", "varh", "varh", "varh", "varh", "VAh", null, null, null};

    public static Map<String, Object> decode(A1700Client.Capture capture) {
        Map<String, Object> data;
        try {
            if (capture.group.equals("current")) data = current(capture.blocks);
            else if (capture.group.equals("historical")) data = historical(capture.blocks);
            else if (capture.group.equals("instantaneous")) data = instantaneous(capture.blocks);
            else if (capture.group.equals("load-profile") || capture.group.equals("profile-probe")) data = profile(capture);
            else throw new IllegalArgumentException("Unsupported group " + capture.group);
        } catch (IllegalArgumentException e) {
            data = map("decodeStatus", "invalid_data", "error", e.getMessage());
        }
        return map("group", capture.group, "transferStatus", capture.complete ? "complete" : "partial",
                "sessionError", capture.error, "metadata", capture.metadata, "data", data, "rawBlocks", capture.rawHex());
    }

    public static BigDecimal bcd(byte[] raw, boolean littleEndian, int scale) {
        StringBuilder digits = new StringBuilder();
        for (int j = 0; j < raw.length; j++) {
            int value = raw[littleEndian ? raw.length - 1 - j : j] & 255;
            if ((value >> 4) > 9 || (value & 15) > 9) throw new IllegalArgumentException("Invalid BCD: " + hex(raw));
            digits.append(value >> 4).append(value & 15);
        }
        if (raw.length == 0) throw new IllegalArgumentException("Empty BCD value");
        return new BigDecimal(new BigInteger(digits.toString()), scale);
    }

    private static Map<String, Object> numeric(String id, String label, String unit, byte[] raw, int offset, boolean little, int scale) {
        Map<String, Object> out = map("id", id, "label", label, "value", null, "unit", unit,
                "offset", offset, "rawHex", hex(raw), "quality", "available", "confidence", "matched_fixture");
        try { out.put("value", bcd(raw, little, scale)); }
        catch (IllegalArgumentException e) { out.put("quality", "invalid_bcd"); out.put("confidence", "unsupported"); out.put("warning", e.getMessage()); }
        return out;
    }
    private static byte[] family(Map<String, byte[]> blocks, String prefix, int count, int lastSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i <= count; i++) {
            byte[] block = blocks.get(prefix + String.format(java.util.Locale.ROOT, "%03X", i));
            if (block == null) return null;
            int expected = i == count ? lastSize : 64;
            if (block.length != expected) throw new IllegalArgumentException("Wrong byte length for " + prefix + " block " + i);
            out.write(block, 0, block.length);
        }
        return out.toByteArray();
    }
    private static List<Object> totals(byte[] data) {
        List<Object> out = new ArrayList<>(); if (data == null) return out;
        for (int i = 0; i < 10; i++) out.add(numeric("total_" + (i + 1), TOTAL_NAMES[i], TOTAL_UNITS[i],
                Arrays.copyOfRange(data, i * 8, i * 8 + 8), i * 8, true, 3));
        return out;
    }
    private static List<Object> rates(byte[] data, byte[] sources) {
        List<Object> out = new ArrayList<>(); if (data == null) return out;
        for (int i = 0; i < 32; i++) {
            int source = sources != null && sources.length == 32 ? sources[i] & 255 : -1;
            String label = source == 1 ? "Import Wh" : source == 2 ? "Export Wh" : source == 0 ? "No Source" : "Unknown source";
            Map<String, Object> row = numeric("rate_" + (i + 1), label, source == 1 || source == 2 ? "Wh" : null,
                    Arrays.copyOfRange(data, i * 8, i * 8 + 8), i * 8, true, 3);
            row.put("register", i + 1); row.put("sourceCode", source < 0 ? null : source);
            if (source == 0 && row.get("value") != null) row.put("quality", "no_source");
            if (source < 0 || source > 2) row.put("warning", "Source/unit not established");
            out.add(row);
        }
        return out;
    }
    private static List<Object> cumulativeMd(byte[] data, byte[] sources) {
        List<Object> out = new ArrayList<>(); if (data == null) return out;
        for (int i = 0; i < 8; i++) {
            int offset = i * 9; int source = data[offset + 8] & 255;
            Map<String, Object> row = numeric("cumulative_md_" + (i + 1), source == 1 ? "Import W" : source == 2 ? "Export W" : "Unknown source",
                    source == 1 || source == 2 ? "W" : null, Arrays.copyOfRange(data, offset, offset + 8), offset, true, 3);
            row.put("register", i + 1); row.put("sourceCode", source); row.put("recordRawHex", hex(Arrays.copyOfRange(data, offset, offset + 9)));
            if (sources != null && sources.length == 8 && source != (sources[i] & 255)) {
                row.put("warning", "Embedded source differs from 668001; retaining embedded source");
            }
            if (source != 1 && source != 2) row.put("warning", "Source/unit not established");
            out.add(row);
        }
        return out;
    }
    private static Map<String, Object> unresolved(byte[] data, int count, String reason) {
        return map("decodeStatus", data == null ? "missing_blocks" : "unsupported", "expectedItems", count,
                "rawHex", data == null ? null : hex(data), "reason", reason);
    }
    private static Map<String, Object> currentParts(byte[] total, byte[] rates, byte[] mu, byte[] md,
            byte[] cumulativeMd, byte[] coincident, byte[] rateSources, byte[] mdSources) {
        List<Object> missing = new ArrayList<>();
        if (total == null) missing.add("totals"); if (rates == null) missing.add("rates");
        if (mu == null) missing.add("multiUtility"); if (md == null) missing.add("maximumDemands");
        if (cumulativeMd == null) missing.add("cumulativeMaximumDemands"); if (coincident == null) missing.add("coincidentDemands");
        return map("decodeStatus", "partial", "totals", totals(total), "rates", rates(rates, rateSources),
                "cumulativeMaximumDemands", cumulativeMd(cumulativeMd, mdSources),
                "multiUtility", unresolved(mu, 4, "Zero-only reference: MU layout and nonzero encoding unverified"),
                "maximumDemands", unresolved(md, 24, "8 registers x 3 levels: offsets, values and timestamps need nonzero references"),
                "coincidentDemands", unresolved(coincident, 15, "5 registers x 3 levels: internal field layout unverified"),
                "missingFamilies", missing, "note", "50 mapped items; 43 report items retained as unresolved families, not invented zeros");
    }
    public static Map<String, Object> current(Map<String, byte[]> blocks) {
        return currentParts(family(blocks, "507", 2, 16), family(blocks, "508", 4, 64), family(blocks, "516", 1, 32),
                family(blocks, "510", 5, 32), family(blocks, "509", 2, 8), family(blocks, "511", 2, 56),
                blocks.get("667001"), blocks.get("668001"));
    }
    private static long uintLE(byte[] data, int offset, int length) {
        long value = 0; for (int i = 0; i < length; i++) value |= (data[offset + i] & 255L) << (8 * i); return value;
    }
    private static LocalDateTime meterTime(byte[] data, int offset) {
        return LocalDateTime.of(1970, 1, 1, 0, 0).plusSeconds(uintLE(data, offset, 4));
    }
    public static Map<String, Object> historical(Map<String, byte[]> blocks) {
        byte[] data = family(blocks, "543", 14, 31);
        if (data == null) return map("decodeStatus", "missing_blocks", "set", 1, "warning", "Need all 14 blocks for a historical snapshot");
        Map<String, Object> result = currentParts(Arrays.copyOfRange(data, 0, 80), Arrays.copyOfRange(data, 80, 336),
                Arrays.copyOfRange(data, 336, 368), Arrays.copyOfRange(data, 440, 728), Arrays.copyOfRange(data, 368, 440),
                Arrays.copyOfRange(data, 728, 848), blocks.get("667001"), blocks.get("668001"));
        int reason = data[858] & 255;
        result.put("set", 1);
        result.put("billing", map("resetNumber", uintLE(data, 848, 2), "periodStart", meterTime(data, 850).toString(),
                "periodEnd", meterTime(data, 854).toString(), "resetTime", meterTime(data, 859).toString(),
                "reasonRaw", reason, "reasonLabelsCandidate", reason == 0x81
                    ? Arrays.asList("Automatic billing date", "Power-up process") : java.util.Collections.emptyList(),
                "rawHex", hex(Arrays.copyOfRange(data, 848, 863)), "timezone", "unverified_meter_display_time",
                "warning", "Reason bit assignments unverified; timestamps use epoch arithmetic without timezone conversion"));
        result.put("auxiliary548", unresolved(blocks.get("548001"), 1, "Historical configuration layout unverified"));
        result.put("sourceWarning", "Rate labels use 667001 as in this fixture; historical source changes require decoding 548");
        return result;
    }

    public static Map<String, Object> instantaneous(Map<String, byte[]> blocks) {
        List<Object> groups = new ArrayList<>();
        for (Map.Entry<String, String> entry : A1700Client.SELECTORS.entrySet()) {
            String group = entry.getKey(); byte[] data = blocks.get("606001@" + group);
            if (data == null) { groups.add(map("group", group, "decodeStatus", "missing_block")); continue; }
            if (data.length != 28) throw new IllegalArgumentException("Instantaneous payload requires 28 bytes");
            boolean total = Arrays.asList("active_power", "reactive_power", "apparent_power", "power_factor").contains(group);
            int count = group.equals("phase_rotation") ? 1 : total ? 4 : 3;
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String phase = group.equals("phase_rotation") ? "meter" : new String[] {"A", "B", "C", "total"}[i];
                values.add(instantSlot(group, phase, Arrays.copyOfRange(data, i * 7, i * 7 + 7), i * 7));
            }
            groups.add(map("group", group, "selector", entry.getValue(), "values", values, "rawHex", hex(data)));
        }
        return map("decodeStatus", "partial", "groups", groups,
                "warning", "V/Hz/PF use empirical BCD scale 4; nonzero I/P/Q/S, sign, rotation and display rounding remain unverified");
    }
    private static Map<String, Object> instantSlot(String group, String phase, byte[] raw, int offset) {
        String unit = group.equals("current") ? "A" : group.equals("voltage") ? "V" : group.equals("active_power") ? "kW"
                : group.equals("reactive_power") ? "kvar" : group.equals("apparent_power") ? "kVA" : group.equals("frequency") ? "Hz"
                : group.equals("phase_angle") ? "degree" : null;
        Map<String, Object> out = map("id", group + "_" + phase, "phase", phase, "value", null, "unit", unit,
                "rawHex", hex(raw), "offset", offset, "quality", "unknown", "confidence", "unsupported");
        if (hex(raw).equals("00FF0000000000")) { out.put("quality", "unavailable"); out.put("confidence", "matched_fixture"); return out; }
        if (group.equals("phase_rotation")) {
            out.put("quality", allZero(raw) ? "unavailable" : "unknown");
            out.put("warning", "Rotation enum is unverified; zero corresponds to Non available in reference"); return out;
        }
        if (group.equals("phase_angle")) {
            if (hex(raw).equals("80000000000000") || allZero(raw)) {
                out.put("value", BigDecimal.ZERO); out.put("displayValue", raw[0] == (byte) 128 ? "-0.00" : "0.00");
                out.put("negativeZero", raw[0] == (byte) 128); out.put("quality", "available"); out.put("confidence", "zero_only");
            } else out.put("warning", "Nonzero angle/sign format unverified");
            return out;
        }
        boolean knownScale = Arrays.asList("voltage", "frequency", "power_factor").contains(group);
        if (!knownScale) {
            if (allZero(raw)) { out.put("value", BigDecimal.ZERO.setScale(3)); out.put("quality", "available"); out.put("confidence", "zero_only"); }
            else out.put("warning", "Nonzero scale/sign unverified for this group; raw retained");
            return out;
        }
        if (raw[0] != 0) { out.put("warning", "Unverified sign/status byte"); return out; }
        try {
            BigDecimal value = bcd(Arrays.copyOfRange(raw, 1, 7), false, 4);
            out.put("value", value); out.put("quality", "available"); out.put("confidence", "empirical_scale");
            out.put("displayValueCandidate", value.setScale(group.equals("power_factor") ? 3 : 2, RoundingMode.DOWN).toPlainString());
            out.put("displayRule", "truncate_candidate_not_verified");
        } catch (IllegalArgumentException e) { out.put("quality", "invalid_bcd"); out.put("warning", e.getMessage()); }
        return out;
    }
    private static boolean allZero(byte[] raw) { for (byte b : raw) if (b != 0) return false; return true; }

    public static Map<String, Object> profile(A1700Client.Capture capture) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int received = 0;
        while (true) {
            byte[] block = capture.blocks.get("550" + String.format(java.util.Locale.ROOT, "%03X", received + 1));
            if (block == null) break;
            if (block.length != 64) throw new IllegalArgumentException("Profile block must be 64 bytes");
            joined.write(block, 0, block.length); received++;
        }
        Map<String, Object> parsed = profileStream(joined.toByteArray());
        byte[] count = capture.blocks.get("551001");
        Integer expected = count != null && count.length == 2 ? (count[0] & 255) * 256 + (count[1] & 255) : null;
        int availableBlocks = 0;
        for (String key : capture.blocks.keySet()) if (key.matches("550[0-9A-F]{3}")) availableBlocks++;
        parsed.put("expectedBlocks", expected); parsed.put("receivedContiguousBlocks", received);
        parsed.put("blockGap", availableBlocks != received);
        if (expected == null || received != expected || availableBlocks != received) {
            parsed.put("decodeStatus", "partial"); parsed.put("transferWarning", "Missing count/blocks or gap; only contiguous prefix decoded");
        }
        return parsed;
    }

    /** Configuration is explicitly limited to the captured four channels and 30-minute intervals. */
    public static Map<String, Object> profileStream(byte[] data) {
        List<Object> rows = new ArrayList<>(), headers = new ArrayList<>(), events = new ArrayList<>();
        int offset = 0; LocalDateTime next = null; String stop = null; boolean unsupportedValue = false;
        while (offset < data.length) {
            int marker = data[offset] & 255;
            if (marker == 0xe4) {
                if (data.length - offset < 8) { stop = "Incomplete E4 header"; break; }
                byte[] header = Arrays.copyOfRange(data, offset, offset + 8);
                if (!hex(Arrays.copyOfRange(header, 5, 8)).equals("030399")) { stop = "Unsupported channel/interval metadata"; break; }
                LocalDateTime time = meterTime(header, 1);
                if (next != null && !next.equals(time)) { stop = "Header timestamp discontinuity"; break; }
                next = time; headers.add(map("offset", offset, "time", time.toString(), "rawHex", hex(header)));
                offset += 8; continue;
            }
            if (marker == 0xff) {
                boolean padding = true; for (int i = offset; i < data.length; i++) padding &= data[i] == (byte) 255;
                if (padding) { stop = "Trailing FF padding"; break; }
            }
            if (marker == 0xe5 || marker == 0xe6) {
                // Preserve consecutive event candidates, but do not advance interval time past an unknown event.
                for (int pos = offset; pos < data.length && ((data[pos] & 255) == 0xe5 || (data[pos] & 255) == 0xe6); pos += 5) {
                    int end = Math.min(pos + 5, data.length);
                    events.add(map("offset", pos, "marker", data[pos] & 255, "rawHex", hex(Arrays.copyOfRange(data, pos, end)),
                            "timestampCandidate", end - pos == 5 ? meterTime(data, pos + 1).toString() : null));
                    if (end - pos < 5) break;
                }
                stop = "Unsupported E5/E6 event; subsequent interval timing unverified"; break;
            }
            if (next == null) { stop = "Record before a supported header"; break; }
            if (marker != 0x40 && marker != 0x60) { stop = "Unsupported status/record marker " + marker; break; }
            if (data.length - offset < 13) { stop = "Incomplete interval record"; break; }
            List<Object> values = new ArrayList<>();
            String[] ids = {"import", "export", "customer_defined_1", "customer_defined_2"};
            int[] channels = {1, 2, 8, 9};
            for (int i = 0; i < 4; i++) {
                byte[] field = Arrays.copyOfRange(data, offset + 1 + i * 3, offset + 4 + i * 3);
                Map<String, Object> value = map("id", ids[i], "channel", channels[i], "unit", i < 2 ? "kW" : null,
                        "value", null, "rawHex", hex(field), "confidence", "unsupported");
                if (allZero(field)) { value.put("value", BigDecimal.ZERO); value.put("confidence", "zero_only"); }
                else if (field[2] == 2 && (i == 1 || i == 2)) {
                    try { value.put("value", bcd(Arrays.copyOfRange(field, 0, 2), false, 3)); value.put("confidence", "matched_fixture"); }
                    catch (IllegalArgumentException e) { value.put("warning", e.getMessage()); }
                } else value.put("warning", "Unverified channel/third-byte scale; no general exponent assumed");
                if (value.get("value") == null) unsupportedValue = true;
                values.add(value);
            }
            rows.add(map("offset", offset, "start", next.toString(), "end", next.plusMinutes(30).toString(),
                    "statusRaw", marker, "flags", marker == 0x40 ? Arrays.asList("Phase C fail") : Arrays.asList("Phase C fail", "Phase B fail (inferred)"),
                    "quality", "phase_failure", "values", values, "rawHex", hex(Arrays.copyOfRange(data, offset, offset + 13))));
            next = next.plusMinutes(30); offset += 13;
        }
        boolean parsedToEnd = offset == data.length || "Trailing FF padding".equals(stop);
        return map("decodeStatus", parsedToEnd && !unsupportedValue ? "complete_for_configured_layout" : "partial",
                "configuration", map("activeChannels", Arrays.asList(1, 2, 8, 9), "intervalMinutes", 30,
                    "source", "PMU reference configuration; not decoded from 030399", "timezone", "unverified_meter_display_time"),
                "headers", headers, "intervals", rows, "eventCandidates", events, "stopOffset", offset,
                "stopReason", stop, "remainingBytes", data.length - offset,
                "remainingRawHex", hex(Arrays.copyOfRange(data, offset, data.length)),
                "unsupportedValues", unsupportedValue, "note", "Only 2023-06-19 values have independent CSV verification");
    }
}
