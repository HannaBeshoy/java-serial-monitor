package io.github.hannabeshoy.serialmonitor.session;

import io.github.hannabeshoy.serialmonitor.core.MonitorListener;
import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.source.DataSource;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one monitoring run received: its label, where the lines came from, when it started,
 * every reading and the final counters. As a {@link MonitorListener} it records a run while the
 * monitor is going; afterwards it can be sorted, exported as text with a {@link PrintWriter} and
 * saved or loaded with Java serialization.
 *
 * <p>Serialization writes the whole object graph, so every object a session holds must be
 * {@link Serializable} too: the {@link Reading}s and the {@link MonitorStats} are, and so are
 * {@link String}, {@link Instant} and {@link ArrayList}. The live {@link DataSource} is not (a
 * serial port is an operating-system handle), so it is {@code transient}: it is skipped when
 * saving and is empty after loading. Only its description is kept.
 *
 * <p>The three sort methods show the three ways of writing a {@link Comparator}: a separate
 * class ({@link ReadingComparatorByValue}), a private inner class and an anonymous class.
 * {@link #sensorNames()} adds the modern form, a lambda.
 */
public final class Session implements SortableReadings, MonitorListener, Serializable {

    /** Change this when the saved form changes, so old files are rejected instead of read wrongly. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Longest allowed label. */
    public static final int MAX_LABEL_LENGTH = 100;

    /** Only these classes may come out of a session file; anything else is refused before it is created. */
    private static final ObjectInputFilter ONLY_SESSION_CLASSES = ObjectInputFilter.Config.createFilter(
            "maxdepth=10;maxarray=10000000;"
                    + "io.github.hannabeshoy.serialmonitor.session.Session;"
                    + "io.github.hannabeshoy.serialmonitor.sensor.Reading;"
                    + "io.github.hannabeshoy.serialmonitor.core.MonitorStats;"
                    + "java.util.ArrayList;java.time.Ser;java.time.Instant;java.lang.*;!*");

    private String label;
    private final String sourceDescription;
    private final Instant started;
    private final ArrayList<Reading> readings = new ArrayList<>();
    private MonitorStats stats;                 // null until the run has ended
    private transient DataSource source;        // not saved: a device handle means nothing in a file

    /**
     * A session of a source known only by its description (a loaded file, a test).
     *
     * @throws IllegalArgumentException if the label is empty or longer than {@value #MAX_LABEL_LENGTH} characters
     */
    public Session(String label, String sourceDescription, Instant started) {
        setLabel(label);                        // the check lives in one place: the setter
        this.sourceDescription = Objects.requireNonNull(sourceDescription);
        this.started = Objects.requireNonNull(started);
    }

    /** A session of a live source, starting now. */
    public Session(String label, DataSource source) {
        this(label, source.describe(), Instant.now());   // this(...) must be the first statement
        this.source = source;
    }

    // ---------------------------------------------------------------- label, with a validating setter

    public synchronized String label() {
        return label;
    }

    /**
     * Renames the session.
     *
     * @throws IllegalArgumentException if the label is null, blank or longer than {@value #MAX_LABEL_LENGTH} characters
     */
    public synchronized void setLabel(String label) {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("the label must not be empty");
        if (label.length() > MAX_LABEL_LENGTH)
            throw new IllegalArgumentException("the label has " + label.length() + " characters, at most "
                    + MAX_LABEL_LENGTH + " are allowed");
        this.label = label;
    }

    // ---------------------------------------------------------------- recording

    public String sourceDescription() {
        return sourceDescription;
    }

    public Instant started() {
        return started;
    }

    /** The live source, while the program that recorded the session is running; empty after loading. */
    public synchronized Optional<DataSource> source() {
        return Optional.ofNullable(source);
    }

    public synchronized void add(Reading r) {
        readings.add(Objects.requireNonNull(r));
    }

    /** Stores the final counters; called once when the run ends. */
    public synchronized void finish(MonitorStats s) {
        this.stats = Objects.requireNonNull(s);
    }

    @Override
    public void onReading(Reading r) {
        add(r);
    }

    @Override
    public void onStopped(MonitorStats s) {
        finish(s);
    }

    /** A copy of the readings in their current order. */
    public synchronized List<Reading> readings() {
        return List.copyOf(readings);
    }

    /** The final counters, or empty while the run is still going. */
    public synchronized Optional<MonitorStats> stats() {
        return Optional.ofNullable(stats);
    }

    // ---------------------------------------------------------------- sorting: three Comparator forms

    /** Form 2: a private inner class. It needs no fields of the session, but lives where it is used. */
    private class ArrivalOrder implements Comparator<Reading> {
        @Override
        public int compare(Reading a, Reading b) {
            int c = Long.compare(a.seq(), b.seq());
            return c != 0 ? c : a.key().compareTo(b.key());
        }
    }

    @Override
    public synchronized void sortByTime() {
        readings.sort(new ArrivalOrder());
    }

    /** Form 1: the separate class, behind a key order so °C, cm and % are never mixed. */
    @Override
    public synchronized void sortByValue() {
        readings.sort(Comparator.comparing(Reading::key).thenComparing(new ReadingComparatorByValue()));
    }

    /** Form 3: an anonymous class, written at the one place it is needed. */
    @Override
    public synchronized void sortBySensorName() {
        readings.sort(new Comparator<Reading>() {
            @Override
            public int compare(Reading a, Reading b) {
                int c = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
                return c != 0 ? c : Long.compare(a.seq(), b.seq());
            }
        });
    }

    /** The highest valid reading of one sensor, or empty if it has none. */
    public synchronized Optional<Reading> highest(String key) {
        return readings.stream()
                .filter(r -> r.key().equals(key) && r.valid())
                .min(new ReadingComparatorByValue());           // "smallest" in a descending order = highest
    }

    /** Form 4, the modern one: a lambda. Each sensor that sent readings, as key to name, sorted by name. */
    public synchronized Map<String, String> sensorNames() {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Reading r : readings) byKey.putIfAbsent(r.key(), r.name());
        List<Map.Entry<String, String>> entries = new ArrayList<>(byKey.entrySet());
        entries.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        Map<String, String> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    // ---------------------------------------------------------------- text export with PrintWriter

    /**
     * Writes a readable report: the header, the summary, the highest value of each sensor and every
     * reading in the session's current order (sort first to choose the order). Numbers always use
     * a decimal point, whatever the computer's language, so the file reads the same everywhere.
     */
    public synchronized void writeTo(PrintWriter w) {
        w.println("session: " + label);
        w.println("source: " + sourceDescription);
        w.println("started: " + started);
        w.println("summary: " + (stats == null ? "not finished" : stats.summary()));
        w.println("readings: " + readings.size());
        Map<String, String> sensors = sensorNames();
        if (!sensors.isEmpty()) {
            w.println();
            w.println("highest value per sensor:");
            sensors.forEach((key, name) -> {
                Optional<Reading> top = highest(key);
                if (top.isPresent()) {
                    Reading r = top.get();
                    w.printf(Locale.ROOT, "  %-20s %s at frame #%d%n", name, value(r), r.seq());
                } else {
                    w.printf(Locale.ROOT, "  %-20s no valid reading%n", name);
                }
            });
        }
        w.println();
        w.println("readings:");
        for (Reading r : readings) w.printf(Locale.ROOT, "  #%-6d %-2s %s%n", r.seq(), r.key(), value(r));
    }

    /** Writes {@link #writeTo(PrintWriter) the report} to a UTF-8 text file, replacing it. */
    public void exportText(Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(file.toFile(), StandardCharsets.UTF_8)) {
            writeTo(w);
            if (w.checkError()) throw new IOException("writing " + file + " failed");   // PrintWriter never throws
        }
    }

    private static String value(Reading r) {
        return r.valid() ? String.format(Locale.ROOT, "%.1f %s", r.value(), r.unit()) : "invalid";
    }

    // ---------------------------------------------------------------- saving and loading

    /** Saves the session with an {@link ObjectOutputStream}, replacing the file. An instance method: it saves this object. */
    public void save(Path file) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
            synchronized (this) {
                out.writeObject(this);
            }
        }
    }

    /**
     * Reads a session saved by {@link #save(Path)}. A static method: there is no session object
     * yet, the method makes one. The result is a new object, equal to the saved one but not the
     * same ({@code ==} is false), and without a live source.
     *
     * @throws IOException if the file cannot be read, does not hold a session, or was saved by an incompatible version
     */
    public static Session load(Path file) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            in.setObjectInputFilter(ONLY_SESSION_CLASSES);   // never build objects of unexpected classes from a file
            Object o = in.readObject();
            if (o instanceof Session s) return s;
            throw new IOException(file + " does not hold a session");
        } catch (ClassNotFoundException e) {
            throw new IOException(file + " holds a class this program does not know", e);
        }
    }

    // ---------------------------------------------------------------- equality

    /** Equal when label, source description, start, readings (in order) and counters are equal. The live source is left out. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Session other)) return false;
        Snapshot a = snapshot(), b = other.snapshot();
        return a.equals(b);
    }

    @Override
    public int hashCode() {
        return snapshot().hashCode();
    }

    @Override
    public String toString() {
        Snapshot s = snapshot();
        return "Session[" + s.label() + ", " + s.source() + ", " + s.readings().size() + " readings]";
    }

    /** The compared fields, read under the lock, so equals never holds two locks at once. */
    private record Snapshot(String label, String source, Instant started, List<Reading> readings, MonitorStats stats) { }

    private synchronized Snapshot snapshot() {
        return new Snapshot(label, sourceDescription, started, List.copyOf(readings), stats);
    }
}
