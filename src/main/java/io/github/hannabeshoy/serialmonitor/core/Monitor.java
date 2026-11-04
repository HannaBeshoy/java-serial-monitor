package io.github.hannabeshoy.serialmonitor.core;

import io.github.hannabeshoy.serialmonitor.protocol.Frame;
import io.github.hannabeshoy.serialmonitor.protocol.FrameCodec;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.sensor.Sensor;
import io.github.hannabeshoy.serialmonitor.sensor.SensorRegistry;
import io.github.hannabeshoy.serialmonitor.source.DataSource;
import io.github.hannabeshoy.serialmonitor.stats.AlarmRule;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker;
import io.github.hannabeshoy.serialmonitor.stats.RollingStats;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Reads a {@link DataSource} on a background thread and turns its lines into events.
 *
 * <pre>
 *   reader thread                 queue (bounded)             dispatcher thread
 *   source.readLine()  ── parse ──▶ [frame][bad][frame] ──▶ convert ▶ stats ▶ alarms ▶ listeners
 * </pre>
 *
 * Two threads keep the serial port drained even when a listener (CSV file, GUI) is slow:
 * the reader never blocks on the queue; if the queue is full the frame is counted as dropped.
 * This is the producer-consumer pattern with a {@link BlockingQueue} as the buffer.
 */
public class Monitor {

    /** What travels through the queue. Sealed: the dispatcher's switch covers every case. */
    private sealed interface Event permits FrameEvent, BadLineEvent, EndEvent { }
    /** {@code droppedBefore}: frames the reader had to drop just before this one, so they are not reported as lost. */
    private record FrameEvent(Frame frame, Instant time, long droppedBefore) implements Event { }
    private record BadLineEvent(String line, FrameException error) implements Event { }
    private record EndEvent(String error) implements Event { }

    public static final int DEFAULT_QUEUE = 1024;
    public static final int DEFAULT_WINDOW = 120;

    private final DataSource source;
    private final SensorRegistry sensors;
    private final AlarmTracker alarms;
    private final int window;
    private final BlockingQueue<Event> queue;
    private final List<MonitorListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, RollingStats> stats = new ConcurrentHashMap<>();
    private final java.util.Set<MonitorListener> failed = ConcurrentHashMap.newKeySet();

    private final AtomicLong lines = new AtomicLong();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bad = new AtomicLong();
    private final AtomicLong checksum = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    private volatile String error = null;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private Thread reader;
    private Thread dispatcher;
    private long lastSeq = -1;                 // dispatcher thread only

    public Monitor(DataSource source, SensorRegistry sensors, List<AlarmRule> rules) {
        this(source, sensors, rules, DEFAULT_WINDOW, DEFAULT_QUEUE);
    }

    public Monitor(DataSource source, SensorRegistry sensors, List<AlarmRule> rules, int window, int queueCapacity) {
        this.source = source;
        this.sensors = sensors;
        this.alarms = new AlarmTracker(rules, 0.5);
        this.window = window;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    public void addListener(MonitorListener l) {
        listeners.add(l);
    }

    /** Starts both threads. A monitor can be started only once. */
    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("already started");
        reader = new Thread(this::readLoop, "monitor-reader");
        dispatcher = new Thread(this::dispatchLoop, "monitor-dispatcher");
        reader.setDaemon(true);
        dispatcher.setDaemon(true);
        dispatcher.start();
        reader.start();
    }

    /** Asks both threads to finish and closes the source. Returns at once; use {@link #await} to wait. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        try {
            source.close();                     // makes a blocked readLine() return
        } catch (IOException e) {
            error = "close failed: " + e.getMessage();
        }
        if (reader != null) reader.interrupt();
    }

    /** Waits until the dispatcher has delivered {@code onStopped}. */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return finished.await(timeout, unit);
    }

    public boolean isRunning() {
        return started.get() && finished.getCount() > 0;
    }

    // ---------------------------------------------------------------- producer

    private void readLoop() {
        String endError = null;
        boolean live = source.isLive();
        long pendingDrops = 0;                                       // frames dropped since the last queued one
        try {
            String line;
            while (!stopping.get() && (line = source.readLine()) != null) {
                lines.incrementAndGet();
                Event e;
                boolean isFrame;
                try {
                    e = new FrameEvent(FrameCodec.parse(line), Instant.now(), pendingDrops);
                    isFrame = true;
                } catch (FrameException ex) {
                    e = new BadLineEvent(line, ex);
                    isFrame = false;
                }
                if (!live) {
                    queue.put(e);                                    // a file can wait: back-pressure
                    if (isFrame) pendingDrops = 0;
                } else if (queue.offer(e)) {
                    if (isFrame) pendingDrops = 0;
                } else {                                             // a live port must never wait: drop
                    dropped.incrementAndGet();
                    if (isFrame) pendingDrops++;
                }
            }
        } catch (IOException ex) {
            if (!stopping.get()) endError = ex.getMessage();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();                     // stop() during put(): just end
        } catch (RuntimeException ex) {
            endError = "reader crashed: " + ex;
        }
        try {
            queue.put(new EndEvent(endError));                       // must arrive, so wait for space
        } catch (InterruptedException ex) {
            queue.clear();                                           // stop() while full: make room
            queue.offer(new EndEvent(endError));
        }
    }

    // ---------------------------------------------------------------- consumer

    private void dispatchLoop() {
        try {
            while (true) {
                Event e = queue.take();
                switch (e) {
                    case FrameEvent fe -> handleFrame(fe.frame(), fe.time(), fe.droppedBefore());
                    case BadLineEvent be -> handleBad(be.line(), be.error());
                    case EndEvent end -> {
                        error = end.error() != null ? end.error() : error;
                        MonitorStats s = snapshot();
                        fire(l -> l.onStopped(s));
                        return;
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            finished.countDown();
        }
    }

    private void handleFrame(Frame f, Instant time, long droppedBefore) {
        frames.incrementAndGet();
        if (lastSeq >= 0 && f.seq() > lastSeq + 1) {
            long missing = f.seq() - lastSeq - 1 - droppedBefore;   // gaps we caused ourselves are not "lost"
            long after = lastSeq;
            if (missing > 0) {
                lost.addAndGet(missing);
                fire(l -> l.onLostFrames(after, missing));
            }
        }
        lastSeq = f.seq();                                           // a smaller seq means the node restarted
        fire(l -> l.onFrame(f));
        f.raw().forEach((key, raw) -> {
            Optional<Sensor> s = sensors.get(key);
            if (s.isEmpty()) {
                unknown.incrementAndGet();
                return;
            }
            Reading r = s.get().read(raw, f.seq(), time);
            if (r.valid()) stats.computeIfAbsent(key, k -> new RollingStats(window)).add(r.value());
            fire(l -> l.onReading(r));
            for (AlarmTracker.Alarm a : alarms.update(r)) fire(l -> l.onAlarm(a));
        });
    }

    private void handleBad(String line, FrameException ex) {
        bad.incrementAndGet();
        if (ex.getKind() == FrameException.Kind.CHECKSUM) checksum.incrementAndGet();
        fire(l -> l.onBadLine(line, ex));
    }

    /** Calls every listener; one that throws must not stop the others or the monitor. */
    private void fire(Consumer<MonitorListener> call) {
        for (MonitorListener l : listeners) {
            try {
                call.accept(l);
            } catch (RuntimeException ex) {
                if (failed.add(l))                                   // report each listener only once
                    System.err.println("listener " + l.getClass().getName() + " failed: " + ex + " (further errors hidden)");
            }
        }
    }

    // ---------------------------------------------------------------- queries (any thread)

    public MonitorStats snapshot() {
        return new MonitorStats(lines.get(), frames.get(), bad.get(), checksum.get(), lost.get(), dropped.get(), unknown.get(), error);
    }

    /** Current statistics per sensor key, sorted by key. */
    public Map<String, RollingStats.Summary> statistics() {
        Map<String, RollingStats.Summary> out = new TreeMap<>();
        stats.forEach((k, v) -> out.put(k, v.summary()));
        return out;
    }

    public SensorRegistry sensors() {
        return sensors;
    }

    public List<AlarmRule> rules() {
        return alarms.rules();
    }

    public String describeSource() {
        return source.describe();
    }
}
