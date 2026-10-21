package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class BytesTest {
    @Test
    public static void fixedWidthValuesAreLittleEndian() throws ModuleFormatException {
        var sink = new ByteSink();
        sink.u8(0x1AB);
        sink.u16(0x1234);
        sink.u32(0x89ABCDEF);
        sink.u64(0x0123456789ABCDEFL);
        sink.zeros(2);
        sink.bytes(new byte[] {7, 8});
        byte[] bytes = sink.toByteArray();
        assertEquals(sink.size(), bytes.length);
        assertEquals("AB 34 12 EF CD AB 89 EF CD AB 89 67 45 23 01 00 00 07 08", Leb128Test.hex(bytes));
        var source = new ByteSource(bytes);
        assertEquals(0xAB, source.u8());
        assertEquals(0x1234, source.u16());
        assertEquals(0x89ABCDEF, source.u32());
        assertEquals(0x0123456789ABCDEFL, source.u64());
        assertEquals("00 00 07 08", Leb128Test.hex(source.bytes(4)));
        assertTrue(source.atEnd());
    }

    @Test
    public static void theSinkGrowsPastItsFirstArray() {
        var sink = new ByteSink();
        for (int i = 0; i < 1000; i++) {
            sink.u8(i);
        }
        byte[] bytes = sink.toByteArray();
        assertEquals(1000, bytes.length);
        assertEquals((byte) 999, bytes[999]);
    }

    @Test
    public static void readsStopAtTheEndOfTheRange() {
        byte[] data = {1, 2, 3, 4, 5, 6};
        var source = new ByteSource(data, 1, 4);
        var error = assertThrows(ModuleFormatException.class, source::u32);
        assertEquals("offset 0x1: needs 4 bytes, but 3 remain", error.getMessage());
        assertEquals(3, source.remaining());
        assertThrows(IllegalArgumentException.class, () -> new ByteSource(data, 4, 7));
    }
}
