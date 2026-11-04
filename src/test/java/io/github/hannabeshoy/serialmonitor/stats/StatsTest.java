package io.github.hannabeshoy.serialmonitor.stats;

import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StatsTest {

    private static Reading t(double v) {
        return new Reading("T", "Temperature", v, "°C", 0, Instant.EPOCH, true);
    }

    @Test
    public void rollingWindowKeepsOnlyTheLastValues() {
        RollingStats s = new RollingStats(3);
        assertEquals(0, s.summary().count());
        for (double v : new double[]{100, 1, 2, 3}) s.add(v);
        RollingStats.Summary sum = s.summary();
        assertEquals(4, sum.count());                  // all values seen
        assertEquals(1, sum.min(), 0);                 // 100 left the window
        assertEquals(3, sum.max(), 0);
        assertEquals(2, sum.mean(), 1e-12);
        assertEquals(Math.sqrt(2.0 / 3), sum.stddev(), 1e-12);
        assertEquals(3, sum.last(), 0);
        s.add(Double.NaN);                             // ignored
        assertEquals(4, s.summary().count());
    }

    @Test
    public void rulesParseAndTest() {
        AlarmRule r = AlarmRule.parse(" T > 30 ");
        assertEquals(new AlarmRule("T", AlarmRule.Op.ABOVE, 30), r);
        assertEquals("T>30", r.toString());
        assertTrue(r.triggeredBy(30.1));
        assertFalse(r.triggeredBy(30));
        assertFalse(r.triggeredBy(Double.NaN));
        AlarmRule d = AlarmRule.parse("D<10.5");
        assertEquals("D<10.5", d.toString());
        assertTrue(d.triggeredBy(-3));
        for (String bad : new String[]{"T=30", "t>30", "T>", ">30", "T>>3"})
            assertThrows(bad, IllegalArgumentException.class, () -> AlarmRule.parse(bad));
    }

    @Test
    public void alarmFiresOnceAndClearsWithHysteresis() {
        AlarmTracker tr = new AlarmTracker(List.of(AlarmRule.parse("T>30")), 0.5);
        assertTrue(tr.update(t(29)).isEmpty());
        List<AlarmTracker.Alarm> up = tr.update(t(30.2));
        assertEquals(1, up.size());
        assertTrue(up.get(0).raised());
        assertTrue("still above: no second alarm", tr.update(t(31)).isEmpty());
        assertTrue("back below 30 but inside the hysteresis band", tr.update(t(29.8)).isEmpty());
        List<AlarmTracker.Alarm> down = tr.update(t(29.4));
        assertEquals(1, down.size());
        assertFalse(down.get(0).raised());
        assertEquals(1, tr.update(t(30.5)).size());    // armed again
    }

    @Test
    public void invalidReadingsAndOtherKeysAreIgnored() {
        AlarmTracker tr = new AlarmTracker(List.of(AlarmRule.parse("T>30")), 0.5);
        assertTrue(tr.update(new Reading("T", "T", 99, "°C", 0, Instant.EPOCH, false)).isEmpty());
        assertTrue(tr.update(new Reading("D", "D", 99, "cm", 0, Instant.EPOCH, true)).isEmpty());
    }
}
