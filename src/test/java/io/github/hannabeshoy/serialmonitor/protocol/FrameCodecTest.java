package io.github.hannabeshoy.serialmonitor.protocol;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class FrameCodecTest {

    static final String GOOD = "$SN,42,123456,T=61,D=2001,L=512*63";

    @Test
    public void parsesAValidLine() throws FrameException {
        Frame f = FrameCodec.parse(GOOD);
        assertEquals("SN", f.node());
        assertEquals(42, f.seq());
        assertEquals(123456, f.uptimeMs());
        assertEquals(Long.valueOf(61), f.raw().get("T"));
        assertEquals(Long.valueOf(2001), f.raw().get("D"));
        assertEquals(Long.valueOf(512), f.raw().get("L"));
        assertEquals("[T, D, L]", f.raw().keySet().toString());     // order kept
    }

    @Test
    public void checksumIsXorOfTheBody() {
        assertEquals("63", FrameCodec.checksum("SN,42,123456,T=61,D=2001,L=512"));
        assertEquals("00", FrameCodec.checksum(""));
        assertEquals("03", FrameCodec.checksum("AB"));               // 0x41 ^ 0x42
    }

    @Test
    public void formatAndParseAreInverse() throws FrameException {
        Map<String, Long> raw = new LinkedHashMap<>();
        raw.put("T", 0L);
        raw.put("X1", 99999L);
        Frame f = new Frame("node_7", 0, 5, raw);
        assertEquals(f, FrameCodec.parse(FrameCodec.format(f)));
    }

    @Test
    public void toleratesCrLfAndSpacesAndLowerCaseChecksum() throws FrameException {
        assertEquals(42, FrameCodec.parse("  " + GOOD + "\r").seq());
        Map<String, Long> raw = Map.of("T", 1L);
        String line = FrameCodec.format(new Frame("SN", 1, 1, raw));
        String lower = line.substring(0, line.length() - 2) + line.substring(line.length() - 2).toLowerCase();
        assertEquals(1, FrameCodec.parse(lower).seq());
    }

    @Test
    public void damagedLineFailsTheChecksum() {
        String damaged = GOOD.replace("T=61", "T=71");
        FrameException e = assertThrows(FrameException.class, () -> FrameCodec.parse(damaged));
        assertEquals(FrameException.Kind.CHECKSUM, e.getKind());
    }

    @Test
    public void syntaxErrors() {
        for (String bad : new String[]{"", "   ", "SN,1,2,T=3*00", "$SN,1,2,T=3", "$SN,1,2,T=3*0", "$*00", "garbage"}) {
            FrameException e = assertThrows(bad, FrameException.class, () -> FrameCodec.parse(bad));
            assertEquals(bad, FrameException.Kind.SYNTAX, e.getKind());
        }
        assertEquals(FrameException.Kind.SYNTAX, assertThrows(FrameException.class, () -> FrameCodec.parse(null)).getKind());
        assertEquals(FrameException.Kind.SYNTAX,
                assertThrows(FrameException.class, () -> FrameCodec.parse("$" + "A".repeat(300) + "*00")).getKind());
    }

    @Test
    public void fieldErrorsHaveAValidChecksumButBadContent() {
        String[] bodies = {"SN,x,2,T=3", "SN,1,2,T=abc", "SN,1,2,T3", "SN,1,2,t=3", "SN,1,2,T=1,T=2", "S N,1,2,T=3", "SN,-1,2,T=3"};
        for (String body : bodies) {
            String line = "$" + body + "*" + FrameCodec.checksum(body);
            FrameException e = assertThrows(line, FrameException.class, () -> FrameCodec.parse(line));
            assertEquals(line, FrameException.Kind.FIELD, e.getKind());
        }
    }

    @Test
    public void notANumberKeepsTheCause() {
        String body = "SN,1,2,T=1.5";
        FrameException e = assertThrows(FrameException.class, () -> FrameCodec.parse("$" + body + "*" + FrameCodec.checksum(body)));
        assertEquals(NumberFormatException.class, e.getCause().getClass());
    }

    @Test
    public void frameIsImmutable() throws FrameException {
        Frame f = FrameCodec.parse(GOOD);
        assertThrows(UnsupportedOperationException.class, () -> f.raw().put("Z", 1L));
    }
}
