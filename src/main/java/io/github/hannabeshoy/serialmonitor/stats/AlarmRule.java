package io.github.hannabeshoy.serialmonitor.stats;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Raise an alarm when sensor KEY goes above (or below) LIMIT", for example {@code T>30} or {@code D<10}.
 *
 * @param key   sensor key
 * @param op    the comparison
 * @param limit the threshold, in the sensor's unit
 */
public record AlarmRule(String key, Op op, double limit) {

    /** The comparison; each constant has its own body for {@link #holds} (a constant-specific method). */
    public enum Op {
        ABOVE(">") {
            @Override
            boolean holds(double value, double limit) { return value > limit; }
        },
        BELOW("<") {
            @Override
            boolean holds(double value, double limit) { return value < limit; }
        };

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        abstract boolean holds(double value, double limit);

        public String symbol() {
            return symbol;
        }
    }

    private static final Pattern RULE = Pattern.compile("\\s*([A-Z][A-Z0-9]*)\\s*([<>])\\s*(-?\\d+(?:\\.\\d+)?)\\s*");

    /** True when the value is in the alarm zone. */
    public boolean triggeredBy(double value) {
        return !Double.isNaN(value) && op.holds(value, limit);
    }

    /** Parses text like "T>30" or "D<10.5". */
    public static AlarmRule parse(String text) {
        Matcher m = RULE.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("alarm rule must look like T>30 or D<10, got: " + text);
        return new AlarmRule(m.group(1), m.group(2).equals(">") ? Op.ABOVE : Op.BELOW, Double.parseDouble(m.group(3)));
    }

    @Override
    public String toString() {
        return key + op.symbol + (limit == Math.rint(limit) ? String.valueOf((long) limit) : String.valueOf(limit));
    }
}
