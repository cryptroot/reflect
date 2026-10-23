package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.bytes;
import static reflect.stage0.bytecode.TestModules.idents;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class ModuleTableTest {
    @Test
    public static void aModuleWithoutRequiresHasTheDocumentedBytes() throws ModuleFormatException {
        byte[] file = roundTrip(new ModuleDef("hello", 0, 1, 2));
        assertEquals("00 05 68 65 6C 6C 6F", Leb128Test.hex(section(file, SectionId.IDENTS)));
        assertEquals("00", Leb128Test.hex(section(file, SectionId.LITERALS)));
        assertEquals("00", Leb128Test.hex(section(file, SectionId.BLOBS)));
        assertEquals("01 00 00 00 01 00 00 00 01 00 00 00 18 00 00 00 "
                + "01 00 00 00 00 00 00 00 01 00 00 00 02 00 00 00 00 00 00 00 00 00 00 00",
                Leb128Test.hex(section(file, SectionId.TABLES)));
        ModuleDef read = ModuleReader.read(file);
        assertEquals("hello 0.1.2", read.name() + " " + read.major() + "." + read.minor() + "." + read.patch());
        assertTrue(!read.unsafe());
        assertTrue(read.requires().isEmpty());
        assertTrue(read.ancillary().isEmpty());
    }

    @Test
    public static void moduleRefsSortByTheirUtf8Bytes() throws ModuleFormatException {
        var module = new ModuleDef("app", 1, 0, 0);
        module.requires().add(new ModuleRef("reflect.io", Flags.REQUIRES_TRANSITIVE));
        module.requires().add(new ModuleRef("\uD800\uDC00", 0));
        module.requires().add(new ModuleRef("\uF900", Flags.REQUIRES_STATIC));
        module.requires().add(new ModuleRef("reflect.core", Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC));
        // String.compareTo puts U+10000, a surrogate pair, before U+F900; UTF-8 puts it after.
        assertTrue("\uD800\uDC00".compareTo("\uF900") < 0);
        ModuleDef read = ModuleReader.read(roundTrip(module));
        var order = new StringBuilder();
        for (ModuleRef ref : read.requires()) {
            order.append(Leb128Test.hex(ref.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append(" flags ").append(ref.flags()).append("; ");
        }
        assertEquals("72 65 66 6C 65 63 74 2E 63 6F 72 65 flags 3; 72 65 66 6C 65 63 74 2E 69 6F flags 1; "
                + "EF A4 80 flags 2; F0 90 80 80 flags 0; ", order.toString());
    }

    @Test
    public static void exportsSortByNamespaceKindAndTarget() throws ModuleFormatException {
        var module = new ModuleDef("app", 1, 0, 0);
        module.requires().add(new ModuleRef("reflect.core", 0));
        module.exports().add(new Export("\uD800\uDC00", Flags.EXPORTS, ""));
        module.exports().add(new Export("app.b", Flags.EXPORTS, ""));
        module.exports().add(new Export("app.a", Flags.OPENS, "tests"));
        module.exports().add(new Export("\uF900", Flags.EXPORTS, ""));
        module.exports().add(new Export("app.a", Flags.EXPORTS, "tests"));
        module.exports().add(new Export("app.a", Flags.EXPORTS, ""));
        byte[] file = roundTrip(module);
        // The names of Export rows follow the ModuleRef names in IDENTS, in row order (BC-24).
        assertEquals("00 03 61 70 70 0C 72 65 66 6C 65 63 74 2E 63 6F 72 65 05 61 70 70 2E 61 05 74 65 73 74 73 "
                + "05 61 70 70 2E 62 03 EF A4 80 04 F0 90 80 80", Leb128Test.hex(section(file, SectionId.IDENTS)));
        // Every module, the empty target, comes first, and U+F900 comes before U+10000 in UTF-8.
        var rows = new StringBuilder();
        for (int row = 1; row <= 6; row++) {
            for (int column = 0; column < 3; column++) {
                rows.append(TestModules.column(file, TableId.EXPORT, row, column)).append(column < 2 ? " " : "; ");
            }
        }
        assertEquals("18 0 0; 18 0 24; 18 1 24; 30 0 0; 36 0 0; 40 0 0; ", rows.toString());
        var order = new StringBuilder();
        for (Export export : ModuleReader.read(file).exports()) {
            order.append(Leb128Test.hex(export.namespace().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append(" / ").append(export.kind()).append(" / ").append(export.target()).append("; ");
        }
        assertEquals("61 70 70 2E 61 / 0 / ; 61 70 70 2E 61 / 0 / tests; 61 70 70 2E 61 / 1 / tests; "
                + "61 70 70 2E 62 / 0 / ; EF A4 80 / 0 / ; F0 90 80 80 / 0 / ; ", order.toString());
    }

    @Test
    public static void theWriterRejectsRepeatedExportsAndUndefinedKinds() {
        var targeted = new ModuleDef("a", 0, 0, 0);
        targeted.exports().add(new Export("a.b", Flags.OPENS, "c"));
        targeted.exports().add(new Export("a.b", Flags.OPENS, "c"));
        unwritable(targeted, "two Export rows have the key of opens a.b to c (BC-24)");
        var everyModule = new ModuleDef("a", 0, 0, 0);
        everyModule.exports().add(new Export("a.b", Flags.EXPORTS, ""));
        everyModule.exports().add(new Export("a.b", Flags.EXPORTS, ""));
        unwritable(everyModule, "two Export rows have the key of exports a.b (BC-24)");
        var kind = new ModuleDef("a", 0, 0, 0);
        kind.exports().add(new Export("a.b", 2, ""));
        unwritable(kind, "Export a.b has the undefined kind 2 (BC-20)");
        var global = new ModuleDef("a", 0, 0, 0);
        global.exports().add(new Export("", Flags.EXPORTS, ""));
        unwritable(global, "an Export row names the global namespace, which no module exports (MOD-01)");
    }

    @Test
    public static void theReaderChecksTheExportRows() throws ModuleFormatException {
        // The Export rows start at TABLES offset 52: the directory of two tables, then the Module row.
        int[] module = {1, 0, 0, 0, 0, 0};
        rows(idents("m", "a"), new TestModules.Tables().rows(TableId.MODULE, module).rows(TableId.EXPORT, 3, 2, 0), 56,
                "Export kind 0x2 is not defined (BC-20)");
        rows(idents("m"), new TestModules.Tables().rows(TableId.MODULE, module).rows(TableId.EXPORT, 0, 0, 0), 52,
                "the Export row names the global namespace, which no module exports (MOD-01)");
        rows(idents("m", "b", "a"), new TestModules.Tables().rows(TableId.MODULE, module)
                .rows(TableId.EXPORT, 3, 0, 0, 5, 0, 0), 64, "the Export rows are not sorted by namespace, kind and target (BC-24)");
        rows(idents("m", "a"), new TestModules.Tables().rows(TableId.MODULE, module)
                .rows(TableId.EXPORT, 3, 1, 0, 3, 0, 0), 64, "the Export rows are not sorted by namespace, kind and target (BC-24)");
        rows(idents("m", "a", "c", "b"), new TestModules.Tables().rows(TableId.MODULE, module)
                .rows(TableId.EXPORT, 3, 0, 5, 3, 0, 7), 64, "the Export rows are not sorted by namespace, kind and target (BC-24)");
        rows(idents("m", "a"), new TestModules.Tables().rows(TableId.MODULE, module)
                .rows(TableId.EXPORT, 3, 0, 0, 3, 0, 0), 64, "two Export rows have equal keys (BC-24)");
    }

    @Test
    public static void theUnsafeFlagAndUnknownAncillarySectionsRoundTrip() throws ModuleFormatException {
        var module = new ModuleDef("native", 0, 0, 0);
        module.setUnsafe(true);
        module.ancillary().add(new Section(0x80000007, bytes(1, 2, 3)));
        byte[] file = roundTrip(module);
        assertEquals(Header.FLAG_UNSAFE, ModuleFile.read(file).flags());
        ModuleDef read = ModuleReader.read(file);
        assertTrue(read.unsafe());
        assertEquals(0x80000007, read.ancillary().get(0).id());
        assertEquals("01 02 03", Leb128Test.hex(read.ancillary().get(0).content()));
    }

    @Test
    public static void theWriterRejectsRepeatedModuleRefsAndUndefinedFlags() {
        var twice = new ModuleDef("a", 0, 0, 0);
        twice.requires().add(new ModuleRef("reflect.core", 0));
        twice.requires().add(new ModuleRef("reflect.core", Flags.REQUIRES_STATIC));
        unwritable(twice, "two ModuleRef rows have the name reflect.core (BC-24)");
        var same = new ModuleDef("a", 0, 0, 0);
        var ref = new ModuleRef("b", 0);
        same.requires().add(ref);
        same.requires().add(ref);
        unwritable(same, "the module lists the ModuleRef b twice");
        var flags = new ModuleDef("a", 0, 0, 0);
        flags.requires().add(new ModuleRef("b", 0x04));
        unwritable(flags, "ModuleRef b has the undefined flags 0x4 (BC-20)");
        var self = new ModuleDef("a", 0, 0, 0);
        self.requires().add(new ModuleRef("a", 0));
        unwritable(self, "the module a requires itself; a requires cycle is an error (MOD-03)");
        var critical = new ModuleDef("a", 0, 0, 0);
        critical.ancillary().add(new Section(SectionId.CODE, bytes(0)));
        unwritable(critical, "section 0x5 is kept as bytes, but it is not ancillary");
    }

    @Test
    public static void theReaderChecksTheTablesDirectory() throws ModuleFormatException {
        byte[] names = idents("m");
        int[] module = {1, 0, 0, 0, 0, 0};
        tables(new TestModules.Tables().rows(TableId.MODULE, module).table(0x18, 1, 4, 0), 16, "unknown table 0x18 (BC-20)");
        tables(new TestModules.Tables().rows(TableId.MODULE, module).table(TableId.SERVICE, 1, 4, 0), 16,
                "unknown table 0x4 (BC-20)");
        tables(new TestModules.Tables().rows(TableId.MODULE, module).table(TableId.TYPE_SPEC, 1, 4, 0), 16,
                "this reader does not support table 0x7 yet");
        tables(new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0), 0,
                "the Module table has 2 rows; it has exactly one (BC-24)");
        tables(new TestModules.Tables().table(TableId.MODULE, 0, 24), 8,
                "table 0x1 has no rows; a table is present only when it has rows (BC-24)");
        tables(new TestModules.Tables().table(TableId.MODULE, 16_777_216, 24), 8,
                "table 0x1 has 16777216 rows, more than the limit of 16777215 (BC-21)");
        tables(new TestModules.Tables().table(TableId.MODULE, 1, 20, 1, 0, 0, 0, 0), 12,
                "table 0x1 has rows of 20 bytes, not 24 (BC-11)");
        tables(new TestModules.Tables().rows(TableId.MODULE_REF, 1, 0).rows(TableId.MODULE, module), 16,
                "the TABLES directory is not sorted by table ID (BC-11)");
        tables(new TestModules.Tables().rows(TableId.MODULE, module).rows(TableId.MODULE, module), 16,
                "table 0x1 appears twice in the TABLES directory");
        tables(new TestModules.Tables().table(TableId.MODULE, 2, 24, module), 8,
                "the rows of table 0x1 run past the end of TABLES");
        byte[] longer = TestModules.file(names, new TestModules.Tables().rows(TableId.MODULE, module).bytes(0));
        rejected(longer, sectionOffset(longer, SectionId.TABLES) + 40, "TABLES continues after the rows of its last table");
        byte[] noTables = ModuleFile.write(0, sections(new Section(SectionId.IDENTS, names),
                new Section(SectionId.LITERALS, bytes(0)), new Section(SectionId.BLOBS, bytes(0))));
        rejected(noTables, 64, "the module has no section 0x4; IDENTS, LITERALS, BLOBS and TABLES are always present (BC-24)");
    }

    @Test
    public static void theReaderChecksTheColumnsOfTheRows() throws ModuleFormatException {
        rows(idents("m"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 1, 0), 32,
                "Module flags 0x1 are not defined (BC-20)");
        rows(idents("m"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 1), 36,
                "row 1 of table 0x5 does not exist");
        rows(idents("m"), new TestModules.Tables().rows(TableId.MODULE, 2, 0, 0, 0, 0, 0), 16,
                "IDENTS offset 0x2 is not the start of an entry");
        rows(idents("m", "b"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.MODULE_REF, 3, 4), 56, "ModuleRef flags 0x4 are not defined (BC-20)");
        rows(idents("m", "b", "a"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.MODULE_REF, 3, 0, 5, 0), 60, "the ModuleRef rows are not sorted by name (BC-24)");
        rows(idents("m", "b"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.MODULE_REF, 3, 0, 3, 0), 60, "two ModuleRef rows have the name b (BC-24)");
        rows(idents("m"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.MODULE_REF, 1, 0), 52, "the module m requires itself; a requires cycle is an error (MOD-03)");
        rows(idents("m", "x"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0), -1,
                "IDENTS entry is not referenced (BC-24)");
        byte[] code = TestModules.file(idents("m"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0).bytes(),
                new Section(SectionId.CODE, bytes(0)));
        rejected(code, 64 + 12 * 4, "the module has a CODE section, but no method has a body (BC-24)");
    }

    // Offsets in the next two helpers are relative to the start of TABLES; -1 means the entry 0x3 of IDENTS.
    private static void tables(TestModules.Tables tables, int offset, String message) throws ModuleFormatException {
        byte[] file = TestModules.file(idents("m"), tables.bytes());
        rejected(file, sectionOffset(file, SectionId.TABLES) + offset, message);
    }

    private static void rows(byte[] names, TestModules.Tables tables, int offset, String message)
            throws ModuleFormatException {
        byte[] file = TestModules.file(names, tables.bytes());
        int at = offset < 0 ? sectionOffset(file, SectionId.IDENTS) + 3 : sectionOffset(file, SectionId.TABLES) + offset;
        rejected(file, at, message);
    }

    private static reflect.stage0.lib.List<Section> sections(Section... sections) {
        var list = new reflect.stage0.lib.ArrayList<Section>();
        for (Section section : sections) {
            list.add(section);
        }
        return list;
    }
}
