package io.github.hannabeshoy.serialmonitor.session;

/**
 * Something that holds readings and can put them in a different order. Each method sorts in
 * place, so the next call to read the readings sees the new order.
 */
public interface SortableReadings {

    /** In the order the frames arrived: by sequence number, then by sensor key. */
    void sortByTime();

    /** Per sensor, highest value first; invalid readings (no measurement) go last. */
    void sortByValue();

    /** Alphabetically by readable sensor name, ignoring case; for one sensor, in arrival order. */
    void sortBySensorName();
}
