package io.github.hannabeshoy.serialmonitor.source;

import java.io.IOException;

/**
 * Where lines come from: a real serial port, a recorded file or a simulation.
 * The monitor depends only on this interface, so it can be tested without hardware.
 */
public interface DataSource extends AutoCloseable {

    /**
     * Blocks until a full line is available.
     *
     * @return the line without its line ending, or {@code null} when the source has ended or was closed
     * @throws IOException if the underlying device fails
     */
    String readLine() throws IOException;

    /**
     * True for a device that keeps sending whether or not we read (a serial port): the monitor must never
     * slow it down and drops frames instead. False for a file or a fast simulation, where the monitor
     * can simply wait for queue space. Default: true.
     */
    default boolean isLive() {
        return true;
    }

    /** A short description for logs and the window title, for example "COM3 @ 115200". */
    String describe();

    /** Releases the device. Must also make a blocked {@link #readLine()} return soon. */
    @Override
    void close() throws IOException;
}
