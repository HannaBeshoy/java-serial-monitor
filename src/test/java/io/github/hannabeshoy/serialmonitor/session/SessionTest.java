package io.github.hannabeshoy.serialmonitor.session;

import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.source.DataSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SessionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Instant START = Instant.parse("2026-11-26T10:00:00Z");

    private static Reading t(long seq, double v) {
        return new Reading("T", "Temperature (LM35)", v, "°C", seq, START.plusSeconds(seq), true);
    }

    private static Reading d(long seq, double v) {
        return new Reading("D", "Distance (HC-SR04)", v, "cm", seq, START.plusSeconds(seq), true);
    }

    private static Reading dTimeout(long seq) {
        return new Reading("D", "Distance (HC-SR04)", Double.NaN, "cm", seq, START.plusSeconds(seq), false);
    }

    /** Three frames, added out of order, with one ultrasonic timeout. */
    private static Session sample() {
        Session s = new Session("bench test", "simulation (3 lines)", START);
        s.add(t(2, 24.4));
        s.add(d(2, 31.0));
        s.add(t(0, 23.9));
        s.add(dTimeout(0));
        s.add(t(1, 25.3));
        s.add(d(1, 55.2));
        s.finish(new MonitorStats(3, 3, 0, 0, 0, 0, 0, null));
        return s;
    }

    private static List<String> keysAndSeqs(Session s) {
        List<String> out = new ArrayList<>();
        for (Reading r : s.readings()) out.add(r.key() + r.seq());
        return out;
    }

    // ------------------------------------------------------------------ sorting

    @Test
    public void comparatorByValuePutsHigherFirstInvalidLastAndTiesByFrame() {
        ReadingComparatorByValue c = new ReadingComparatorByValue();
        assertTrue(c.compare(t(0, 30), t(1, 20)) < 0);           // higher value first
        assertTrue(c.compare(t(0, 20), t(1, 30)) > 0);
        assertTrue(c.compare(t(0, 25), t(1, 25)) < 0);           // tie: earlier frame first
        assertEquals(0, c.compare(t(4, 25), t(4, 25)));
        assertTrue(c.compare(dTimeout(0), d(1, 2)) > 0);         // invalid after any valid value
        assertTrue(c.compare(d(1, 2), dTimeout(0)) < 0);
        assertTrue(c.compare(t(0, -40), t(1, 1e300)) > 0);       // no overflow trouble at the extremes
    }

    @Test
    public void sortByTimeUsesFrameThenKey() {
        Session s = sample();
        s.sortByTime();
        assertEquals(List.of("D0", "T0", "D1", "T1", "D2", "T2"), keysAndSeqs(s));
    }

    @Test
    public void sortByValueIsPerSensorHighestFirstWithTimeoutsLast() {
        Session s = sample();
        s.sortByValue();
        assertEquals(List.of("D1", "D2", "D0", "T1", "T2", "T0"), keysAndSeqs(s));
    }

    @Test
    public void sortBySensorNameIgnoresCaseAndKeepsArrivalOrderPerSensor() {
        Session s = sample();
        s.add(new Reading("L", "light (LDR)", 50, "%", 0, START, true));   // lower-case name sorts between D and T
        s.sortBySensorName();
        assertEquals(List.of("D0", "D1", "D2", "L0", "T0", "T1", "T2"), keysAndSeqs(s));
    }

    @Test
    public void highestAndSensorNames() {
        Session s = sample();
        assertEquals(25.3, s.highest("T").orElseThrow().value(), 1e-9);
        assertEquals(55.2, s.highest("D").orElseThrow().value(), 1e-9);
        assertTrue(s.highest("L").isEmpty());
        assertEquals(List.of("D", "T"), new ArrayList<>(s.sensorNames().keySet()));
    }

    // ------------------------------------------------------------------ label

    @Test
    public void labelIsValidatedInTheSetterAndTheConstructor() {
        Session s = sample();
        String max = "x".repeat(Session.MAX_LABEL_LENGTH);
        s.setLabel(max);
        assertEquals(max, s.label());
        assertThrows(IllegalArgumentException.class, () -> s.setLabel(max + "x"));
        assertThrows(IllegalArgumentException.class, () -> s.setLabel("  "));
        assertThrows(IllegalArgumentException.class, () -> s.setLabel(null));
        assertEquals(max, s.label());                              // a rejected label changes nothing
        assertThrows(IllegalArgumentException.class, () -> new Session(max + "x", "x", START));
    }

    // ------------------------------------------------------------------ PrintWriter export

    @Test
    public void exportWritesTheReportInTheCurrentOrder() throws IOException {
        Session s = sample();
        s.sortByTime();
        Path file = tmp.getRoot().toPath().resolve("session.txt");
        s.exportText(file);
        List<String> expected = List.of(
                "session: bench test",
                "source: simulation (3 lines)",
                "started: 2026-11-26T10:00:00Z",
                "summary: " + new MonitorStats(3, 3, 0, 0, 0, 0, 0, null).summary(),
                "readings: 6",
                "",
                "highest value per sensor:",
                "  Distance (HC-SR04)   55.2 cm at frame #1",
                "  Temperature (LM35)   25.3 °C at frame #1",
                "",
                "readings:",
                "  #0      D  invalid",
                "  #0      T  23.9 °C",
                "  #1      D  55.2 cm",
                "  #1      T  25.3 °C",
                "  #2      D  31.0 cm",
                "  #2      T  24.4 °C");
        assertEquals(expected, Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    public void reportOfAnUnfinishedEmptySession() {
        StringWriter buf = new StringWriter();
        new Session("empty", "x", START).writeTo(new PrintWriter(buf));
        String text = buf.toString();
        assertTrue(text, text.contains("summary: not finished"));
        assertTrue(text, text.contains("readings: 0"));
        assertFalse(text, text.contains("highest"));
    }

    // ------------------------------------------------------------------ serialization

    @Test
    public void saveThenLoadGivesAnEqualButNewSession() throws IOException {
        Session original = sample();
        original.sortByValue();
        Path file = tmp.getRoot().toPath().resolve("session.ser");
        original.save(file);

        Session copy = Session.load(file);
        assertNotSame(original, copy);
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
        assertEquals(original.readings(), copy.readings());        // same readings in the same (sorted) order
        assertEquals(original.stats(), copy.stats());
        assertEquals("bench test", copy.label());
        assertTrue(Double.isNaN(copy.readings().get(2).value())); // the timeout survives as NaN
    }

    @Test
    public void theLiveSourceIsTransient() throws IOException {
        DataSource port = new DataSource() {
            @Override public String readLine() { return null; }
            @Override public String describe() { return "COM3 @ 115200"; }
            @Override public void close() { }
        };
        Session live = new Session("lab day", port);
        live.add(t(0, 22.0));
        assertTrue(live.source().isPresent());

        Path file = tmp.getRoot().toPath().resolve("live.ser");
        live.save(file);                                           // would throw NotSerializableException without transient
        Session copy = Session.load(file);
        assertTrue(copy.source().isEmpty());
        assertEquals("COM3 @ 115200", copy.sourceDescription());
        assertEquals(live, copy);
    }

    @Test
    public void loadRejectsFilesThatDoNotHoldASession() throws IOException {
        Path text = tmp.getRoot().toPath().resolve("plain.ser");
        Files.writeString(text, "not serialized at all");
        assertThrows(IOException.class, () -> Session.load(text));

        Path aString = tmp.getRoot().toPath().resolve("string.ser");
        write(aString, "just a string");
        IOException e = assertThrows(IOException.class, () -> Session.load(aString));
        assertTrue(e.getMessage(), e.getMessage().contains("does not hold a session"));

        Path other = tmp.getRoot().toPath().resolve("map.ser");
        Map<String, String> map = new HashMap<>(Map.of("a", "b"));
        write(other, map);
        assertThrows(InvalidClassException.class, () -> Session.load(other));   // refused by the class filter
    }

    private static void write(Path file, Object o) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
            out.writeObject(o);
        }
    }
}
