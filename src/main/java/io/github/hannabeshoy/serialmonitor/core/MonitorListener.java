package io.github.hannabeshoy.serialmonitor.core;

import io.github.hannabeshoy.serialmonitor.protocol.Frame;
import io.github.hannabeshoy.serialmonitor.protocol.FrameException;
import io.github.hannabeshoy.serialmonitor.sensor.Reading;
import io.github.hannabeshoy.serialmonitor.stats.AlarmTracker.Alarm;

/**
 * Receives events from a {@link Monitor}. Every method has an empty default, so a listener
 * overrides only what it needs. All methods are called on the monitor's dispatcher thread:
 * a GUI listener must hand work to the Swing event thread itself.
 */
public interface MonitorListener {

    /** A valid frame arrived; called before its readings. */
    default void onFrame(Frame frame) { }

    /** One converted value from a frame. */
    default void onReading(Reading reading) { }

    /** A line was rejected. */
    default void onBadLine(String line, FrameException error) { }

    /** Sequence numbers jumped: {@code missing} frames were lost between two received ones. */
    default void onLostFrames(long afterSeq, long missing) { }

    /** An alarm was raised or cleared. */
    default void onAlarm(Alarm alarm) { }

    /** The source ended or the monitor was stopped. Called exactly once. */
    default void onStopped(MonitorStats stats) { }
}
