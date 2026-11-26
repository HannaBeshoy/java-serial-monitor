package io.github.hannabeshoy.serialmonitor.session;

import io.github.hannabeshoy.serialmonitor.sensor.Reading;

import java.util.Comparator;

/**
 * Orders readings by value, highest first. A separate class, so it can be reused and tested on
 * its own. Invalid readings have no value (NaN) and always go last; equal values keep the
 * earlier frame first.
 *
 * <p>The sign rule of {@code compare(a, b)}: negative puts {@code a} first, positive puts
 * {@code b} first, zero means equal. {@link Double#compare} gives the sign without the overflow
 * and rounding problems of subtracting the values.
 */
public class ReadingComparatorByValue implements Comparator<Reading> {

    @Override
    public int compare(Reading a, Reading b) {
        if (a.valid() != b.valid()) return a.valid() ? -1 : 1;
        int c = Double.compare(b.value(), a.value());        // b before a: descending
        return c != 0 ? c : Long.compare(a.seq(), b.seq());
    }
}
