package io.github.hannabeshoy.serialmonitor.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plays back lines recorded earlier, for example with a terminal program, or given as text in a test. */
public class ReplaySource implements DataSource {
    private final BufferedReader in;
    private final String name;

    public ReplaySource(BufferedReader in, String name) {
        this.in = in;
        this.name = name;
    }

    public static ReplaySource of(Path file) throws IOException {
        return new ReplaySource(Files.newBufferedReader(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    public static ReplaySource ofText(String text) {
        return new ReplaySource(new BufferedReader(new StringReader(text)), "text");
    }

    @Override
    public String readLine() throws IOException {
        return in.readLine();
    }

    @Override
    public boolean isLive() {
        return false;                    // a file waits for us
    }

    @Override
    public String describe() {
        return "replay of " + name;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
