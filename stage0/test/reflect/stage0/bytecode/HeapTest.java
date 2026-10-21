package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class HeapTest {
    @Test
    public static void theWriterStartsWithTheEmptyEntryAndAddsEachEntryOnce() throws ModuleFormatException {
        var idents = new HeapWriter(SectionId.IDENTS);
        assertEquals("00", Leb128Test.hex(idents.toByteArray()));
        assertEquals(1, idents.ident("abc"));
        assertEquals(0, idents.ident(""));
        assertEquals(5, idents.ident("\u00E9"));
        assertEquals(1, idents.ident(new String("abc")));
        assertEquals("00 03 61 62 63 02 C3 A9", Leb128Test.hex(idents.toByteArray()));
        var literals = new HeapWriter(SectionId.LITERALS);
        assertEquals(1, literals.literal("hi"));
        assertEquals(6, literals.literal("\uD800"));
        assertEquals(0, literals.literal(""));
        assertEquals("00 02 68 00 69 00 01 00 D8", Leb128Test.hex(literals.toByteArray()));
        var blobs = new HeapWriter(SectionId.BLOBS);
        assertEquals(1, blobs.blob(new byte[] {0x20, 0x08}));
        assertEquals(1, blobs.blob(new byte[] {0x20, 0x08}));
        assertEquals(4, blobs.blob(new byte[] {0x20}));
        assertEquals("00 02 20 08 01 20", Leb128Test.hex(blobs.toByteArray()));
    }

    @Test
    public static void identifiersHaveAtMost65535BytesAndNoUnpairedSurrogate() throws ModuleFormatException {
        var idents = new HeapWriter(SectionId.IDENTS);
        assertEquals(1, idents.ident("a".repeat(65_535)));
        var error = assertThrows(ModuleFormatException.class, () -> idents.ident("a".repeat(65_536)));
        assertEquals("an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)", error.getMessage());
        assertThrows(ModuleFormatException.class, () -> idents.ident("\uD800"));
    }

    @Test
    public static void theReaderResolvesEachReference() throws ModuleFormatException {
        var heap = new HeapReader(SectionId.IDENTS, source(0x00, 0x03, 0x61, 0x62, 0x63, 0x02, 0xC3, 0xA9));
        assertEquals("", heap.ident(0, 100));
        assertEquals("abc", heap.ident(1, 100));
        assertEquals("abc", heap.ident(1, 100));
        assertEquals("\u00E9", heap.ident(5, 100));
        heap.checkAllReferenced();
        var literals = new HeapReader(SectionId.LITERALS, source(0x00, 0x01, 0x00, 0xD8));
        assertEquals("\uD800", literals.literal(1, 100));
        literals.checkAllReferenced();
        var blobs = new HeapReader(SectionId.BLOBS, source(0x00, 0x02, 0x20, 0x08));
        ByteSource blob = blobs.blob(1, 100);
        assertEquals(2, blob.position());
        assertEquals("20 08", Leb128Test.hex(blob.bytes(2)));
        assertTrue(blob.atEnd());
        blobs.checkAllReferenced();
    }

    @Test
    public static void referencesMustStartAnEntryAndMeetTheEntriesInHeapOrder() throws ModuleFormatException {
        var heap = new HeapReader(SectionId.IDENTS, source(0x00, 0x03, 0x61, 0x62, 0x63, 0x02, 0xC3, 0xA9));
        reject("offset 0x64: IDENTS offset 0x2 is not the start of an entry", () -> heap.ident(2, 100));
        reject("offset 0x64: IDENTS offset 0xFFFFFFFF is not the start of an entry", () -> heap.ident(-1, 100));
        reject("offset 0x64: IDENTS entry 0x5 is referenced before the entry 0x1; entries follow the order of"
                + " their first reference (BC-24)", () -> heap.ident(5, 100));
        heap.ident(1, 100);
        reject("offset 0x5: IDENTS entry is not referenced (BC-24)", heap::checkAllReferenced);
    }

    @Test
    public static void malformedHeapsAreRejectedAtTheirOffset() {
        malformed(SectionId.IDENTS, "offset 0x0: IDENTS does not start with its empty entry, one zero byte (BC-02)",
                0x01, 0x61);
        malformed(SectionId.BLOBS, "offset 0x0: BLOBS does not start with its empty entry, one zero byte (BC-02)");
        malformed(SectionId.IDENTS, "offset 0x3: IDENTS entry repeats the entry 0x1 (BC-24)", 0x00, 0x01, 0x61, 0x01, 0x61);
        malformed(SectionId.LITERALS, "offset 0x1: LITERALS entry repeats the entry 0x0 (BC-24)", 0x00, 0x00);
        malformed(SectionId.IDENTS, "offset 0x2: UTF-8 text of 3 bytes runs past the end of its data", 0x00, 0x03, 0x61);
        malformed(SectionId.IDENTS, "offset 0x2: invalid UTF-8 lead byte 0xFF", 0x00, 0x01, 0xFF);
        malformed(SectionId.IDENTS, "offset 0x1: ULEB128 value is not minimal", 0x00, 0x81, 0x00, 0x61);
        malformed(SectionId.IDENTS, "offset 0x1: an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)",
                0x00, 0x80, 0x80, 0x04);
        malformed(SectionId.LITERALS, "offset 0x2: UTF-16LE text of 1 code units runs past the end of its data",
                0x00, 0x01, 0x61);
        malformed(SectionId.BLOBS, "offset 0x1: a blob of 5 bytes runs past the end of BLOBS", 0x00, 0x05, 0x01);
    }

    @Test
    public static void blobsCompareAsUnsignedBytesThenByLength() {
        assertTrue(Blob.compare(new byte[] {0x7F}, new byte[] {(byte) 0x80}) < 0);
        assertTrue(Blob.compare(new byte[] {1}, new byte[] {1, 0}) < 0);
        assertTrue(Blob.compare(new byte[] {2}, new byte[] {1, 0}) > 0);
        assertEquals(0, Blob.compare(new byte[] {1, 2}, new byte[] {1, 2}));
        assertEquals(new Blob(new byte[] {1, 2}), new Blob(new byte[] {1, 2}));
        assertEquals(new Blob(new byte[] {1, 2}).hashCode(), new Blob(new byte[] {1, 2}).hashCode());
        assertTrue(!new Blob(new byte[] {1}).equals(new Blob(new byte[] {2})));
    }

    private static ByteSource source(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new ByteSource(bytes);
    }

    private static void malformed(int section, String message, int... values) {
        reject(message, () -> new HeapReader(section, source(values)));
    }

    private static void reject(String message, reflect.stage0.test.Assertions.Action action) {
        assertEquals(message, assertThrows(ModuleFormatException.class, action).getMessage());
    }
}
