package io.github.hannabeshoy.serialmonitor.app;

import io.github.hannabeshoy.serialmonitor.core.Monitor;
import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.log.CsvLogger;
import io.github.hannabeshoy.serialmonitor.sensor.SensorRegistry;
import io.github.hannabeshoy.serialmonitor.session.Session;
import io.github.hannabeshoy.serialmonitor.source.DataSource;
import io.github.hannabeshoy.serialmonitor.source.ReplaySource;
import io.github.hannabeshoy.serialmonitor.source.SerialPortSource;
import io.github.hannabeshoy.serialmonitor.source.SimulatedSource;
import io.github.hannabeshoy.serialmonitor.stats.AlarmHistory;
import io.github.hannabeshoy.serialmonitor.stats.RollingStats;
import io.github.hannabeshoy.serialmonitor.ui.MonitorWindow;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Entry point: parses the options, wires source, monitor and listeners, and prints a summary at the end. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(Options.parse(args), System.out);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.print(Options.USAGE);
            code = 2;
        }
        if (code != 0) System.exit(code);
    }

    /** Runs one session. Separate from main so tests can call it with their own output stream. */
    static int run(Options o, PrintStream out) {
        switch (o.mode()) {
            case HELP -> {
                out.print(Options.USAGE);
                return 0;
            }
            case LIST_PORTS -> {
                List<String> ports = SerialPortSource.listPorts();
                if (ports.isEmpty()) out.println("no serial ports found");
                ports.forEach(out::println);
                return 0;
            }
            case SHOW -> {
                return show(o, out);
            }
            default -> { }
        }

        DataSource source;
        try {
            source = open(o);
        } catch (IOException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }

        Monitor monitor = new Monitor(source, SensorRegistry.standard(), o.alarms());
        monitor.addListener(new ConsolePrinter(out, o.quiet() || o.gui()));
        AlarmHistory history = new AlarmHistory();
        monitor.addListener(history);
        Session session = null;
        if (o.save() != null || o.export() != null) {      // only kept when asked for: an endless run would fill the memory
            String label = o.label() != null ? o.label() : "run " + Instant.now().truncatedTo(ChronoUnit.SECONDS);
            session = new Session(label, source);
            monitor.addListener(session);
        }
        CsvLogger csv = null;
        try {
            if (o.log() != null) {
                csv = new CsvLogger(o.log());
                monitor.addListener(csv);
            }
        } catch (IOException e) {
            out.println("error: cannot write " + o.log() + ": " + e.getMessage());
            return 1;
        }
        if (o.gui()) {
            MonitorWindow w = new MonitorWindow(monitor);
            monitor.addListener(w);
            SwingUtilities.invokeLater(w::show);
        }

        out.println("reading from " + source.describe() + (o.alarms().isEmpty() ? "" : ", alarms " + o.alarms()));
        Thread hook = new Thread(monitor::stop, "shutdown");      // Ctrl+C: stop cleanly, flush the CSV
        Runtime.getRuntime().addShutdownHook(hook);
        monitor.start();
        try {
            while (!monitor.await(1, TimeUnit.SECONDS)) {
                // waiting; the window or Ctrl+C can stop the monitor
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            monitor.stop();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // already shutting down
            }
            if (csv != null) {
                try {
                    csv.close();
                } catch (IOException e) {
                    out.println("warning: closing the CSV failed: " + e.getMessage());
                }
            }
        }
        printSummary(monitor, out, o);
        if (history.size() > 0) {
            out.println("alarms, most severe first:");
            out.print(history.report());
        }
        if (session != null && !writeSession(session, o, out)) return 1;
        return monitor.snapshot().error() == null ? 0 : 1;
    }

    /** Saves and exports the recorded session as the options ask; false if a file could not be written. */
    private static boolean writeSession(Session session, Options o, PrintStream out) {
        sort(session, o.sort());
        try {
            if (o.export() != null) {
                session.exportText(o.export());
                out.println("session exported to " + o.export());
            }
            if (o.save() != null) {
                session.save(o.save());
                out.println("session saved to " + o.save() + " (" + session.readings().size() + " readings)");
            }
            return true;
        } catch (IOException e) {
            out.println("error: cannot write the session: " + e.getMessage());
            return false;
        }
    }

    /** --show: loads a saved session and prints its report. */
    private static int show(Options o, PrintStream out) {
        Session session;
        try {
            session = Session.load(o.show());
        } catch (IOException e) {
            out.println("error: cannot load " + o.show() + ": " + e.getMessage());
            return 1;
        }
        sort(session, o.sort());
        PrintWriter w = new PrintWriter(new OutputStreamWriter(out, out.charset()));
        session.writeTo(w);
        w.flush();                                      // not closed: that would close the caller's stream
        if (o.export() != null) {
            try {
                session.exportText(o.export());
                out.println("session exported to " + o.export());
            } catch (IOException e) {
                out.println("error: cannot write " + o.export() + ": " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }

    private static void sort(Session session, Options.Sort order) {
        switch (order) {
            case TIME -> session.sortByTime();
            case VALUE -> session.sortByValue();
            case NAME -> session.sortBySensorName();
        }
    }

    private static DataSource open(Options o) throws IOException {
        return switch (o.mode()) {
            case SERIAL -> new SerialPortSource(o.port(), o.baud());
            case REPLAY -> ReplaySource.of(o.replay());
            case SIM -> new SimulatedSource(o.seed(), o.count(), o.periodMs(),
                    o.faults() ? SimulatedSource.Faults.TYPICAL : SimulatedSource.Faults.NONE);
            case SHOW, LIST_PORTS, HELP -> throw new IllegalStateException("not a data source: " + o.mode());
        };
    }

    private static void printSummary(Monitor m, PrintStream out, Options o) {
        MonitorStats s = m.snapshot();
        out.println();
        out.println("summary: " + s.summary());
        Map<String, RollingStats.Summary> stats = m.statistics();
        m.sensors().all().forEach(sensor -> {
            RollingStats.Summary st = stats.getOrDefault(sensor.key(), RollingStats.Summary.EMPTY);
            if (st.count() == 0) out.printf("  %-20s no valid readings%n", sensor.name());
            else out.printf("  %-20s min %7.2f  mean %7.2f  max %7.2f  sd %5.2f %s  (last %d of %d)%n",
                    sensor.name(), st.min(), st.mean(), st.max(), st.stddev(), sensor.unit(),
                    Math.min(st.count(), Monitor.DEFAULT_WINDOW), st.count());
        });
        if (o.log() != null) out.println("log written to " + o.log());
    }
}
