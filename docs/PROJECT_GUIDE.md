# Project guide

How the serial monitor is built and why, one part at a time. Each section names the Programming Languages 2 topic it practises.

## 1. Data first: records and validation

`Frame` and `Reading` are **records**: immutable data classes whose constructor, accessors, `equals`, `hashCode` and `toString` Java writes. `Frame`'s compact constructor checks its arguments and copies the map into an unmodifiable one, so a frame cannot change after parsing, and frames can be passed between threads without locks.

`FrameException` is a **checked** exception with an enum `Kind` (SYNTAX, CHECKSUM, FIELD). Checked, because every caller of `FrameCodec.parse` must decide what to do with a bad line; it cannot be forgotten. When `Long.parseLong` fails, the `NumberFormatException` is kept as the **cause** (exception chaining).

## 2. One abstract class, three sensors

`Sensor` is **abstract**: it stores the key, name, unit and valid range and has the final method `read(raw, seq, time)`, which calls the abstract `convert(raw)` and the overridable `rawIsValid(raw)`. Each subclass supplies its own physics:

| Class | `convert` | `rawIsValid` |
| --- | --- | --- |
| `Lm35TemperatureSensor` | counts · 5 / 1023 · 100 | 0 ≤ counts ≤ 1023 |
| `UltrasonicDistanceSensor` | µs · 0.0343 / 2 | µs > 0 (0 = timeout) |
| `LightSensor` | counts · 100 / 1023 | 0 ≤ counts ≤ 1023 |

The monitor only knows `Sensor`. Which `convert` runs is decided at run time by the object (**dynamic binding** through the class's vtable). Adding a fourth sensor means one new subclass and one `registry.add(...)`, nothing else. `read` is `final` so no subclass can skip the range check.

## 3. Sources behind an interface

`DataSource` has three methods (`readLine`, `describe`, `close`) and one **default method**, `isLive()`. `SerialPortSource`, `SimulatedSource` and `ReplaySource` implement it. The monitor and all tests are written against the interface, which is what makes all 61 tests possible without an Arduino.

`SimulatedSource` produces exactly what the node would send, through the same `FrameCodec.format`, and can inject the four faults a real cable shows: a changed character, a garbage line, a missing frame and a sensor timeout. A fixed **seed** makes every run repeatable, so tests can expect exact results.

`SerialPortSource` uses jSerialComm in semi-blocking mode: a read waits at most 500 ms. That keeps the thread responsive to `close()` and lets it notice an unplugged board.

## 4. Two threads and a queue

```text
reader thread:      readLine → parse → queue.offer()      (producer)
dispatcher thread:  queue.take() → convert → stats → alarms → listeners   (consumer)
```

Why two threads? A serial port keeps sending whether we read or not; the operating system's buffer is a few kilobytes. If the CSV file or the window were slow and the same thread had to wait for them, bytes would be lost silently. With a **bounded `ArrayBlockingQueue`** in between, the reader only parses and moves on.

- For a **live** source the reader uses `offer`, which never blocks. If the queue is full the frame is counted as **dropped**, and the number of drops travels with the next queued frame, so the dispatcher does not report our own drops as frames lost on the cable.
- For a **file or a fast simulation** the reader uses `put`, which waits for space (**back-pressure**). Nothing is dropped.
- The end of the stream is a special `EndEvent` in the same queue, so the dispatcher finishes everything that came before it and then calls `onStopped` exactly once.

The queue holds a **sealed interface** with three record types, and the dispatcher uses a **pattern-matching switch** over it. The compiler checks that every case is handled.

Shared counters are `AtomicLong` (lock-free, safe from both threads). `RollingStats` is read by the GUI thread and written by the dispatcher, so its methods are `synchronized`: it is a monitor in the textbook sense. Listeners are kept in a `CopyOnWriteArrayList`, so one can be added while events are being delivered. `stop()` closes the source (which unblocks `readLine`) and interrupts the reader; `await` waits on a `CountDownLatch`.

## 5. Events: the listener pattern

`MonitorListener` has six methods, all with empty **default** bodies, so each listener overrides only what it needs. `ConsolePrinter`, `CsvLogger` and `MonitorWindow` are three independent listeners. `Monitor` calls them in a loop that catches `RuntimeException`, so a broken listener is reported once and cannot stop the others. This is the observer pattern, the same model Swing uses for buttons.

## 6. Alarms with hysteresis

`AlarmRule` is a record built from text such as `T>30` with a regular expression. Its `Op` enum gives each constant its own `holds` method (a **constant-specific method body**). `AlarmTracker` remembers whether each rule is active: it raises an alarm when the value enters the zone and clears it only after the value has moved 0.5 units back past the limit. Without that, a temperature sitting at 30.0 °C with 0.49 °C ADC steps would raise and clear an alarm on every reading.

## 7. Statistics with streams

`RollingStats` keeps the last 120 values in an `ArrayDeque` and computes min, max and mean with `DoubleSummaryStatistics` from a stream, and the standard deviation with a second stream pass. The summary is returned as an immutable record, a safe snapshot for another thread.

## 8. Files

`CsvLogger` writes through a `BufferedWriter` and quotes cells by RFC 4180, so a bad line containing commas cannot break the columns. It is `AutoCloseable` and used in try-with-resources in the tests. Listener methods cannot throw checked exceptions, so an `IOException` is wrapped in `UncheckedIOException`.

### Sessions: sorting, text export and serialization

`Session` records a run as a `MonitorListener` (only when `--save` or `--export` is given) and implements the small interface `SortableReadings`. Its three sort methods use the three ways of writing a `Comparator`: the separate class `ReadingComparatorByValue` (reusable and tested on its own; highest value first, invalid readings last, `Double.compare` instead of subtraction), the private inner class `ArrivalOrder`, and an anonymous class for the order by sensor name. Sorting by value groups by sensor key first, so °C, cm and % are never compared with each other. `List.sort` is stable and sorts in place.

`exportText` writes a report with a `PrintWriter` in try-with-resources. A `PrintWriter` never throws, so `checkError()` turns a failed write into an `IOException`. Numbers use `Locale.ROOT`, so the file has a decimal point on every computer.

`save` and `load` use `ObjectOutputStream` and `ObjectInputStream`. Serialization writes the whole object graph, so `Reading` and `MonitorStats` are `Serializable` too. The live `DataSource` is `transient`: a serial port is an operating-system handle that cannot be written to a file, so only its description is kept. `serialVersionUID` is fixed, so a file from an incompatible version is rejected instead of read wrongly. `load` is static (it makes the object), `save` is an instance method (it writes this object), and the loaded session equals the saved one but is a new object. Because reading a file builds objects, `load` sets an `ObjectInputFilter` that accepts only the session's own classes.

## 9. The window

`MonitorWindow` follows Swing's rule: components are touched only on the **event dispatch thread**. Listener methods (dispatcher thread) store values in `ConcurrentHashMap`s or post with `SwingUtilities.invokeLater`; a `javax.swing.Timer` refreshes the table and the plot four times per second. The table model is an **inner class** that reads the window's maps; closing the window is handled by an **anonymous** `WindowAdapter`; the Stop button uses a **lambda** as its `ActionListener`. `TrendPanel` draws the plot itself with Java 2D.

## 10. The command line

`Options` is a record built by a parser with a `switch` on the argument; every mistake gives an `IllegalArgumentException` with a clear message, and `Main` prints the usage text and exits with code 2. `Main.run` takes a `PrintStream`, so a test can run the whole program and read what it printed. A shutdown hook stops the monitor cleanly on Ctrl+C, so the CSV is flushed.

## Testing without hardware, then with it

1. Unit tests for each class with hand-calculated values (`SensorTest`, `FrameCodecTest`, `StatsTest`).
2. End-to-end tests with `SimulatedSource` and text replays, including the timing-sensitive cases: a fast stop, a slow listener on a live source.
3. The Arduino sketch, compiled on a PC with small stand-ins for the Arduino functions; its output was replayed through the program and parsed without errors.
4. Lab day (planned): flash the sketch, run `--list-ports`, then `--port COMx --gui --log lab.csv`; check the temperature against a thermometer, the distance against a ruler at 10, 30 and 100 cm, and cover the LDR. Unplug the USB cable while running to check the error path.

## Why JUnit 4

The tests use JUnit 4.13 (`org.junit.Test`, `assertThrows`, `TemporaryFolder`) because it needs one jar and runs everywhere, including the lab PCs. Moving to JUnit 5 means changing the imports and `@Rule` to `@TempDir`.
