package io.github.hannabeshoy.serialmonitor.sensor;

import java.time.Instant;

/**
 * A sensor type that turns the raw number from the node into a physical value.
 * Each subclass knows its own conversion; the monitor only sees this abstract type
 * and relies on dynamic binding to call the right {@link #convert(long)}.
 */
public abstract class Sensor {
    private final String key;
    private final String name;
    private final String unit;
    private final double min;
    private final double max;

    /**
     * @param key  the key used in the frame, for example "T"
     * @param name a readable name
     * @param unit the unit of the converted value
     * @param min  smallest physically possible value
     * @param max  largest physically possible value
     */
    protected Sensor(String key, String name, String unit, double min, double max) {
        if (min >= max) throw new IllegalArgumentException("min must be below max");
        this.key = key;
        this.name = name;
        this.unit = unit;
        this.min = min;
        this.max = max;
    }

    /** Converts a raw value into the physical unit. */
    public abstract double convert(long raw);

    /** False for raw values that mean "no measurement" (for example a sensor timeout). */
    protected boolean rawIsValid(long raw) {
        return raw >= 0;
    }

    /** Converts and checks one raw value. A reading outside [min, max] is kept but marked invalid. */
    public final Reading read(long raw, long seq, Instant time) {
        if (!rawIsValid(raw)) return new Reading(key, name, Double.NaN, unit, seq, time, false);
        double v = convert(raw);
        return new Reading(key, name, v, unit, seq, time, v >= min && v <= max);
    }

    public String key()  { return key; }
    public String name() { return name; }
    public String unit() { return unit; }
    public double min()  { return min; }
    public double max()  { return max; }

    @Override
    public String toString() {
        return name + " [" + key + ", " + unit + "]";
    }
}
