package dev.kodex.ext.wuxiaworld;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal protobuf (proto3 wire format) codec plus gRPC-web framing — just enough to talk to WuxiaWorld's
 * {@code api2.wuxiaworld.com} gRPC-web API without a protobuf runtime. Only the field types WuxiaWorld uses
 * are handled: varints (int32/int64/bool/enum), length-delimited (string/bytes/nested message, repeated),
 * and fixed32/64 (stored as raw longs; unused by our reads).
 */
final class Proto {

    private Proto() {
    }

    // ---- Wire framing (gRPC-web) ------------------------------------------------------------------

    /** Wrap a protobuf message in a gRPC-web data frame: {@code [0x00][4-byte BE length][message]}. */
    static byte[] frame(byte[] message) {
        byte[] out = new byte[5 + message.length];
        out[0] = 0; // uncompressed data frame
        out[1] = (byte) (message.length >>> 24);
        out[2] = (byte) (message.length >>> 16);
        out[3] = (byte) (message.length >>> 8);
        out[4] = (byte) message.length;
        System.arraycopy(message, 0, out, 5, message.length);
        return out;
    }

    /** Extract the first data frame's payload from a gRPC-web response (skips the trailer frame, flag 0x80). */
    static byte[] unframe(byte[] body) {
        int pos = 0;
        while (pos + 5 <= body.length) {
            int flag = body[pos] & 0xff;
            int len = ((body[pos + 1] & 0xff) << 24) | ((body[pos + 2] & 0xff) << 16)
                | ((body[pos + 3] & 0xff) << 8) | (body[pos + 4] & 0xff);
            pos += 5;
            if (pos + len > body.length) {
                break;
            }
            if ((flag & 0x80) == 0) { // data frame (trailer frames set bit 0x80)
                byte[] out = new byte[len];
                System.arraycopy(body, pos, out, 0, len);
                return out;
            }
            pos += len;
        }
        return new byte[0];
    }

    // ---- Decoding ---------------------------------------------------------------------------------

    /**
     * Decode a protobuf message into {@code fieldNumber -> [values]}. Each value is a {@link Long} (varint /
     * fixed32 / fixed64) or a {@code byte[]} (length-delimited). Repeated fields collect multiple values.
     */
    static Map<Integer, List<Object>> decode(byte[] data) {
        Map<Integer, List<Object>> fields = new LinkedHashMap<>();
        if (data == null) {
            return fields;
        }
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = readVarint(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            Object value;
            switch (wire) {
                case 0 -> value = readVarint(data, pos);
                case 1 -> {
                    value = readFixed(data, pos[0], 8);
                    pos[0] += 8;
                }
                case 2 -> {
                    int len = (int) readVarint(data, pos);
                    byte[] slice = new byte[len];
                    System.arraycopy(data, pos[0], slice, 0, len);
                    pos[0] += len;
                    value = slice;
                }
                case 5 -> {
                    value = readFixed(data, pos[0], 4);
                    pos[0] += 4;
                }
                default -> {
                    return fields; // unknown wire type: stop (malformed / truncated)
                }
            }
            fields.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
        }
        return fields;
    }

    private static long readVarint(byte[] data, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            byte b = data[pos[0]++];
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    private static long readFixed(byte[] data, int off, int n) {
        long v = 0;
        for (int i = 0; i < n && off + i < data.length; i++) {
            v |= (long) (data[off + i] & 0xff) << (8 * i);
        }
        return v;
    }

    // ---- Field accessors --------------------------------------------------------------------------

    static byte[] bytes(Map<Integer, List<Object>> m, int field) {
        List<Object> v = m.get(field);
        if (v == null || v.isEmpty() || !(v.get(0) instanceof byte[] b)) {
            return null;
        }
        return b;
    }

    static String string(Map<Integer, List<Object>> m, int field) {
        byte[] b = bytes(m, field);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static long longVal(Map<Integer, List<Object>> m, int field, long def) {
        List<Object> v = m.get(field);
        if (v == null || v.isEmpty() || !(v.get(0) instanceof Long l)) {
            return def;
        }
        return l;
    }

    static Map<Integer, List<Object>> message(Map<Integer, List<Object>> m, int field) {
        byte[] b = bytes(m, field);
        return b == null ? null : decode(b);
    }

    /** A google.protobuf.StringValue wrapper ({@code value = 1}): message field → its inner string. */
    static String wrappedString(Map<Integer, List<Object>> m, int field) {
        Map<Integer, List<Object>> wrap = message(m, field);
        return wrap == null ? null : string(wrap, 1);
    }

    /** Every length-delimited value of a repeated field, decoded as a sub-message. */
    static List<Map<Integer, List<Object>>> repeatedMessages(Map<Integer, List<Object>> m, int field) {
        List<Map<Integer, List<Object>>> out = new ArrayList<>();
        List<Object> v = m.get(field);
        if (v != null) {
            for (Object o : v) {
                if (o instanceof byte[] b) {
                    out.add(decode(b));
                }
            }
        }
        return out;
    }

    /** Every length-delimited value of a repeated string field. */
    static List<String> repeatedStrings(Map<Integer, List<Object>> m, int field) {
        List<String> out = new ArrayList<>();
        List<Object> v = m.get(field);
        if (v != null) {
            for (Object o : v) {
                if (o instanceof byte[] b) {
                    out.add(new String(b, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    // ---- Encoding (request messages) --------------------------------------------------------------

    /** A tiny protobuf message builder. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer string(int field, String value) {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            return lenDelimited(field, b);
        }

        Writer varint(int field, long value) {
            writeTag(field, 0);
            writeVarint(value);
            return this;
        }

        Writer message(int field, Writer sub) {
            return lenDelimited(field, sub.toBytes());
        }

        private Writer lenDelimited(int field, byte[] b) {
            writeTag(field, 2);
            writeVarint(b.length);
            out.writeBytes(b);
            return this;
        }

        private void writeTag(int field, int wire) {
            writeVarint(((long) field << 3) | wire);
        }

        private void writeVarint(long v) {
            while ((v & ~0x7fL) != 0) {
                out.write((int) ((v & 0x7f) | 0x80));
                v >>>= 7;
            }
            out.write((int) (v & 0x7f));
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}
