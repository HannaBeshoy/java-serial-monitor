package io.github.hannabeshoy.serialmonitor.app;

import io.github.hannabeshoy.serialmonitor.stats.AlarmRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MainTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void defaultsAreASimulationOf200Lines() {
        Options o = Options.parse();
        assertEquals(Options.Mode.SIM, o.mode());
        assertEquals(200, o.count());
        assertEquals(0, o.periodMs());
        assertEquals(115200, o.baud());
    }

    @Test
    public void guiMakesTheSimulationEndlessAndPaced() {
        Options o = Options.parse("--gui");
        assertEquals(-1, o.count());
        assertEquals(250, o.periodMs());
        assertEquals(10, Options.parse("--gui", "--count", "10").count());
    }

    @Test
    public void parsesEveryOption() {
        Options o = Options.parse("--port", "COM3", "--baud", "9600", "--log", "x.csv", "--alarm", "T>30", "--alarm", "D<10", "--quiet");
        assertEquals(Options.Mode.SERIAL, o.mode());
        assertEquals("COM3", o.port());
        assertEquals(9600, o.baud());
        assertEquals(Path.of("x.csv"), o.log());
        assertEquals(List.of(AlarmRule.parse("T>30"), AlarmRule.parse("D<10")), o.alarms());
        assertTrue(o.quiet());
        assertEquals(Options.Mode.HELP, Options.parse("--sim", "--help").mode());
    }

    @Test
    public void rejectsBadInput() {
        String[][] bad = {{"--port"}, {"--sim", "--port", "COM3"}, {"--baud", "0"}, {"--count", "x"}, {"--bogus"},
                {"--alarm", "T=3"}, {"--period", "-5"}, {"--count", "-7"}, {"--sort", "size"}, {"--label", " "},
                {"--label", "x".repeat(101)}, {"--save"}, {"--sim", "--show", "a.ser"}};
        for (String[] args : bad)
            assertThrows(String.join(" ", args), IllegalArgumentException.class, () -> Options.parse(args));
    }

    @Test
    public void endToEndRunPrintsFramesAndASummary() throws Exception {
        Path csv = tmp.getRoot().toPath().resolve("out.csv");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Main.run(Options.parse("--sim", "--count", "30", "--faults", "--seed", "3", "--log", csv.toString(), "--alarm", "D<30"),
                new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(out, out.contains("reading from simulation (30 lines)"));
        assertTrue(out, out.contains("#0 "));
        assertTrue(out, out.contains("summary: lines 30"));
        assertTrue(out, out.contains("Temperature (LM35)"));
        assertTrue(Files.exists(csv));
        assertFalse(Files.readAllLines(csv).isEmpty());
    }

    @Test
    public void missingReplayFileIsAnErrorNotACrash() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Main.run(Options.parse("--replay", tmp.getRoot().toPath().resolve("nope.txt").toString()), new PrintStream(buf));
        assertEquals(1, code);
        assertTrue(buf.toString().startsWith("error:"));
    }

    @Test
    public void parsesTheSessionOptions() {
        Options o = Options.parse("--save", "run.ser", "--export", "run.txt", "--label", "bench", "--sort", "value");
        assertEquals(Options.Mode.SIM, o.mode());
        assertEquals(Path.of("run.ser"), o.save());
        assertEquals(Path.of("run.txt"), o.export());
        assertEquals("bench", o.label());
        assertEquals(Options.Sort.VALUE, o.sort());
        assertEquals(Options.Sort.TIME, Options.parse().sort());
        Options show = Options.parse("--show", "run.ser", "--sort", "name");
        assertEquals(Options.Mode.SHOW, show.mode());
        assertEquals(Path.of("run.ser"), show.show());
        assertEquals(Options.Sort.NAME, show.sort());
    }

    @Test
    public void savedSessionCanBeShownAndExportedLater() throws Exception {
        Path ser = tmp.getRoot().toPath().resolve("run.ser");
        Path txt = tmp.getRoot().toPath().resolve("run.txt");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Main.run(Options.parse("--sim", "--count", "20", "--seed", "4", "--quiet",
                "--save", ser.toString(), "--export", txt.toString(), "--label", "bench"), new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(out, 0, code);
        assertTrue(out, out.contains("session saved to " + ser + " (60 readings)"));   // 20 frames x 3 sensors
        List<String> exported = Files.readAllLines(txt, StandardCharsets.UTF_8);
        assertEquals("session: bench", exported.get(0));
        assertEquals("source: simulation (20 lines)", exported.get(1));
        assertTrue(exported.get(3), exported.get(3).startsWith("summary: lines 20, frames 20"));

        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        assertEquals(0, Main.run(Options.parse("--show", ser.toString()), new PrintStream(shown, true, StandardCharsets.UTF_8)));
        List<String> lines = shown.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(exported, lines);                               // the loaded session prints the same report
    }

    @Test
    public void missingSessionFileIsAnErrorNotACrash() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Main.run(Options.parse("--show", tmp.getRoot().toPath().resolve("nope.ser").toString()), new PrintStream(buf));
        assertEquals(1, code);
        assertTrue(buf.toString().startsWith("error: cannot load"));
    }
}
