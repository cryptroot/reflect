package reflect.stage0.bytecode;

import java.nio.charset.StandardCharsets;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;

public final class Utf16Test {
    @Test
    public static void encodesAsStringGetBytesDoes() {
        for (String text : new String[] {"", "abc", "\u00E9\u20AC", "\uFFFF", "\uD834\uDD1E", "x\uDBFF\uDFFFy"}) {
            assertEquals(Leb128Test.hex(text.getBytes(StandardCharsets.UTF_16LE)), Leb128Test.hex(Utf16.encode(text)));
        }
        assertEquals("34 D8 1E DD", Leb128Test.hex(Utf16.encode("\uD834\uDD1E")));
    }

    @Test
    public static void unpairedSurrogatesRoundTrip() throws ModuleFormatException {
        for (String text : new String[] {"\uD800", "x\uDFFFy", "\uDC00\uD800", "\uD800\uD800", "a\uDBFF"}) {
            byte[] bytes = Utf16.encode(text);
            assertEquals(2 * text.length(), bytes.length);
            var source = new ByteSource(bytes);
            assertEquals(text, Utf16.decode(source, text.length()));
            assertEquals(bytes.length, source.position());
        }
        assertEquals("00 D8", Leb128Test.hex(Utf16.encode("\uD800")));
        // String.getBytes replaces the unpaired unit with U+FFFD, so LITERALS cannot use it.
        assertEquals("FD FF", Leb128Test.hex("\uD800".getBytes(StandardCharsets.UTF_16LE)));
    }

    @Test
    public static void decodingNeedsTwoBytesPerUnit() {
        byte[] bytes = {0x61, 0x00, 0x62};
        var error = assertThrows(ModuleFormatException.class, () -> Utf16.decode(new ByteSource(bytes), 2));
        assertEquals("offset 0x0: UTF-16LE text of 2 code units runs past the end of its data", error.getMessage());
    }
}
