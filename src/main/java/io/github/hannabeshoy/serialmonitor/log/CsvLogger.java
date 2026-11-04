package io.github.hannabeshoy.serialmonitor.log;

import io.github.hannabeshoy.serialmonitor.core.MonitorListener;
import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker.Alarm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes every reading, bad line and alarm to a CSV file that opens in Excel or MATLAB.
 * Columns: time, seq, kind, key, value, unit, valid, note.
 */
public class CsvLogger implements MonitorListener, AutoCloseable {
    public static final String HEADER = "time,seq,kind,key,value,unit,valid,note";

    private final BufferedWriter out;
    private long rows = 0;

    public CsvLogger(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.newLine();
    }

    @Override
    public void onReading(Reading r) {
        row(r.time().toString(), String.valueOf(r.seq()), "reading", r.key(),
                r.valid() ? String.format(Locale.ROOT, "%.3f", r.value()) : "", r.unit(), String.valueOf(r.valid()), "");
    }

    @Override
    public void onBadLine(String line, FrameException e) {
        row("", "", "bad-line", "", "", "", "false", e.getKind() + ": " + e.getMessage() + " | " + line);
    }

    @Override
    public void onAlarm(Alarm a) {
        Reading r = a.reading();
        row(r.time().toString(), String.valueOf(r.seq()), a.raised() ? "alarm" : "alarm-clear", r.key(),
                String.format(Locale.ROOT, "%.3f", r.value()), r.unit(), "true", a.rule().toString());
    }

    @Override
    public void onStopped(MonitorStats s) {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        try {
            out.write(sb.toString());
            out.newLine();
            rows++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);       // listener methods cannot throw checked exceptions
        }
    }

    /** RFC 4180: quote a cell that contains a comma, a quote or a line break; double the quotes. */
    static String escape(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    public long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
