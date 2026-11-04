package io.github.hannabeshoy.serialmonitor.stats;

import io.github.hannabeshoy.serialmonitor.sensor.Reading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns readings into alarm events with hysteresis: a rule fires once when the value enters the
 * alarm zone and fires again only after the value has left the zone by at least {@code hysteresis}.
 * Without that, a temperature hovering at 30.0 °C would flood the user with alarms.
 *
 * <p>Used only by the monitor's dispatcher thread, so it needs no locking.
 */
public class AlarmTracker {

    /** One alarm event. {@code raised} is true when entering the zone, false when it clears. */
    public record Alarm(AlarmRule rule, Reading reading, boolean raised) {
        public String message() {
            return (raised ? "ALARM " : "clear ") + rule + " : " + reading.name() + " = " + reading.formatted();
        }
    }

    private final List<AlarmRule> rules;
    private final double hysteresis;
    private final Map<AlarmRule, Boolean> active = new HashMap<>();

    public AlarmTracker(List<AlarmRule> rules, double hysteresis) {
        this.rules = List.copyOf(rules);
        this.hysteresis = hysteresis;
    }

    /** Returns the alarms (possibly none) caused by this reading. */
    public List<Alarm> update(Reading r) {
        List<Alarm> out = new ArrayList<>();
        if (!r.valid()) return out;
        for (AlarmRule rule : rules) {
            if (!rule.key().equals(r.key())) continue;
            boolean on = active.getOrDefault(rule, false);
            if (!on && rule.triggeredBy(r.value())) {
                active.put(rule, true);
                out.add(new Alarm(rule, r, true));
            } else if (on && cleared(rule, r.value())) {
                active.put(rule, false);
                out.add(new Alarm(rule, r, false));
            }
        }
        return out;
    }

    private boolean cleared(AlarmRule rule, double v) {
        return rule.op() == AlarmRule.Op.ABOVE ? v < rule.limit() - hysteresis : v > rule.limit() + hysteresis;
    }

    public List<AlarmRule> rules() {
        return rules;
    }
}
