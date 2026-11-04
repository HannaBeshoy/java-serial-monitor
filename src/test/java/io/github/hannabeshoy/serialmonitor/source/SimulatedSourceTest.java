package io.github.hannabeshoy.serialmonitor.source;

import io.github.hannabeshoy.serialmonitor.protocol.FrameCodec;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SimulatedSourceTest {

    private static List<String> drain(DataSource s) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l; (l = s.readLine()) != null; ) out.add(l);
        return out;
    }

    @Test
    public void sameSeedSameLines() throws IOException {
        List<String> a = drain(new SimulatedSource(7, 50, 0, SimulatedSource.Faults.TYPICAL));
        List<String> b = drain(new SimulatedSource(7, 50, 0, SimulatedSource.Faults.TYPICAL));
        assertEquals(50, a.size());
        assertEquals(a, b);
        assertFalse(a.equals(drain(new SimulatedSource(8, 50, 0, SimulatedSource.Faults.TYPICAL))));
    }

    @Test
    public void cleanLinesAreAllValidAndConsecutive() throws Exception {
        List<String> lines = drain(SimulatedSource.clean(1, 300));
        for (int i = 0; i < lines.size(); i++) assertEquals(i, FrameCodec.parse(lines.get(i)).seq());
    }

    @Test
    public void faultsProduceBadLinesAndGaps() throws IOException {
        SimulatedSource.Faults many = new SimulatedSource.Faults(0.2, 0.1, 0.1, 0.2);
        int bad = 0;
        for (String l : drain(new SimulatedSource(3, 1000, 0, many))) {
            try {
                FrameCodec.parse(l);
            } catch (FrameException e) {
                bad++;
            }
        }
        assertTrue("about 30 % of the lines should be bad, got " + bad, bad > 200 && bad < 400);
    }

    @Test
    public void closeEndsTheStream() {
        SimulatedSource s = new SimulatedSource(1, -1, 0, SimulatedSource.Faults.NONE);
        assertTrue(s.readLine() != null);
        s.close();
        assertNull(s.readLine());
    }

    @Test
    public void faultRatesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedSource.Faults(1.5, 0, 0, 0));
    }

    @Test
    public void replayReadsText() throws IOException {
        try (ReplaySource r = ReplaySource.ofText("a\nb\n")) {
            assertEquals(List.of("a", "b"), drain(r));
            assertFalse(r.isLive());
        }
    }
}
