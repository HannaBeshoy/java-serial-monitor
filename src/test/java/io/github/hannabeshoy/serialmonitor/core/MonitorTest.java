package io.github.hannabeshoy.serialmonitor.core;

import io.github.hannabeshoy.serialmonitor.protocol.Frame;
import io.github.hannabeshoy.serialmonitor.protocol.FrameCodec;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.sensor.SensorRegistry;
import io.github.hannabeshoy.serialmonitor.source.DataSource;
import io.github.hannabeshoy.serialmonitor.source.ReplaySource;
import io.github.hannabeshoy.serialmonitor.source.SimulatedSource;
import io.github.hannabeshoy.serialmonitor.stats.AlarmRule;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker;
import io.github.hannabeshoy.serialmonitor.stats.RollingStats;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MonitorTest {

    /** Records every event; the lists are synchronized because the dispatcher thread writes them. */
    static class Recorder implements MonitorListener {
        final List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
        final List<Reading> readings = Collections.synchronizedList(new ArrayList<>());
        final List<FrameException> bad = Collections.synchronizedList(new ArrayList<>());
        final List<long[]> lost = Collections.synchronizedList(new ArrayList<>());
        final List<AlarmTracker.Alarm> alarms = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger stopped = new AtomicInteger();
        volatile MonitorStats last;

        @Override public void onFrame(Frame f) { frames.add(f); }
        @Override public void onReading(Reading r) { readings.add(r); }
        @Override public void onBadLine(String line, FrameException e) { bad.add(e); }
        @Override public void onLostFrames(long after, long missing) { lost.add(new long[]{after, missing}); }
        @Override public void onAlarm(AlarmTracker.Alarm a) { alarms.add(a); }
        @Override public void onStopped(MonitorStats s) { last = s; stopped.incrementAndGet(); }
    }

    private static Recorder run(DataSource src, List<AlarmRule> rules) throws InterruptedException {
        Monitor m = new Monitor(src, SensorRegistry.standard(), rules, 50, 16);
        Recorder rec = new Recorder();
        m.addListener(rec);
        m.start();
        assertTrue("monitor did not finish", m.await(10, TimeUnit.SECONDS));
        return rec;
    }

    private static String line(long seq, long t, long d, long l) {
        Map<String, Long> raw = new LinkedHashMap<>();
        raw.put("T", t);
        raw.put("D", d);
        raw.put("L", l);
        return FrameCodec.format(new Frame("SN", seq, seq * 250, raw));
    }

    @Test
    public void everyLineIsCountedExactlyOnce() throws InterruptedException {
        Recorder r = run(new SimulatedSource(5, 3000, 0, SimulatedSource.Faults.TYPICAL), List.of());
        MonitorStats s = r.last;
        assertEquals(1, r.stopped.get());
        assertEquals(3000, s.lines());
        assertEquals(s.lines(), s.frames() + s.bad() + s.dropped());
        assertEquals("a file-like source is never dropped", 0, s.dropped());
        assertEquals(s.frames(), r.frames.size());
        assertEquals(s.bad(), r.bad.size());
        assertEquals(3 * s.frames(), r.readings.size());
        assertTrue(s.checksum() > 0 && s.lost() > 0);
        assertNull(s.error());
    }

    @Test
    public void lostFramesAreDetectedFromSequenceNumbers() throws InterruptedException {
        String text = String.join("\n", line(0, 61, 2000, 500), line(1, 61, 2000, 500), line(4, 61, 2000, 500),
                "not a frame", line(5, 61, 2000, 500), line(0, 61, 2000, 500));   // last one: node restarted
        Recorder r = run(ReplaySource.ofText(text), List.of());
        assertEquals(5, r.last.frames());
        assertEquals(1, r.last.bad());
        assertEquals(2, r.last.lost());
        assertEquals(1, r.lost.size());
        assertEquals(1, r.lost.get(0)[0]);          // after seq 1
        assertEquals(2, r.lost.get(0)[1]);          // seq 2 and 3 missing
    }

    @Test
    public void statisticsAndAlarms() throws InterruptedException {
        // 61 counts = 29.8 °C, 72 counts = 35.2 °C, then back to 61
        String text = String.join("\n", line(0, 61, 2000, 500), line(1, 72, 2000, 500), line(2, 72, 0, 500), line(3, 61, 2000, 500));
        Monitor m = new Monitor(ReplaySource.ofText(text), SensorRegistry.standard(), List.of(AlarmRule.parse("T>32")), 50, 16);
        Recorder r = new Recorder();
        m.addListener(r);
        m.start();
        assertTrue(m.await(5, TimeUnit.SECONDS));
        assertEquals(2, r.alarms.size());
        assertTrue(r.alarms.get(0).raised());
        assertEquals(1, r.alarms.get(0).reading().seq());
        assertFalse(r.alarms.get(1).raised());
        RollingStats.Summary t = m.statistics().get("T");
        assertEquals(4, t.count());
        assertEquals(29.8, t.min(), 0.05);
        assertEquals(35.2, t.max(), 0.05);
        assertEquals("the timeout (D=0) is not part of the statistics", 3, m.statistics().get("D").count());
    }

    @Test
    public void unknownKeysAreCountedNotFatal() throws InterruptedException {
        Recorder r = run(ReplaySource.ofText(FrameCodec.format(new Frame("SN", 0, 0, Map.of("Q", 1L, "T", 61L)))), List.of());
        assertEquals(1, r.last.unknown());
        assertEquals(1, r.readings.size());
    }

    @Test
    public void stopEndsAnEndlessSourceQuickly() throws InterruptedException {
        Monitor m = new Monitor(new SimulatedSource(1, -1, 5, SimulatedSource.Faults.NONE), SensorRegistry.standard(), List.of());
        Recorder r = new Recorder();
        m.addListener(r);
        m.start();
        Thread.sleep(200);
        assertTrue(m.isRunning());
        long t0 = System.nanoTime();
        m.stop();
        assertTrue(m.await(2, TimeUnit.SECONDS));
        assertTrue("stop took too long", System.nanoTime() - t0 < 1_000_000_000L);
        assertFalse(m.isRunning());
        assertEquals(1, r.stopped.get());
        assertTrue(r.last.frames() > 5);
        m.stop();                                     // a second stop is harmless
        assertThrows(IllegalStateException.class, m::start);
    }

    @Test
    public void aSlowListenerMakesALiveSourceDropNotBlock() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        DataSource live = new DataSource() {          // a fast "port" that never waits
            long n = 0;
            @Override public String readLine() { return n < 500 ? FrameCodec.format(new Frame("SN", n++, 0, Map.of("T", 61L))) : null; }
            @Override public String describe() { return "fast live test port"; }
            @Override public void close() { }
        };
        Monitor m = new Monitor(live, SensorRegistry.standard(), List.of(), 10, 8);
        Recorder r = new Recorder();
        m.addListener(new MonitorListener() {
            @Override public void onFrame(Frame f) {
                try {
                    release.await(); // blocks the dispatcher until the reader has finished
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        m.addListener(r);
        m.start();
        Thread.sleep(300);                            // the reader has read all 500 lines meanwhile
        release.countDown();
        assertTrue(m.await(5, TimeUnit.SECONDS));
        MonitorStats s = r.last;
        assertEquals(500, s.lines());
        assertTrue("frames must have been dropped", s.dropped() > 400);
        assertEquals(500, s.frames() + s.dropped());
        assertEquals("our own drops are not reported as lost", 0, s.lost());
    }

    @Test
    public void aFailingListenerDoesNotStopTheOthers() throws InterruptedException {
        Monitor m = new Monitor(SimulatedSource.clean(1, 20), SensorRegistry.standard(), List.of());
        m.addListener(new MonitorListener() {
            @Override public void onReading(Reading r) { throw new IllegalStateException("boom"); }
        });
        Recorder r = new Recorder();
        m.addListener(r);
        m.start();
        assertTrue(m.await(5, TimeUnit.SECONDS));
        assertEquals(60, r.readings.size());
    }

    @Test
    public void anIoErrorEndsTheSessionWithTheMessage() throws InterruptedException {
        DataSource broken = new DataSource() {
            int n = 0;
            @Override public String readLine() throws IOException {
                if (n++ < 3) return line(n, 61, 2000, 500);
                throw new IOException("device unplugged");
            }
            @Override public String describe() { return "broken"; }
            @Override public void close() { }
        };
        Recorder r = run(broken, List.of());
        assertNotNull(r.last);
        assertEquals("device unplugged", r.last.error());
        assertEquals(3, r.last.frames());
    }
}
