package io.github.hannabeshoy.serialmonitor.source;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A real serial port (the Arduino's USB port shows up as COM3 on Windows or /dev/ttyACM0 on Linux).
 * Uses jSerialComm. Settings are 8 data bits, no parity, 1 stop bit (8N1), like Serial.begin() on the Arduino.
 */
public class SerialPortSource implements DataSource {
    private final SerialPort port;
    private final InputStream in;
    private final int baud;
    private final StringBuilder line = new StringBuilder();
    private volatile boolean closed = false;

    /**
     * Opens the port.
     *
     * @throws IOException if the port does not exist or is used by another program (for example the Arduino IDE's serial monitor)
     */
    public SerialPortSource(String portName, int baud) throws IOException {
        this.baud = baud;
        this.port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // semi-blocking: read() waits at most 500 ms, so close() is noticed quickly
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0);
        if (!port.openPort())
            throw new IOException("cannot open " + portName + " (wrong name, unplugged, or open in another program?)");
        this.in = port.getInputStream();
    }

    /** Names of the serial ports on this computer, with their descriptions. */
    public static List<String> listPorts() {
        List<String> names = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts())
            names.add(p.getSystemPortName() + "  " + p.getDescriptivePortName());
        return names;
    }

    @Override
    public String readLine() throws IOException {
        while (!closed) {
            int b;
            try {
                b = in.read();
            } catch (IOException e) {
                if (closed) return null;                     // close() from another thread
                if (e.getClass().getSimpleName().contains("Timeout")) continue;   // SerialPortTimeoutException: no byte within 500 ms
                throw e;
            }
            if (b < 0) {
                if (!port.isOpen()) return null;             // unplugged
                continue;
            }
            if (b == '\n') {
                String s = line.toString();
                line.setLength(0);
                return s;
            }
            if (b != '\r') line.append((char) b);
            if (line.length() > 1000) line.setLength(0);     // no newline for 1000 bytes: wrong baud rate
        }
        return null;
    }

    @Override
    public String describe() {
        return port.getSystemPortName() + " @ " + baud;
    }

    @Override
    public void close() {
        closed = true;
        port.closePort();
    }
}
