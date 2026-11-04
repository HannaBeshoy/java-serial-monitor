package io.github.hannabeshoy.serialmonitor.app;

import io.github.hannabeshoy.serialmonitor.stats.AlarmRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line options, parsed once into an immutable record.
 *
 * @param mode      where the lines come from
 * @param port      serial port name for {@link Mode#SERIAL}
 * @param baud      baud rate
 * @param replay    file for {@link Mode#REPLAY}
 * @param seed      random seed for {@link Mode#SIM}
 * @param count     lines to simulate, -1 for no end
 * @param periodMs  pause between simulated lines
 * @param faults    inject faults into the simulation
 * @param log       CSV output file, or null
 * @param alarms    alarm rules
 * @param gui       open the Swing window
 * @param quiet     print only the summary
 */
public record Options(Mode mode, String port, int baud, Path replay, long seed, long count, long periodMs,
                      boolean faults, Path log, List<AlarmRule> alarms, boolean gui, boolean quiet) {

    public enum Mode { SIM, SERIAL, REPLAY, LIST_PORTS, HELP }

    public static final String USAGE = """
            usage: java -jar java-serial-monitor.jar [source] [options]

            source (pick one, default --sim):
              --sim                 simulated sensor node (no hardware needed)
              --port NAME           real serial port, e.g. COM3 or /dev/ttyACM0
              --replay FILE         play back a file of recorded lines
              --list-ports          show the serial ports of this computer and exit

            options:
              --baud N              baud rate for --port (default 115200)
              --seed N              random seed for --sim (default 1)
              --count N             number of simulated lines, -1 = endless (default 200, endless with --gui)
              --period MS           pause between simulated lines (default 0, 250 with --gui)
              --faults              inject checksum errors, garbage, lost frames and timeouts into --sim
              --log FILE.csv        write every reading, bad line and alarm to a CSV file
              --alarm RULE          alarm rule such as T>30 or D<10 (repeatable)
              --gui                 open the live window
              --quiet               print only the final summary
              --help                this text
            """;

    public static Options parse(String... args) {
        Mode mode = null;
        String port = null;
        Path replay = null;
        int baud = 115200;
        long seed = 1;
        Long count = null;
        Long period = null;
        boolean faults = false, gui = false, quiet = false;
        Path log = null;
        List<AlarmRule> alarms = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--sim" -> mode = setOnce(mode, Mode.SIM);
                case "--port" -> { mode = setOnce(mode, Mode.SERIAL); port = value(args, ++i, a); }
                case "--replay" -> { mode = setOnce(mode, Mode.REPLAY); replay = Path.of(value(args, ++i, a)); }
                case "--list-ports" -> mode = setOnce(mode, Mode.LIST_PORTS);
                case "--help", "-h" -> mode = Mode.HELP;
                case "--baud" -> baud = (int) positive(value(args, ++i, a), a);
                case "--seed" -> seed = number(value(args, ++i, a), a);
                case "--count" -> count = number(value(args, ++i, a), a);
                case "--period" -> period = positiveOrZero(value(args, ++i, a), a);
                case "--faults" -> faults = true;
                case "--log" -> log = Path.of(value(args, ++i, a));
                case "--alarm" -> alarms.add(AlarmRule.parse(value(args, ++i, a)));
                case "--gui" -> gui = true;
                case "--quiet" -> quiet = true;
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        if (mode == null) mode = Mode.SIM;
        long c = count != null ? count : (gui ? -1 : 200);
        long p = period != null ? period : (gui ? 250 : 0);
        if (c < -1) throw new IllegalArgumentException("--count must be -1 or more");
        return new Options(mode, port, baud, replay, seed, c, p, faults, log, List.copyOf(alarms), gui, quiet);
    }

    private static Mode setOnce(Mode current, Mode next) {
        if (current != null && current != next && current != Mode.HELP)
            throw new IllegalArgumentException("choose only one source: --sim, --port, --replay or --list-ports");
        return current == Mode.HELP ? current : next;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static long number(String s, String option) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " needs a whole number, got " + s);
        }
    }

    private static long positive(String s, String option) {
        long v = number(s, option);
        if (v <= 0) throw new IllegalArgumentException(option + " must be positive");
        return v;
    }

    private static long positiveOrZero(String s, String option) {
        long v = number(s, option);
        if (v < 0) throw new IllegalArgumentException(option + " must not be negative");
        return v;
    }
}
