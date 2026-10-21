package reflect.stage0.bytecode;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class ModuleFileTest {
    private static final int UNKNOWN_ANCILLARY = 0x80000005;

    @Test
    public static void sectionsFollowTheHeaderAndTheDirectoryInIdOrder() throws Exception {
        byte[] file = ModuleFile.write(0, sections(new Section(SectionId.TABLES, new byte[] {1, 2, 3}),
                new Section(SectionId.IDENTS, new byte[] {0})));
        assertEquals("52 46 4C 43 00 00 01 00 00 00 00 00", Leb128Test.hex(Arrays.copyOfRange(file, 0, 12)));
        assertEquals(16, count(file, 12, 28, (byte) 0));
        assertEquals(2L, u32(file, 28));
        // Two entries end the directory at 88, a multiple of 8, so IDENTS starts there.
        assertEquals("1 88 1 4 96 3", u32(file, 64) + " " + u32(file, 68) + " " + u32(file, 72) + " "
                + u32(file, 76) + " " + u32(file, 80) + " " + u32(file, 84));
        assertEquals(99, file.length);
        assertEquals(0, (int) file[88]);
        assertEquals(7, count(file, 89, 96, (byte) 0));
        assertEquals("01 02 03", Leb128Test.hex(Arrays.copyOfRange(file, 96, 99)));
        var expected = MessageDigest.getInstance("SHA-256");
        expected.update(Arrays.copyOfRange(file, 4, 28));
        expected.update(new byte[] {1, 0, 0, 0, 1, 0, 0, 0, 0});
        expected.update(new byte[] {4, 0, 0, 0, 3, 0, 0, 0, 1, 2, 3});
        assertEquals(Leb128Test.hex(expected.digest()), Leb128Test.hex(Arrays.copyOfRange(file, 32, 64)));
    }

    @Test
    public static void readingGivesBackTheSectionsAndTheFlags() throws Exception {
        byte[] file = ModuleFile.write(Header.FLAG_UNSAFE, sections(new Section(SectionId.IDENTS, new byte[] {0}),
                new Section(SectionId.CODE, new byte[] {9, 8})));
        ModuleFile read = ModuleFile.read(file);
        assertEquals(Header.FLAG_UNSAFE, read.flags());
        assertEquals(2, read.sectionCount());
        assertEquals(SectionId.CODE, read.sectionId(1));
        assertEquals(1, read.find(SectionId.CODE));
        assertEquals(-1, read.find(SectionId.TABLES));
        assertEquals("09 08", Leb128Test.hex(read.content(1)));
        assertEquals(96, read.open(1).position());
        assertEquals(Leb128Test.hex(Arrays.copyOfRange(file, 32, 64)), Leb128Test.hex(read.contentHash()));
    }

    @Test
    public static void anUnknownAncillarySectionIsWrittenBackByteForByte() throws Exception {
        byte[] unknown = new byte[13];
        new Random(5).nextBytes(unknown);
        byte[] file = ModuleFile.write(0, sections(new Section(UNKNOWN_ANCILLARY, unknown),
                new Section(SectionId.IDENTS, new byte[] {0}), new Section(SectionId.DEBUG, new byte[] {4})));
        ModuleFile read = ModuleFile.read(file);
        assertEquals(UNKNOWN_ANCILLARY, read.sectionId(2));
        var again = new ArrayList<Section>();
        for (int i = 0; i < read.sectionCount(); i++) {
            again.add(new Section(read.sectionId(i), read.content(i)));
        }
        assertTrue(Arrays.equals(file, ModuleFile.write(read.flags(), again)));
    }

    @Test
    public static void ancillarySectionsSortLastAndStayOutOfTheContentHash() throws Exception {
        Section idents = new Section(SectionId.IDENTS, new byte[] {0});
        Section code = new Section(SectionId.CODE, new byte[] {1});
        byte[] stripped = ModuleFile.write(0, sections(idents, code));
        byte[] full = ModuleFile.write(0, sections(new Section(SectionId.DEBUG, new byte[] {7, 7}), code,
                new Section(UNKNOWN_ANCILLARY, new byte[] {6}), idents));
        assertEquals("1 5 2147483649 2147483653",
                u32(full, 64) + " " + u32(full, 76) + " " + u32(full, 88) + " " + u32(full, 100));
        assertEquals(Leb128Test.hex(Arrays.copyOfRange(stripped, 32, 64)), Leb128Test.hex(Arrays.copyOfRange(full, 32, 64)));
        byte[] changed = full.clone();
        changed[changed.length - 1] ^= 1;
        assertEquals(UNKNOWN_ANCILLARY, ModuleFile.read(changed).sectionId(3));
    }

    @Test
    public static void anotherFormatVersionIsRejected() throws Exception {
        byte[] minor = valid();
        minor[6] = 2;
        reject(minor, 4, "format 0.2 is not accepted; this reader accepts format 0.1 only (BC-20)");
        byte[] major = valid();
        major[4] = 1;
        reject(major, 4, "format 1.1 is not accepted; this reader accepts format 0.1 only (BC-20)");
    }

    @Test
    public static void unknownFlagBitsAndTheDeltaFlagAreRejected() throws Exception {
        byte[] unknown = valid();
        unknown[8] = 0x05;
        reject(unknown, 8, "unknown header flag 0x4 (BC-20)");
        byte[] high = valid();
        high[11] = (byte) 0x80;
        reject(high, 8, "unknown header flag 0x80000000 (BC-20)");
        byte[] delta = valid();
        delta[8] = 0x02;
        reject(delta, 8, "delta modules come with v0.5; until then a reader rejects the DELTA flag");
        var error = assertThrows(ModuleFormatException.class, () -> ModuleFile.write(0x10, sections()));
        assertEquals("unknown header flag 0x10 (BC-20)", error.getMessage());
    }

    @Test
    public static void unknownCriticalSectionsAndDeltaSectionsAreRejected() throws Exception {
        byte[] unknown = valid();
        unknown[76] = 0x07;
        reject(unknown, 76, "unknown critical section 0x7 (BC-20)");
        byte[] delta = valid();
        delta[76] = 0x06;
        reject(delta, 76, "delta modules come with v0.5; until then a reader rejects the DELTA section");
        var error = assertThrows(ModuleFormatException.class,
                () -> ModuleFile.write(0, sections(new Section(0x12, new byte[] {1}))));
        assertEquals("unknown critical section 0x12 (BC-20)", error.getMessage());
    }

    @Test
    public static void defectsInTheHeaderAndTheDirectoryNameTheirOffset() throws Exception {
        reject(new byte[10], 0, "the file has 10 bytes, fewer than the 64 of the header");
        byte[] magic = valid();
        magic[3] = 'D';
        reject(magic, 0, "the file does not start with RFLC");
        byte[] reserved = valid();
        reserved[20] = 1;
        reject(reserved, 20, "a reserved header byte is not zero (BC-24)");
        byte[] count = valid();
        count[28] = 100;
        reject(count, 28, "a directory of 0x64 sections runs past the end of the file");
        byte[] unsorted = valid();
        unsorted[64] = 5;
        unsorted[76] = 1;
        reject(unsorted, 76, "the directory is not sorted by section ID (BC-02)");
        byte[] twice = valid();
        twice[76] = 1;
        reject(twice, 76, "two sections have the ID 0x1");
        byte[] moved = valid();
        moved[68] = 96;
        reject(moved, 68, "section 0x1 starts at 0x60, not at 0x58 (BC-02)");
        byte[] empty = valid();
        empty[72] = 0;
        reject(empty, 72, "section 0x1 has no content (BC-24)");
        byte[] past = valid();
        past[84] = 9;
        reject(past, 84, "section 0x5 runs past the end of the file");
        byte[] padding = valid();
        padding[90] = 1;
        reject(padding, 90, "a padding byte is not zero (BC-24)");
        byte[] longer = Arrays.copyOf(valid(), 98);
        reject(longer, 97, "the file continues after its last section (BC-02)");
        byte[] hash = valid();
        hash[96] ^= 1;
        reject(hash, 32, "contentHash does not match the content (BC-22)");
    }

    @Test
    public static void theWriterRejectsRepeatedAndEmptySections() {
        var twice = assertThrows(ModuleFormatException.class, () -> ModuleFile.write(0, sections(
                new Section(SectionId.IDENTS, new byte[] {0}), new Section(SectionId.IDENTS, new byte[] {0}))));
        assertEquals("two sections have the ID 0x1", twice.getMessage());
        var empty = assertThrows(ModuleFormatException.class,
                () -> ModuleFile.write(0, sections(new Section(SectionId.CODE, new byte[0]))));
        assertEquals("section 0x5 has no content (BC-24)", empty.getMessage());
    }

    // IDENTS of one byte at 88 and CODE of one byte at 96: 97 bytes in all.
    private static byte[] valid() throws ModuleFormatException {
        return ModuleFile.write(0, sections(new Section(SectionId.IDENTS, new byte[] {0}),
                new Section(SectionId.CODE, new byte[] {1})));
    }

    private static void reject(byte[] file, int offset, String message) {
        var error = assertThrows(ModuleFormatException.class, () -> ModuleFile.read(file));
        assertEquals("offset " + ModuleFormatException.hex(offset) + ": " + message, error.getMessage());
        assertEquals(offset, error.offset());
    }

    private static List<Section> sections(Section... sections) {
        var list = new ArrayList<Section>();
        for (Section section : sections) {
            list.add(section);
        }
        return list;
    }

    private static long u32(byte[] file, int offset) {
        return (file[offset] & 0xFFL) | (file[offset + 1] & 0xFFL) << 8 | (file[offset + 2] & 0xFFL) << 16
                | (file[offset + 3] & 0xFFL) << 24;
    }

    private static int count(byte[] file, int from, int to, byte value) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (file[i] == value) {
                count++;
            }
        }
        return count;
    }
}
