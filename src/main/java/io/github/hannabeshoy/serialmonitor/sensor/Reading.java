package io.github.hannabeshoy.serialmonitor.sensor;

import java.io.Serializable;
import java.time.Instant;

/**
 * One converted measurement.
 *
 * @param key   sensor key from the frame
 * @param name  readable sensor name
 * @param value value in {@code unit}; NaN when the sensor reported no measurement
 * @param unit  unit of the value
 * @param seq   sequence number of the frame it came from
 * @param time  when the PC received the frame
 * @param valid false when the raw value meant "no measurement" or the value is physically impossible
 *
 * <p>Serializable, so a saved {@code Session} can hold its readings: every field is a String, a number or an Instant.
 */
public record Reading(String key, String name, double value, String unit, long seq, Instant time, boolean valid)
        implements Serializable {

    /** Value with one decimal and its unit, for example "29.8 °C". */
    public String formatted() {
        return valid ? String.format("%.1f %s", value, unit) : "invalid";
    }
}
