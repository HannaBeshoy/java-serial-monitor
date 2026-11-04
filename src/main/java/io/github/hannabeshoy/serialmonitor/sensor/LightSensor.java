package io.github.hannabeshoy.serialmonitor.sensor;

/** LDR in a voltage divider on a 10-bit ADC pin, shown as 0..100 % of full scale. */
public class LightSensor extends Sensor {

    public LightSensor() {
        this("L");
    }

    public LightSensor(String key) {
        super(key, "Light (LDR)", "%", 0, 100);
    }

    @Override
    protected boolean rawIsValid(long raw) {
        return raw >= 0 && raw <= 1023;
    }

    @Override
    public double convert(long raw) {
        return raw * 100.0 / 1023.0;
    }
}
