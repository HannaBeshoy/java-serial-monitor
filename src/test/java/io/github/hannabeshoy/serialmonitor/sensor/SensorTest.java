package io.github.hannabeshoy.serialmonitor.sensor;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SensorTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Test
    public void lm35ConvertsAdcCountsToCelsius() {
        Sensor t = new Lm35TemperatureSensor();
        assertEquals(29.82, t.convert(61), 0.01);           // 61 · 5 / 1023 · 100
        assertEquals(0.0, t.convert(0), 1e-9);
        assertEquals(500.0, t.convert(1023), 1e-9);          // 5 V would be 500 °C: physically impossible
        Reading r = t.read(1023, 7, NOW);
        assertFalse("outside the LM35 range", r.valid());
        assertTrue(t.read(61, 7, NOW).valid());
        assertFalse(t.read(2000, 7, NOW).valid());          // not a 10-bit value
    }

    @Test
    public void ultrasonicConvertsEchoTimeToCentimetres() {
        Sensor d = new UltrasonicDistanceSensor();
        assertEquals(34.3, d.convert(2000), 0.01);           // 2000 µs · 0.0343 / 2
        Reading timeout = d.read(0, 1, NOW);
        assertFalse(timeout.valid());
        assertTrue(Double.isNaN(timeout.value()));
        assertEquals("invalid", timeout.formatted());
        assertFalse("1 cm is below the 2 cm minimum", d.read(58, 1, NOW).valid());
    }

    @Test
    public void lightIsPercentOfFullScale() {
        Sensor l = new LightSensor();
        assertEquals(100.0, l.convert(1023), 1e-9);
        assertEquals(50.05, l.convert(512), 0.01);
        assertEquals("50.0 %", l.read(512, 1, NOW).formatted());
    }

    @Test
    public void readingCarriesItsContext() {
        Reading r = new Lm35TemperatureSensor().read(61, 42, NOW);
        assertEquals("T", r.key());
        assertEquals(42, r.seq());
        assertEquals(NOW, r.time());
        assertEquals("°C", r.unit());
    }

    @Test
    public void registryRejectsDuplicateKeys() {
        SensorRegistry reg = SensorRegistry.standard();
        assertEquals(3, reg.all().size());
        assertTrue(reg.get("D").isPresent());
        assertFalse(reg.get("Q").isPresent());
        assertThrows(IllegalArgumentException.class, () -> reg.add(new LightSensor()));
    }

    @Test
    public void subclassesAreUsedThroughTheBaseType() {
        double sum = 0;
        for (Sensor s : SensorRegistry.standard().all()) sum += s.convert(100);   // dynamic binding
        assertEquals(100 * 5.0 / 1023 * 100 + 100 / UltrasonicDistanceSensor.US_PER_CM + 100 * 100.0 / 1023, sum, 1e-9);
    }
}
