package io.github.hannabeshoy.serialmonitor.source;

import io.github.hannabeshoy.serialmonitor.protocol.Frame;
import io.github.hannabeshoy.serialmonitor.protocol.FrameCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Generates the lines a real sensor node would send, with optional faults, so the whole program
 * can run and be tested without an Arduino. With the same seed it always produces the same lines.
 *
 * <p>Faults it can inject: a damaged character (checksum error), a garbage line, a skipped
 * sequence number (lost frame) and a distance timeout (raw value 0).
 */
public class SimulatedSource implements DataSource {

    /** How often each fault happens, from 0 (never) to 1 (always). */
    public record Faults(double corrupt, double garbage, double skip, double timeout) {
        public static final Faults NONE = new Faults(0, 0, 0, 0);
        public static final Faults TYPICAL = new Faults(0.02, 0.01, 0.01, 0.03);

        public Faults {
            for (double p : new double[]{corrupt, garbage, skip, timeout})
                if (p < 0 || p > 1) throw new IllegalArgumentException("fault rate must be 0..1: " + p);
        }
    }

    private final Random rnd;
    private final long count;          // number of lines to produce, or -1 for no end
    private final long periodMs;       // pause between lines, 0 in tests
    private final Faults faults;
    private long produced = 0;
    private long seq = 0;
    private double temperature = 23.5; // °C, drifts slowly
    private volatile boolean closed = false;

    public SimulatedSource(long seed, long count, long periodMs, Faults faults) {
        this.rnd = new Random(seed);
        this.count = count;
        this.periodMs = periodMs;
        this.faults = faults;
    }

    /** No faults, no pause: handy in tests. */
    public static SimulatedSource clean(long seed, long count) {
        return new SimulatedSource(seed, count, 0, Faults.NONE);
    }

    @Override
    public String readLine() {
        if (closed || (count >= 0 && produced >= count)) return null;
        if (periodMs > 0) {
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (closed) return null;
        produced++;

        if (rnd.nextDouble() < faults.garbage()) return "\u0000~~ noise " + rnd.nextInt(1000);
        if (rnd.nextDouble() < faults.skip()) seq++;          // the node sent it, the cable lost it

        long s = seq++;
        long uptime = s * 250 + rnd.nextInt(5);
        temperature += (23.5 - temperature) * 0.02 + rnd.nextGaussian() * 0.05;   // drifts, but stays near 23.5
        double t = temperature + rnd.nextGaussian() * 0.3;
        double cm = 50 + 25 * Math.sin(s / 40.0) + rnd.nextGaussian() * 0.8;
        double light = 60 + 10 * Math.sin(s / 150.0) + rnd.nextGaussian();

        Map<String, Long> raw = new LinkedHashMap<>();
        raw.put("T", clamp(Math.round(t / 100.0 / 5.0 * 1023), 0, 1023));        // inverse of the LM35 formula
        raw.put("D", rnd.nextDouble() < faults.timeout() ? 0 : Math.round(cm * 2 / 0.0343));
        raw.put("L", clamp(Math.round(light / 100.0 * 1023), 0, 1023));
        String line = FrameCodec.format(new Frame("SN", s, uptime, raw));

        if (rnd.nextDouble() < faults.corrupt()) {          // flip one digit somewhere in the body
            char[] c = line.toCharArray();
            int i = 1 + rnd.nextInt(line.length() - 4);
            c[i] = c[i] == '7' ? '8' : '7';
            line = new String(c);
        }
        return line;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Paced like a real node only when there is a pause between lines. */
    @Override
    public boolean isLive() {
        return periodMs > 0;
    }

    @Override
    public String describe() {
        return "simulation" + (count >= 0 ? " (" + count + " lines)" : "");
    }

    @Override
    public void close() {
        closed = true;
    }
}
