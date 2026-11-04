package io.github.hannabeshoy.serialmonitor.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One line sent by the sensor node, after the checksum has been verified.
 * Immutable: the raw values cannot be changed after parsing.
 *
 * @param node     node name, for example "SN"
 * @param seq      sequence number, increases by one per frame
 * @param uptimeMs milliseconds since the node started
 * @param raw      raw sensor values in the order they were sent, key = sensor key ("T", "D", "L")
 */
public record Frame(String node, long seq, long uptimeMs, Map<String, Long> raw) {

    public Frame {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(raw, "raw");
        if (seq < 0) throw new IllegalArgumentException("seq must not be negative: " + seq);
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));   // defensive copy, keeps order
    }
}
