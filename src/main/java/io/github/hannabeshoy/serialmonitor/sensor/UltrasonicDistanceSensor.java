package io.github.hannabeshoy.serialmonitor.sensor;

/**
 * HC-SR04: the node sends the echo pulse width in microseconds.
 * Sound travels about 343 m/s, there and back, so cm = µs / 58.3 (0.0343 cm/µs / 2).
 * The node sends 0 when no echo arrived before its timeout.
 */
public class UltrasonicDistanceSensor extends Sensor {
    /** Microseconds of echo per centimetre of distance at about 20 °C. */
    public static final double US_PER_CM = 2.0 / 0.0343;

    public UltrasonicDistanceSensor() {
        this("D");
    }

    public UltrasonicDistanceSensor(String key) {
        super(key, "Distance (HC-SR04)", "cm", 2, 400);    // the module's datasheet range
    }

    @Override
    protected boolean rawIsValid(long raw) {
        return raw > 0;                                  // 0 = timeout, no object in range
    }

    @Override
    public double convert(long raw) {
        return raw / US_PER_CM;
    }
}
