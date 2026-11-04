package io.github.hannabeshoy.serialmonitor.ui;

import io.github.hannabeshoy.serialmonitor.core.Monitor;
import io.github.hannabeshoy.serialmonitor.core.MonitorListener;
import io.github.hannabeshoy.serialmonitor.core.MonitorStats;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.sensor.Sensor;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker.Alarm;
import io.github.hannabeshoy.serialmonitor.stats.RollingStats;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live window: a table of every sensor with its statistics, a trend plot of the selected sensor,
 * counters, and an event log.
 *
 * <p>Threading: the monitor calls the listener methods on its dispatcher thread. Those methods only
 * store data in thread-safe structures or post work with {@link SwingUtilities#invokeLater}; a Swing
 * {@link Timer} repaints four times per second on the event dispatch thread. Swing components are
 * touched only on that thread.
 */
public class MonitorWindow implements MonitorListener {
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Monitor monitor;
    private final List<Sensor> sensors;
    private final Map<String, Reading> latest = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inAlarm = new ConcurrentHashMap<>();
    private final TrendPanel trend = new TrendPanel(240);

    private JFrame frame;
    private final SensorTableModel model = new SensorTableModel();
    private final JLabel counters = new JLabel(" ");
    private final JTextArea events = new JTextArea(8, 60);
    private final JButton stop = new JButton("Stop");
    private Timer timer;
    private int selected = 0;
    private Map<String, RollingStats.Summary> stats = Map.of();     // refreshed by the timer, EDT only

    public MonitorWindow(Monitor monitor) {
        this.monitor = monitor;
        this.sensors = new ArrayList<>(monitor.sensors().all());
    }

    /** Builds and shows the window. Call on the event dispatch thread. */
    public void show() {
        frame = new JFrame("Serial Monitor · " + monitor.describeSource());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {      // anonymous class
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                monitor.stop();
                if (timer != null) timer.stop();
            }
        });

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        int[] widths = {170, 40, 90, 80, 80, 80, 80, 70};
        for (int c = 0; c < widths.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                selected = table.getSelectedRow();
                Sensor s = sensors.get(selected);
                trend.show(s.key(), s.name() + " [" + s.unit() + "]");
            }
        });
        if (!sensors.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
            trend.show(sensors.get(0).key(), sensors.get(0).name() + " [" + sensors.get(0).unit() + "]");
        }
        JScrollPane tablePane = new JScrollPane(table);
        tablePane.setPreferredSize(new Dimension(760, 24 * (sensors.size() + 1) + 8));

        events.setEditable(false);
        events.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane eventPane = new JScrollPane(events);
        eventPane.setBorder(BorderFactory.createTitledBorder("Events"));

        trend.setPreferredSize(new Dimension(760, 220));
        trend.setBorder(BorderFactory.createTitledBorder("Trend of the selected row · min, mean, max and std dev use the last " + Monitor.DEFAULT_WINDOW + " readings"));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(tablePane, BorderLayout.NORTH);
        top.add(trend, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, eventPane);
        split.setResizeWeight(0.75);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(stop);
        bottom.add(counters);
        stop.addActionListener(e -> {
            monitor.stop();
            stop.setEnabled(false);
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(split, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        timer = new Timer(250, e -> refresh());
        timer.start();
    }

    private void refresh() {
        stats = monitor.statistics();
        model.fireTableRowsUpdated(0, Math.max(0, sensors.size() - 1));
        MonitorStats s = monitor.snapshot();
        counters.setText(String.format("frames %d   bad %d (checksum %d)   lost %d   dropped %d   quality %.1f %%",
                s.frames(), s.bad(), s.checksum(), s.lost(), s.dropped(), s.quality() * 100));
        trend.repaint();
    }

    private void log(String text) {
        String line = LocalTime.now().format(HMS) + "  " + text + "\n";
        SwingUtilities.invokeLater(() -> {
            events.append(line);
            int lines = events.getLineCount();
            if (lines > 300) {                                  // keep the log short
                try {
                    events.replaceRange("", 0, events.getLineEndOffset(lines - 301));
                } catch (javax.swing.text.BadLocationException ignored) {
                    events.setText(line);
                }
            }
            events.setCaretPosition(events.getDocument().getLength());
        });
    }

    // ---------------------------------------------------------------- MonitorListener (dispatcher thread)

    @Override
    public void onReading(Reading r) {
        latest.put(r.key(), r);
        if (r.valid()) trend.add(r.key(), r.value());
    }

    @Override
    public void onBadLine(String line, FrameException e) {
        log("bad line, " + e.getKind().name().toLowerCase() + ": " + e.getMessage());
    }

    @Override
    public void onLostFrames(long afterSeq, long missing) {
        log("lost " + missing + " frame(s) after #" + afterSeq);
    }

    @Override
    public void onAlarm(Alarm a) {
        inAlarm.put(a.rule().key(), a.raised());
        log(a.message());
    }

    @Override
    public void onStopped(MonitorStats s) {
        log("stopped: " + s.summary());
        SwingUtilities.invokeLater(() -> {
            stop.setEnabled(false);
            refresh();
            if (timer != null) timer.stop();
        });
    }

    // ---------------------------------------------------------------- table

    private class SensorTableModel extends AbstractTableModel {           // inner class: reads the window's maps
        private final String[] cols = {"Sensor", "Key", "Latest", "Min", "Mean", "Max", "Std dev", "Status"};

        @Override public int getRowCount()    { return sensors.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            Sensor s = sensors.get(row);
            Reading r = latest.get(s.key());
            RollingStats.Summary st = stats.getOrDefault(s.key(), RollingStats.Summary.EMPTY);
            return switch (col) {
                case 0 -> s.name();
                case 1 -> s.key();
                case 2 -> r == null ? "–" : r.formatted();
                case 3 -> fmt(st.min(), s.unit());
                case 4 -> fmt(st.mean(), s.unit());
                case 5 -> fmt(st.max(), s.unit());
                case 6 -> fmt(st.stddev(), s.unit());
                case 7 -> r == null ? "waiting" : Boolean.TRUE.equals(inAlarm.get(s.key())) ? "ALARM" : r.valid() ? "ok" : "invalid";
                default -> "";
            };
        }

        private String fmt(double v, String unit) {
            return Double.isNaN(v) ? "–" : String.format("%.1f %s", v, unit);
        }
    }
}
