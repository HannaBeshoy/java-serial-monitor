package io.github.hannabeshoy.serialmonitor.sensor;

/**
 * LM35 on an analog pin: 10 mV per °C, read by a 10-bit ADC (0..1023) with a 5 V reference.
 * °C = raw · Vref / 1023 · 100.
 */
public class Lm35TemperatureSensor extends Sensor {
    private final double vref;

    public Lm35TemperatureSensor() {
        this("T", 5.0);
    }

    public Lm35TemperatureSensor(String key, double vref) {
        super(key, "Temperature (LM35)", "°C", -55, 150);   // the LM35's rated range
        this.vref = vref;
    }

    @Override
    protected boolean rawIsValid(long raw) {
        return raw >= 0 && raw <= 1023;
    }

    @Override
    public double convert(long raw) {
        return raw * vref / 1023.0 * 100.0;
    }

    /** A subclass with its own field must include it: two LM35s on different references convert differently. */
    @Override
    public boolean equals(Object o) {
        return super.equals(o) && Double.compare(vref, ((Lm35TemperatureSensor) o).vref) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Double.hashCode(vref);
    }
}
