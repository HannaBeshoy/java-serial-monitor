package io.github.hannabeshoy.serialmonitor.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses and formats the line protocol of the sensor node (see docs/PROTOCOL.md).
 *
 * <pre>
 *   $SN,42,123456,T=61,D=2001,L=512*63
 *    |  |  |      |                  |
 *    |  |  |      |                  XOR checksum of every character between '$' and '*', 2 hex digits
 *    |  |  |      raw sensor values, KEY=integer
 *    |  |  uptime in ms
 *    |  sequence number
 *    node name
 * </pre>
 *
 * The class is stateless, so it is thread-safe and all methods are static.
 */
public final class FrameCodec {

    /** Longest line accepted; anything longer is rejected before parsing (protects against a stuck port). */
    public static final int MAX_LINE = 200;

    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9]{0,7}");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private FrameCodec() {
    }

    /** XOR of all characters, as two upper-case hex digits (the NMEA 0183 checksum). */
    public static String checksum(CharSequence body) {
        int x = 0;
        for (int i = 0; i < body.length(); i++) x ^= body.charAt(i);
        return String.format("%02X", x & 0xFF);
    }

    /** Builds the line for a frame, without the line ending. */
    public static String format(Frame f) {
        StringBuilder body = new StringBuilder();
        body.append(f.node()).append(',').append(f.seq()).append(',').append(f.uptimeMs());
        f.raw().forEach((k, v) -> body.append(',').append(k).append('=').append(v));
        return "$" + body + "*" + checksum(body);
    }

    /**
     * Parses one line.
     *
     * @param line the received text; surrounding spaces and a trailing CR are ignored
     * @return the frame
     * @throws FrameException if the line is not a valid frame; {@link FrameException#getKind()} says why
     */
    public static Frame parse(String line) throws FrameException {
        if (line == null) throw new FrameException(FrameException.Kind.SYNTAX, "null line");
        String s = line.strip();
        if (s.isEmpty()) throw new FrameException(FrameException.Kind.SYNTAX, "empty line");
        if (s.length() > MAX_LINE) throw new FrameException(FrameException.Kind.SYNTAX, "line longer than " + MAX_LINE);
        if (s.charAt(0) != '$') throw new FrameException(FrameException.Kind.SYNTAX, "missing '$' at the start");
        int star = s.lastIndexOf('*');
        if (star < 0 || star != s.length() - 3)
            throw new FrameException(FrameException.Kind.SYNTAX, "missing '*XX' checksum at the end");

        String body = s.substring(1, star);
        String sent = s.substring(star + 1).toUpperCase();
        String calc = checksum(body);
        if (!sent.equals(calc))
            throw new FrameException(FrameException.Kind.CHECKSUM, "checksum " + sent + " but computed " + calc);

        String[] parts = body.split(",", -1);
        if (parts.length < 4)
            throw new FrameException(FrameException.Kind.SYNTAX, "expected node, seq, uptime and at least one value");
        if (!NODE.matcher(parts[0]).matches())
            throw new FrameException(FrameException.Kind.FIELD, "bad node name '" + parts[0] + "'");
        long seq = number(parts[1], "seq");
        long uptime = number(parts[2], "uptime");

        Map<String, Long> raw = new LinkedHashMap<>();
        for (int i = 3; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) throw new FrameException(FrameException.Kind.FIELD, "field '" + parts[i] + "' is not KEY=value");
            String key = parts[i].substring(0, eq);
            if (!KEY.matcher(key).matches())
                throw new FrameException(FrameException.Kind.FIELD, "bad key '" + key + "'");
            if (raw.put(key, number(parts[i].substring(eq + 1), key)) != null)
                throw new FrameException(FrameException.Kind.FIELD, "key '" + key + "' appears twice");
        }
        if (seq < 0 || uptime < 0) throw new FrameException(FrameException.Kind.FIELD, "negative seq or uptime");
        return new Frame(parts[0], seq, uptime, raw);
    }

    private static long number(String text, String what) throws FrameException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new FrameException(FrameException.Kind.FIELD, what + " is not a whole number: '" + text + "'", e);
        }
    }
}
