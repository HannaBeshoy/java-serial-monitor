package io.github.hannabeshoy.serialmonitor.stats;

import io.github.hannabeshoy.serialmonitor.core.MonitorListener;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker.Alarm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Keeps every raised alarm of a run so that a summary can list them in a useful order.
 *
 * <p>{@link Entry} has a natural order ({@link Comparable}): the most severe alarm first, and
 * for equal severity the earlier one first, then by sensor key. {@code Collections.sort} uses
 * that order. {@link #BY_TIME} is a second order, given as a {@link Comparator}, for the cases
 * where the natural order is not the wanted one.
 */
public final class AlarmHistory implements MonitorListener {

    /** One raised alarm with how far the value was beyond its limit. */
    public record Entry(String key, double value, double limit, double severity, Instant time, long seq)
            implements Comparable<Entry> {

        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(time);
        }

        /** Most severe first; then earliest; then by key, so the order is total and repeatable. */
        @Override
        public int compareTo(Entry other) {
            int c = Double.compare(other.severity, severity);        // descending
            if (c != 0) return c;
            c = time.compareTo(other.time);
            if (c != 0) return c;
            c = Long.compare(seq, other.seq);
            return c != 0 ? c : key.compareTo(other.key);
        }
    }

    /** Oldest first; for equal times the frame sequence number decides. */
    public static final Comparator<Entry> BY_TIME =
            Comparator.comparing(Entry::time).thenComparingLong(Entry::seq).thenComparing(Entry::key);

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public synchronized void onAlarm(Alarm a) {
        if (!a.raised()) return;
        double limit = a.rule().limit();
        double beyond = Math.abs(a.reading().value() - limit);
        double severity = limit == 0 ? beyond : beyond / Math.abs(limit);   // relative, so sensors compare
        entries.add(new Entry(a.rule().key(), a.reading().value(), limit, severity,
                a.reading().time(), a.reading().seq()));
    }

    /** All entries in their natural order: most severe first. */
    public synchronized List<Entry> sorted() {
        List<Entry> copy = new ArrayList<>(entries);
        Collections.sort(copy);
        return copy;
    }

    /** All entries, oldest first. */
    public synchronized List<Entry> byTime() {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(BY_TIME);
        return copy;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** One line per alarm, most severe first; empty when there was none. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : sorted()) {
            sb.append(String.format("  %s = %.1f (limit %.1f, %.0f %% beyond) at frame %d%n",
                    e.key(), e.value(), e.limit(), 100 * e.severity(), e.seq()));
        }
        return sb.toString();
    }
}
