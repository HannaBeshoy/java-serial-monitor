package io.github.hannabeshoy.serialmonitor.stats;

import io.github.hannabeshoy.serialmonitor.sensor.LightSensor;
import io.github.hannabeshoy.serialmonitor.sensor.Lm35TemperatureSensor;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.sensor.Sensor;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AlarmHistoryTest {

    private static AlarmTracker.Alarm alarm(String key, double value, double limit, long seq, boolean raised) {
        Reading r = new Reading(key, key, value, "u", seq, Instant.EPOCH.plusSeconds(seq), true);
        return new AlarmTracker.Alarm(new AlarmRule(key, AlarmRule.Op.ABOVE, limit), r, raised);
    }

    private static AlarmHistory sample() {
        AlarmHistory h = new AlarmHistory();
        h.onAlarm(alarm("T", 33, 30, 5, true));     // 10 % beyond
        h.onAlarm(alarm("D", 150, 100, 2, true));   // 50 % beyond
        h.onAlarm(alarm("T", 33, 30, 1, true));     // 10 % beyond, earlier
        h.onAlarm(alarm("T", 29, 30, 9, false));    // a clear event: not kept
        h.onAlarm(alarm("L", 66, 60, 1, true));     // 10 % beyond, same time as the T alarm
        return h;
    }

    @Test
    public void naturalOrderIsSeverityThenTimeThenKey() {
        List<AlarmHistory.Entry> s = sample().sorted();
        assertEquals(4, s.size());
        assertEquals("D", s.get(0).key());                       // most severe
        assertEquals("L", s.get(1).key());                       // tie on severity and time: key decides
        assertEquals(1, s.get(1).seq());
        assertEquals("T", s.get(2).key());
        assertEquals(1, s.get(2).seq());
        assertEquals(5, s.get(3).seq());                         // same severity, later
    }

    @Test
    public void comparatorGivesASecondOrder() {
        List<AlarmHistory.Entry> s = sample().byTime();
        long[] seqs = s.stream().mapToLong(AlarmHistory.Entry::seq).toArray();
        assertEquals(1, seqs[0]);
        assertEquals(1, seqs[1]);
        assertEquals(2, seqs[2]);
        assertEquals(5, seqs[3]);
        assertEquals("L", s.get(0).key());
    }

    @Test
    public void compareToIsConsistent() {
        List<AlarmHistory.Entry> s = sample().sorted();
        for (AlarmHistory.Entry a : s) {
            assertEquals(0, a.compareTo(a));
            for (AlarmHistory.Entry b : s) {
                assertEquals(Integer.signum(a.compareTo(b)), -Integer.signum(b.compareTo(a)));
                if (a.compareTo(b) == 0) assertEquals(a, b);     // consistent with equals
            }
        }
        List<AlarmHistory.Entry> shuffled = new ArrayList<>(s);
        Collections.shuffle(shuffled, new java.util.Random(1));
        Collections.sort(shuffled);
        assertEquals(s, shuffled);
    }

    @Test
    public void reportListsTheWorstAlarmFirst() {
        String report = sample().report();
        assertTrue(report.startsWith("  D = 150.0 (limit 100.0, 50 % beyond)"));
        assertEquals(4, report.lines().count());
    }

    @Test
    public void sensorsAreEqualByKeyUnitAndRange() {
        Sensor a = new Lm35TemperatureSensor("T", 5.0);
        Sensor b = new Lm35TemperatureSensor("T", 5.0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Lm35TemperatureSensor("X", 5.0));  // another key
        assertNotEquals(a, new Lm35TemperatureSensor("T", 3.3));  // another reference voltage
        assertNotEquals(a, new LightSensor("T"));                 // another class
        assertNotEquals(a, null);
        Set<Sensor> set = new HashSet<>(List.of(a, b, new LightSensor("L")));
        assertEquals(2, set.size());                              // the duplicate is dropped
    }
}
