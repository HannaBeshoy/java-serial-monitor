package io.github.hannabeshoy.serialmonitor.sensor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps frame keys to sensor objects. Built once at start-up, then only read. */
public final class SensorRegistry {
    private final Map<String, Sensor> byKey = new LinkedHashMap<>();

    /** The three sensors of the reference node: LM35 on T, HC-SR04 on D, LDR on L. */
    public static SensorRegistry standard() {
        return new SensorRegistry()
                .add(new Lm35TemperatureSensor())
                .add(new UltrasonicDistanceSensor())
                .add(new LightSensor());
    }

    public SensorRegistry add(Sensor s) {
        if (byKey.putIfAbsent(s.key(), s) != null)
            throw new IllegalArgumentException("key " + s.key() + " is already registered");
        return this;
    }

    public Optional<Sensor> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Collection<Sensor> all() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    /** The sensors sorted by readable name, ignoring case; for equal names by key. */
    public List<Sensor> sortedByName() {
        List<Sensor> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparing(Sensor::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Sensor::key));
        return list;
    }
}
