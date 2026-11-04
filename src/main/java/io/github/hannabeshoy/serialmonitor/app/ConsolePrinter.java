package io.github.hannabeshoy.serialmonitor.app;

import io.github.hannabeshoy.serialmonitor.core.MonitorListener;
import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.protocol.Frame;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker.Alarm;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Prints one line per frame, plus bad lines, lost frames and alarms. */
class ConsolePrinter implements MonitorListener {
    private final PrintStream out;
    private final boolean quiet;
    private final List<Reading> current = new ArrayList<>();
    private final List<Alarm> alarms = new ArrayList<>();
    private long seq = -1;

    ConsolePrinter(PrintStream out, boolean quiet) {
        this.out = out;
        this.quiet = quiet;
    }

    @Override
    public void onFrame(Frame f) {
        flush();
        seq = f.seq();
    }

    @Override
    public void onReading(Reading r) {
        current.add(r);
    }

    @Override
    public void onBadLine(String line, FrameException e) {
        flush();
        if (!quiet) out.printf("  bad line (%s): %s%n", e.getKind(), e.getMessage());
    }

    @Override
    public void onLostFrames(long afterSeq, long missing) {
        flush();
        if (!quiet) out.printf("  lost %d frame(s) after #%d%n", missing, afterSeq);
    }

    @Override
    public void onAlarm(Alarm a) {
        alarms.add(a);                 // printed after the frame's line
    }

    @Override
    public void onStopped(MonitorStats s) {
        flush();
    }

    /** Prints the readings collected for the previous frame. */
    private void flush() {
        if (!current.isEmpty() && !quiet) {
            StringBuilder sb = new StringBuilder(String.format("#%-5d", seq));
            for (Reading r : current) sb.append(String.format("  %s %-9s", r.key(), r.formatted()));
            out.println(sb.toString().stripTrailing());
        }
        current.clear();
        for (Alarm a : alarms) out.println("  " + a.message());
        alarms.clear();
    }
}
