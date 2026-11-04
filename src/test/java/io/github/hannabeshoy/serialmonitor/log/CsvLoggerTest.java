package io.github.hannabeshoy.serialmonitor.log;

import io.github.hannabeshoy.serialmonitor.core.Monitor;
import io.github.hannabeshoy.serialmonitor.sensor.SensorRegistry;
import io.github.hannabeshoy.serialmonitor.source.SimulatedSource;
import io.github.hannabeshoy.serialmonitor.stats.AlarmRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CsvLoggerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void escapesCellsLikeRfc4180() {
        assertEquals("plain", CsvLogger.escape("plain"));
        assertEquals("\"a,b\"", CsvLogger.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvLogger.escape("say \"hi\""));
    }

    @Test
    public void writesOneRowPerReadingBadLineAndAlarm() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("logs/run.csv");      // folder is created
        Monitor m = new Monitor(new SimulatedSource(2, 400, 0, SimulatedSource.Faults.TYPICAL),
                SensorRegistry.standard(), List.of(AlarmRule.parse("D<30")));
        try (CsvLogger csv = new CsvLogger(file)) {
            m.addListener(csv);
            m.start();
            assertTrue(m.await(10, TimeUnit.SECONDS));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(CsvLogger.HEADER, lines.get(0));
            assertEquals(csv.rows(), lines.size() - 1);
            long readings = lines.stream().filter(l -> l.contains(",reading,")).count();
            long bad = lines.stream().filter(l -> l.contains(",bad-line,")).count();
            long alarms = lines.stream().filter(l -> l.contains(",alarm,")).count();
            assertEquals(3 * m.snapshot().frames(), readings);
            assertEquals(m.snapshot().bad(), bad);
            assertTrue("the distance dips below 30 cm in the simulation", alarms > 0);
            for (String l : lines) assertEquals(l, 8, splitCsv(l));
        }
    }

    /** Number of cells in a CSV row, respecting quotes. */
    private static int splitCsv(String row) {
        int cells = 1;
        boolean q = false;
        for (char c : row.toCharArray()) {
            if (c == '"') q = !q;
            else if (c == ',' && !q) cells++;
        }
        return cells;
    }
}
