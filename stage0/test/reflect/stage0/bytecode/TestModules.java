package reflect.stage0.bytecode;

import java.util.Arrays;

import reflect.stage0.lib.ArrayList;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;

/** Helpers for the module tests: the round trip, hand-made files and section offsets. */
final class TestModules {
    private TestModules() {
    }

    /** Writes, reads and writes again; both writes must give the same bytes (README §7.3 exit check). */
    static byte[] roundTrip(ModuleDef module) throws ModuleFormatException {
        byte[] first = ModuleWriter.write(module);
        byte[] second = ModuleWriter.write(ModuleReader.read(first));
        if (!Arrays.equals(first, second)) {
            throw new AssertionError("write, read and write give different bytes:\n" + Leb128Test.hex(first)
                    + "\n" + Leb128Test.hex(second));
        }
        return first;
    }

    /** A file with these sections and a correct content hash; LITERALS and BLOBS default to their empty entry. */
    static byte[] file(byte[] idents, byte[] tables, Section... more) throws ModuleFormatException {
        var sections = new ArrayList<Section>();
        sections.add(new Section(SectionId.IDENTS, idents));
        sections.add(new Section(SectionId.TABLES, tables));
        boolean literals = false;
        boolean blobs = false;
        for (Section section : more) {
            sections.add(section);
            literals |= section.id() == SectionId.LITERALS;
            blobs |= section.id() == SectionId.BLOBS;
        }
        if (!literals) {
            sections.add(new Section(SectionId.LITERALS, new byte[] {0}));
        }
        if (!blobs) {
            sections.add(new Section(SectionId.BLOBS, new byte[] {0}));
        }
        return ModuleFile.write(0, sections);
    }

    static byte[] section(byte[] file, int id) throws ModuleFormatException {
        ModuleFile read = ModuleFile.read(file);
        return read.content(read.find(id));
    }

    static int sectionOffset(byte[] file, int id) throws ModuleFormatException {
        ModuleFile read = ModuleFile.read(file);
        return read.open(read.find(id)).position();
    }

    /** The file offset of a column of a row, found through the TABLES directory; rows count from 1. */
    static int columnOffset(byte[] file, int table, int row, int column) throws ModuleFormatException {
        int tables = sectionOffset(file, SectionId.TABLES);
        int count = u32(file, tables);
        int position = tables + 4 + 12 * count;
        for (int i = 0; i < count; i++) {
            int entry = tables + 4 + 12 * i;
            if (u32(file, entry) == table) {
                return position + (row - 1) * u32(file, entry + 8) + 4 * column;
            }
            position += u32(file, entry + 4) * u32(file, entry + 8);
        }
        throw new AssertionError("the file has no table " + table);
    }

    static int column(byte[] file, int table, int row, int column) throws ModuleFormatException {
        return u32(file, columnOffset(file, table, row, column));
    }

    /** A copy of the file with one column changed and the content hash computed again. */
    static byte[] patch(byte[] file, int table, int row, int column, int value) throws Exception {
        byte[] copy = file.clone();
        int at = columnOffset(file, table, row, column);
        for (int i = 0; i < 4; i++) {
            copy[at + i] = (byte) (value >>> (8 * i));
        }
        return rehash(copy);
    }

    /** A copy of the file with one byte changed and the content hash computed again. */
    static byte[] patchByte(byte[] file, int offset, int value) throws Exception {
        byte[] copy = file.clone();
        copy[offset] = (byte) value;
        return rehash(copy);
    }

    /** Puts the contentHash of BC-22 into the file, computed with MessageDigest apart from ModuleFile. */
    static byte[] rehash(byte[] file) throws java.security.NoSuchAlgorithmException {
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update(file, 4, 24);
        for (int i = 0; i < u32(file, 28); i++) {
            int entry = 64 + 12 * i;
            if (u32(file, entry) >= 0) {
                sha.update(file, entry, 4);
                sha.update(file, entry + 8, 4);
                sha.update(file, u32(file, entry + 4), u32(file, entry + 8));
            }
        }
        byte[] hash = sha.digest();
        System.arraycopy(hash, 0, file, 32, hash.length);
        return file;
    }

    static int u32(byte[] file, int offset) {
        return (file[offset] & 0xFF) | (file[offset + 1] & 0xFF) << 8 | (file[offset + 2] & 0xFF) << 16
                | (file[offset + 3] & 0xFF) << 24;
    }

    @SafeVarargs
    static <T> reflect.stage0.lib.List<T> list(T... items) {
        var list = new ArrayList<T>();
        for (T item : items) {
            list.add(item);
        }
        return list;
    }

    /** The file offset of the payload of the BLOBS entry that a column holds. */
    static int blobOffset(byte[] file, int table, int row, int column) throws ModuleFormatException {
        int entry = sectionOffset(file, SectionId.BLOBS) + column(file, table, row, column);
        int position = entry;
        while ((file[position] & 0x80) != 0) {
            position++;
        }
        return position + 1;
    }

    /** The payload of the BLOBS entry that a column holds, read with a ULEB128 length of its own. */
    static byte[] blobBytes(byte[] file, int table, int row, int column) throws ModuleFormatException {
        int entry = sectionOffset(file, SectionId.BLOBS) + column(file, table, row, column);
        int length = 0;
        int shift = 0;
        int position = entry;
        while (true) {
            int b = file[position++] & 0xFF;
            length |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return Arrays.copyOfRange(file, position, position + length);
    }

    static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    /** IDENTS with the empty entry and then these identifiers, in order. */
    static byte[] idents(String... names) throws ModuleFormatException {
        var heap = new HeapWriter(SectionId.IDENTS);
        for (String name : names) {
            heap.ident(name);
        }
        return heap.toByteArray();
    }

    /** Expects the reader to reject the file with this message at this offset. */
    static void rejected(byte[] file, int offset, String message) {
        var error = assertThrows(ModuleFormatException.class, () -> ModuleReader.read(file));
        assertEquals("offset " + ModuleFormatException.hex(offset) + ": " + message, error.getMessage());
    }

    /** Expects the writer to reject the module with this message. */
    static void unwritable(ModuleDef module, String message) {
        assertEquals(message, assertThrows(ModuleFormatException.class, () -> ModuleWriter.write(module)).getMessage());
    }

    /** Builds TABLES: the directory as given, then the values of the rows. */
    static final class Tables {
        private final ByteSink directory = new ByteSink();
        private final ByteSink rows = new ByteSink();
        private int count;

        Tables table(int id, int rowCount, int rowSize, int... values) {
            directory.u32(id);
            directory.u32(rowCount);
            directory.u32(rowSize);
            for (int value : values) {
                rows.u32(value);
            }
            count++;
            return this;
        }

        /** A table whose rows have the columns of this format. */
        Tables rows(int id, int... values) {
            return table(id, values.length / TableId.columns(id), TableId.rowSize(id), values);
        }

        byte[] bytes(int... extra) {
            var out = new ByteSink();
            out.u32(count);
            out.bytes(directory.toByteArray());
            out.bytes(rows.toByteArray());
            for (int value : extra) {
                out.u8(value);
            }
            return out.toByteArray();
        }
    }
}
