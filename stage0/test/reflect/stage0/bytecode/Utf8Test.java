package reflect.stage0.bytecode;

import java.nio.charset.StandardCharsets;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;

public final class Utf8Test {
    // Each byte length, its boundaries, the edges of the surrogate range and a surrogate pair.
    private static final String[] VALID = {
        "", "abc", "\u007F", "\u0080", "\u00E9", "\u07FF", "\u0800", "\u20AC", "\uD7FF", "\uE000", "\uFFFF",
        "\uD800\uDC00", "\uD834\uDD1E", "\uDBFF\uDFFF", "reflect.core.Int32", "a\u00E9\u20AC\uD834\uDD1Ez",
    };

    @Test
    public static void encodesAsStringGetBytesDoes() throws ModuleFormatException {
        for (String text : VALID) {
            assertEquals(Leb128Test.hex(text.getBytes(StandardCharsets.UTF_8)), Leb128Test.hex(Utf8.encode(text)));
        }
        assertEquals("F0 9D 84 9E", Leb128Test.hex(Utf8.encode("\uD834\uDD1E")));
        assertEquals("F4 8F BF BF", Leb128Test.hex(Utf8.encode("\uDBFF\uDFFF")));
    }

    @Test
    public static void decodesWhatItEncodes() throws ModuleFormatException {
        for (String text : VALID) {
            byte[] bytes = Utf8.encode(text);
            var source = new ByteSource(bytes);
            assertEquals(text, Utf8.decode(source, bytes.length));
            assertEquals(bytes.length, source.position());
        }
    }

    @Test
    public static void unpairedSurrogatesHaveNoEncoding() {
        unencodable("unpaired surrogate 0xD800 at index 0 has no UTF-8 form", "\uD800");
        unencodable("unpaired surrogate 0xDC00 at index 1 has no UTF-8 form", "a\uDC00b");
        unencodable("unpaired surrogate 0xD800 at index 0 has no UTF-8 form", "\uD800\uD800");
        unencodable("unpaired surrogate 0xDFFF at index 0 has no UTF-8 form", "\uDFFF\uD800");
        unencodable("unpaired surrogate 0xDBFF at index 2 has no UTF-8 form", "ab\uDBFF");
        // String.getBytes replaces the unit with '?', which would give two strings one encoding.
        assertEquals("3F", Leb128Test.hex("\uD800".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public static void malformedSequencesAreRejectedAtTheirOffset() {
        malformed(0, "invalid UTF-8 lead byte 0xC0", 0xC0, 0x80);
        malformed(0, "invalid UTF-8 lead byte 0xC1", 0xC1, 0xBF);
        malformed(0, "invalid UTF-8 lead byte 0xF5", 0xF5, 0x80, 0x80, 0x80);
        malformed(0, "invalid UTF-8 lead byte 0xFF", 0xFF);
        malformed(1, "invalid UTF-8 lead byte 0x80", 0x41, 0x80);
        malformed(0, "overlong UTF-8 sequence", 0xE0, 0x80, 0x80);
        malformed(0, "overlong UTF-8 sequence", 0xE0, 0x9F, 0xBF);
        malformed(0, "overlong UTF-8 sequence", 0xF0, 0x8F, 0xBF, 0xBF);
        malformed(0, "UTF-8 sequence encodes the surrogate 0xD800", 0xED, 0xA0, 0x80);
        malformed(0, "UTF-8 sequence encodes the surrogate 0xDFFF", 0xED, 0xBF, 0xBF);
        malformed(0, "UTF-8 sequence encodes a value above U+10FFFF", 0xF4, 0x90, 0x80, 0x80);
        malformed(2, "truncated UTF-8 sequence", 0x61, 0x62, 0xE2, 0x82);
        malformed(0, "invalid UTF-8 continuation byte 0x41", 0xC2, 0x41);
        // new String replaces malformed input with U+FFFD, which would give one string two encodings.
        assertEquals("\uFFFD\uFFFD", new String(new byte[] {(byte) 0xC0, (byte) 0x80}, StandardCharsets.UTF_8));
    }

    @Test
    public static void decodingStaysInsideItsLength() throws ModuleFormatException {
        byte[] bytes = {0x61, (byte) 0xC3, (byte) 0xA9, 0x62};
        var source = new ByteSource(bytes);
        assertEquals("a", Utf8.decode(source, 1));
        assertEquals("\u00E9", Utf8.decode(source, 2));
        var split = new ByteSource(bytes);
        var error = assertThrows(ModuleFormatException.class, () -> Utf8.decode(split, 2));
        assertEquals("offset 0x1: truncated UTF-8 sequence", error.getMessage());
        var past = assertThrows(ModuleFormatException.class, () -> Utf8.decode(new ByteSource(bytes), 5));
        assertEquals("offset 0x0: UTF-8 text of 5 bytes runs past the end of its data", past.getMessage());
    }

    private static void unencodable(String message, String text) {
        assertEquals(message, assertThrows(ModuleFormatException.class, () -> Utf8.encode(text)).getMessage());
    }

    private static void malformed(int offset, String message, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        var error = assertThrows(ModuleFormatException.class, () -> Utf8.decode(new ByteSource(bytes), bytes.length));
        assertEquals(offset, error.offset());
        assertEquals("offset " + ModuleFormatException.hex(offset) + ": " + message, error.getMessage());
    }
}
