package io.github.hannabeshoy.serialmonitor.core;

/**
 * Counters of one monitoring session, as an immutable snapshot.
 *
 * @param lines    lines received
 * @param frames   valid frames
 * @param bad      rejected lines (syntax, checksum or field errors)
 * @param checksum of those, checksum errors
 * @param lost     frames missing according to the sequence numbers
 * @param dropped  frames thrown away because the queue was full (the PC was too slow)
 * @param unknown  readings whose key has no registered sensor
 * @param error    the I/O error that ended the session, or null
 */
public record MonitorStats(long lines, long frames, long bad, long checksum, long lost, long dropped, long unknown, String error) {

    /** Share of lines that were valid frames, 0..1. */
    public double quality() {
        return lines == 0 ? 1.0 : (double) frames / lines;
    }

    public String summary() {
        return String.format("lines %d, frames %d, bad %d (checksum %d), lost %d, dropped %d, unknown keys %d, quality %.1f %%%s",
                lines, frames, bad, checksum, lost, dropped, unknown, quality() * 100, error == null ? "" : ", error: " + error);
    }
}
