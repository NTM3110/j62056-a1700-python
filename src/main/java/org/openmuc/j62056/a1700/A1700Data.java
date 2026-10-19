package org.openmuc.j62056.a1700;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free JSON serializer. BigDecimal values are emitted without floating point conversion. */
public final class A1700Data {
    private A1700Data() { }
    public static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Key/value pairs required");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    public static String json(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value, -1); return out.toString();
    }
    public static String prettyJson(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value, 0); return out.toString();
    }
    private static void append(StringBuilder out, Object value, int depth) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                if (depth >= 0) { out.append('\n'); indent(out, depth + 1); }
                first = false;
                append(out, String.valueOf(e.getKey()), -1); out.append(depth >= 0 ? ": " : ":");
                append(out, e.getValue(), depth < 0 ? -1 : depth + 1);
            }
            if (!first && depth >= 0) { out.append('\n'); indent(out, depth); }
            out.append('}'); return;
        }
        if (value instanceof Iterable) {
            out.append('['); boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                if (depth >= 0) { out.append('\n'); indent(out, depth + 1); }
                first = false; append(out, item, depth < 0 ? -1 : depth + 1);
            }
            if (!first && depth >= 0) { out.append('\n'); indent(out, depth); }
            out.append(']'); return;
        }
        String text = String.valueOf(value); out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }
    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth * 2; i++) out.append(' ');
    }
}
