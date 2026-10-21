package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class Leb128Test {
    @Test
    public static void unsignedValuesAtEachByteLengthBoundary() throws ModuleFormatException {
        for (int length = 1; length <= 5; length++) {
            long smallest = length == 1 ? 0 : 1L << (7 * (length - 1));
            long largest = Math.min((1L << (7 * length)) - 1, 0xFFFFFFFFL);
            for (long value : new long[] {smallest, largest}) {
                byte[] bytes = unsigned((int) value);
                assertEquals(length, bytes.length);
                assertEquals(length, Leb128.sizeUnsigned((int) value));
                var source = new ByteSource(bytes);
                assertEquals((int) value, source.uleb());
                assertTrue(source.atEnd());
            }
        }
        assertEquals("00", hex(unsigned(0)));
        assertEquals("7F", hex(unsigned(127)));
        assertEquals("80 01", hex(unsigned(128)));
        assertEquals("FF 7F", hex(unsigned(16_383)));
        assertEquals("80 80 01", hex(unsigned(16_384)));
        assertEquals("FF FF FF FF 0F", hex(unsigned(-1)));
    }

    @Test
    public static void signedValuesAtEachByteLengthBoundary() throws ModuleFormatException {
        for (int length = 1; length <= 10; length++) {
            long largest = length == 10 ? Long.MAX_VALUE : (1L << (7 * length - 1)) - 1;
            long smallest = length == 10 ? Long.MIN_VALUE : -(1L << (7 * length - 1));
            for (long value : new long[] {largest, smallest}) {
                byte[] bytes = signed(value);
                assertEquals(length, bytes.length);
                assertEquals(length, Leb128.sizeSigned(value));
                var source = new ByteSource(bytes);
                assertEquals(value, source.sleb64());
                assertTrue(source.atEnd());
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    assertEquals((int) value, new ByteSource(bytes).sleb32());
                }
            }
        }
        assertEquals(Integer.MAX_VALUE, new ByteSource(signed(Integer.MAX_VALUE)).sleb32());
        assertEquals(Integer.MIN_VALUE, new ByteSource(signed(Integer.MIN_VALUE)).sleb32());
        assertEquals("3F", hex(signed(63)));
        assertEquals("C0 00", hex(signed(64)));
        assertEquals("40", hex(signed(-64)));
        assertEquals("BF 7F", hex(signed(-65)));
        assertEquals("FF FF FF FF 07", hex(signed(Integer.MAX_VALUE)));
        assertEquals("80 80 80 80 78", hex(signed(Integer.MIN_VALUE)));
        assertEquals("FF FF FF FF FF FF FF FF FF 00", hex(signed(Long.MAX_VALUE)));
        assertEquals("80 80 80 80 80 80 80 80 80 7F", hex(signed(Long.MIN_VALUE)));
    }

    @Test
    public static void paddedEncodingsAreRejected() {
        rejectUnsigned("ULEB128 value is not minimal", 0x80, 0x00);
        rejectUnsigned("ULEB128 value is not minimal", 0x81, 0x80, 0x00);
        rejectUnsigned("ULEB128 value is not minimal", 0xFF, 0xFF, 0xFF, 0xFF, 0x00);
        rejectSigned32("SLEB128 value is not minimal", 0x80, 0x00);
        rejectSigned32("SLEB128 value is not minimal", 0xFF, 0x7F);
        rejectSigned32("SLEB128 value is not minimal", 0xBF, 0x00);
        rejectSigned64("SLEB128 value is not minimal", 0xC0, 0x80, 0x00);
        rejectSigned64("SLEB128 value is not minimal", 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    public static void valuesThatDoNotFitTheirFieldAreRejected() {
        rejectUnsigned("ULEB128 value does not fit 32 bits", 0x80, 0x80, 0x80, 0x80, 0x10);
        rejectUnsigned("ULEB128 value longer than 5 bytes", 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
        rejectSigned32("SLEB128 value does not fit 32 bits", 0x80, 0x80, 0x80, 0x80, 0x08);
        rejectSigned32("SLEB128 value does not fit 32 bits", 0xFF, 0xFF, 0xFF, 0xFF, 0x77);
        rejectSigned32("SLEB128 value longer than 5 bytes", 0x80, 0x80, 0x80, 0x80, 0x80, 0x00);
        rejectSigned64("SLEB128 value does not fit 64 bits", 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
        rejectSigned64("SLEB128 value does not fit 64 bits", 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00);
    }

    @Test
    public static void truncatedValuesAndErrorsNameTheOffsetOfTheValue() {
        rejectUnsigned("truncated ULEB128 value", 0x80);
        rejectSigned64("truncated SLEB128 value", 0xFF, 0xFF);
        byte[] data = {0x00, 0x00, (byte) 0x80, 0x00};
        var source = new ByteSource(data, 2, 4);
        var error = assertThrows(ModuleFormatException.class, source::uleb);
        assertEquals(2, error.offset());
        assertEquals("offset 0x2: ULEB128 value is not minimal", error.getMessage());
    }

    private static byte[] unsigned(int value) {
        var sink = new ByteSink();
        sink.uleb(value);
        return sink.toByteArray();
    }

    private static byte[] signed(long value) {
        var sink = new ByteSink();
        sink.sleb(value);
        return sink.toByteArray();
    }

    private static void rejectUnsigned(String message, int... bytes) {
        reject(message, bytes, source -> source.uleb());
    }

    private static void rejectSigned32(String message, int... bytes) {
        reject(message, bytes, source -> source.sleb32());
    }

    private static void rejectSigned64(String message, int... bytes) {
        reject(message, bytes, source -> source.sleb64());
    }

    private static void reject(String message, int[] values, Read read) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        var error = assertThrows(ModuleFormatException.class, () -> read.run(new ByteSource(bytes)));
        assertEquals("offset 0x0: " + message, error.getMessage());
        assertEquals(0, error.offset());
    }

    static String hex(byte[] bytes) {
        var text = new StringBuilder();
        for (byte b : bytes) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(String.format("%02X", b & 0xFF));
        }
        return text.toString();
    }

    @FunctionalInterface
    private interface Read {
        void run(ByteSource source) throws ModuleFormatException;
    }
}
