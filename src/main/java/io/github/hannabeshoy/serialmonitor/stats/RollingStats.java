package io.github.hannabeshoy.serialmonitor.stats;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.DoubleSummaryStatistics;

/**
 * Statistics over the last {@code window} values of one sensor.
 * Written by the monitor thread and read by the GUI thread, so every method is synchronized
 * (the object is a monitor in the textbook sense).
 */
public class RollingStats {

    /** An immutable snapshot, safe to hand to another thread. */
    public record Summary(long count, double min, double max, double mean, double stddev, double last) {
        public static final Summary EMPTY = new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private final int window;
    private final Deque<Double> values = new ArrayDeque<>();
    private long total = 0;            // all values ever added, not only those in the window

    public RollingStats(int window) {
        if (window < 1) throw new IllegalArgumentException("window must be at least 1");
        this.window = window;
    }

    public synchronized void add(double v) {
        if (Double.isNaN(v)) return;
        values.addLast(v);
        if (values.size() > window) values.removeFirst();
        total++;
    }

    public synchronized Summary summary() {
        if (values.isEmpty()) return Summary.EMPTY;
        DoubleSummaryStatistics s = values.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        double mean = s.getAverage();
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
        return new Summary(total, s.getMin(), s.getMax(), mean, Math.sqrt(var), values.peekLast());
    }

    public int window() {
        return window;
    }
}
