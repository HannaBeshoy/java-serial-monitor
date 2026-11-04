package io.github.hannabeshoy.serialmonitor.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * A small line plot of the last {@code capacity} values of one sensor, painted with Java 2D.
 * {@link #add} is called from the monitor thread and {@link #paintComponent} from the Swing thread,
 * so both synchronize on the same lock.
 */
class TrendPanel extends JPanel {
    private final int capacity;
    private final Map<String, Deque<Double>> history = new HashMap<>();
    private final Object lock = new Object();
    private volatile String key = null;
    private volatile String title = "";

    TrendPanel(int capacity) {
        this.capacity = capacity;
        setBackground(Color.WHITE);
    }

    void add(String k, double v) {
        synchronized (lock) {
            Deque<Double> d = history.computeIfAbsent(k, x -> new ArrayDeque<>());
            d.addLast(v);
            if (d.size() > capacity) d.removeFirst();
        }
    }

    void show(String k, String t) {
        key = k;
        title = t;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        double[] v;
        synchronized (lock) {
            Deque<Double> d = key == null ? null : history.get(key);
            v = d == null ? new double[0] : d.stream().mapToDouble(Double::doubleValue).toArray();
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Insets in = getInsets();
        int x0 = in.left + 56, y0 = in.top + 22, w = getWidth() - x0 - in.right - 12, h = getHeight() - y0 - in.bottom - 22;
        g.setColor(new Color(0x55, 0x5f, 0x66));
        g.drawString(title + (v.length > 0 ? "   last " + v.length + " values" : "   no data yet"), x0, y0 - 6);
        if (v.length < 2 || w < 20 || h < 20) {
            g.dispose();
            return;
        }
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : v) { min = Math.min(min, x); max = Math.max(max, x); }
        if (max - min < 1e-9) { min -= 1; max += 1; }
        double pad = (max - min) * 0.1;
        min -= pad;
        max += pad;

        g.setColor(new Color(0xe3, 0xe7, 0xea));                 // grid and axis labels
        for (int i = 0; i <= 4; i++) {
            int y = y0 + h * i / 4;
            g.drawLine(x0, y, x0 + w, y);
            g.setColor(new Color(0x6b, 0x74, 0x7a));
            g.drawString(String.format("%.1f", max - (max - min) * i / 4), in.left + 6, y + 4);
            g.setColor(new Color(0xe3, 0xe7, 0xea));
        }
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < v.length; i++) {
            double x = x0 + (double) w * i / (capacity - 1);
            double y = y0 + h * (max - v[i]) / (max - min);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        g.setColor(new Color(0x1f, 0x6b, 0x52));
        g.setStroke(new BasicStroke(1.8f));
        g.draw(path);
        g.dispose();
    }
}
