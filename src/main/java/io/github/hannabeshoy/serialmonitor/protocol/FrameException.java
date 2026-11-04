package io.github.hannabeshoy.serialmonitor.protocol;

/** A line that is not a valid frame. Checked: every caller must decide what to do with a bad line. */
public class FrameException extends Exception {

    /** Why the line was rejected. */
    public enum Kind {
        /** Missing '$' or '*', wrong number of fields, or an empty line. */
        SYNTAX,
        /** The checksum does not match: the line was damaged on the way. */
        CHECKSUM,
        /** A field has the right place but a wrong value (not a number, bad key). */
        FIELD
    }

    private final Kind kind;

    public FrameException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FrameException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
