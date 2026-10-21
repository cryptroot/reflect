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
