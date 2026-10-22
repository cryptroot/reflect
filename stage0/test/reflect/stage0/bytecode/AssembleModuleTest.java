package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.bytes;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleModuleTest {
    @Test
    public static void moduleDirectivesGiveTheRowsAndSectionsThatTheyState() throws Exception {
        byte[] file = assemble(".module demo 0.1.2 // a comment\n"
                + ".requires transitive static reflect.io\n"
                + ".requires reflect.core\n"
                + ".exports demo.zoo to demo.tests, demo.app\n"
                + ".opens demo.zoo\n"
                + ".unsafe\n"
                + ".section 0x80000002 hex \"00fF\"\n"
                + ".exports demo\n");
        var expected = new ModuleDef("demo", 0, 1, 2);
        expected.requires().add(new ModuleRef("reflect.io", Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC));
        expected.requires().add(new ModuleRef("reflect.core", 0));
        expected.exports().add(new Export("demo.zoo", Flags.EXPORTS, "demo.tests"));
        expected.exports().add(new Export("demo.zoo", Flags.EXPORTS, "demo.app"));
        expected.exports().add(new Export("demo.zoo", Flags.OPENS, ""));
        expected.exports().add(new Export("demo", Flags.EXPORTS, ""));
        expected.setUnsafe(true);
        expected.ancillary().add(new Section(0x80000002, bytes(0x00, 0xFF)));
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(file));
        // Version numbers are u32 values, so the largest is 2^32 - 1.
        assertEquals(Leb128Test.hex(ModuleWriter.write(new ModuleDef("m", -1, 0, -1))),
                Leb128Test.hex(assemble(".module m 4294967295.0.4294967295")));
    }

    @Test
    public static void aKeptDebugIsStale() throws Exception {
        String text = ".module m 0.0.0\n";
        byte[] hash = ModuleFile.read(assemble(text)).contentHash();
        // The ancillary sections leave the contentHash as it is, so this DEBUG is stale (BC-20).
        ModuleDef read = ModuleReader.read(assemble(text + ".section 0x80000001 hex \"" + "00".repeat(32) + "01\"\n"));
        assertEquals(SectionId.DEBUG, read.ancillary().get(0).id());
        unassemblable(text + ".section 0x80000001 hex \"" + Leb128Test.hex(hash).replace(" ", "") + "01\"\n",
                "2:1: RFA0301: the kept DEBUG starts with the contentHash of the module, so it is not stale (BC-20)");
    }

    @Test
    public static void eachModuleDirectiveHasItsDiagnostics() {
        String m = ".module m 0.0.0\n";
        unassemblable("", "1:1: RFA0101: expected .module, found the end of the file");
        unassemblable(".module 1 0.0.0", "1:9: RFA0101: expected the name of the module, found 1");
        unassemblable(".module m 1.0", "1:11: RFA0101: expected a version of three numbers, such as 1.2.0, found 1.0");
        unassemblable(".module m 0.0.4294967296",
                "1:11: RFA0103: 0.0.4294967296 is outside the range of a version number, from 0 to 4294967295");
        unassemblable(".module m 0.0.0 .bogus", "1:17: RFA0101: expected a directive of the module, found .bogus");
        unassemblable(m + ".requires static static a", "2:18: RFA0104: static appears twice");
        unassemblable(m + ".requires a\n.requires static a", "3:18: RFA0301: two ModuleRef rows have the name a (BC-24)");
        unassemblable(m + ".exports a.b to c, c", "2:20: RFA0301: two Export rows have the key of exports a.b to c (BC-24)");
        unassemblable(m + ".opens a.b\n.opens a.b", "3:8: RFA0301: two Export rows have the key of opens a.b (BC-24)");
        unassemblable(m + ".exports a.b to", "2:16: RFA0101: expected the name of a module, found the end of the file");
        unassemblable(m + ".unsafe\n.unsafe", "3:1: RFA0104: the module is unsafe once");
        unassemblable(m + ".main a.A\n.main a.B", "3:1: RFA0104: the module names one main class");
        unassemblable(m + ".main a.A/B", "2:7: RFA0201: the module defines no type a.A/B");
        unassemblable(m + ".section 5 hex \"00\"",
                "2:10: RFA0301: section 0x5 is not ancillary; .section keeps an ancillary section (BC-20)");
        unassemblable(m + ".section 0x80000002 hex \"\"", "2:10: RFA0301: section 0x80000002 has no content (BC-24)");
        unassemblable(m + ".section 0x80000002 hex \"0\"", "2:25: RFA0101: a hex string holds pairs of hexadecimal digits");
        unassemblable(m + ".section 0x80000002 hex \"0g\"", "2:25: RFA0101: a hex string holds pairs of hexadecimal digits");
        unassemblable(m + ".section 0x80000002 hex \"\\u0030\\u0030\"",
                "2:25: RFA0101: a hex string holds pairs of hexadecimal digits");
        unassemblable(m + ".section 0x100000000 hex \"00\"",
                "2:10: RFA0103: 0x100000000 is outside the range of a section ID, from 0 to 4294967295");
        unassemblable(m + ".section 1.5 hex \"00\"", "2:10: RFA0101: expected a section ID, found 1.5");
        unassemblable(m + ".section 0x80000002 hex \"00\"\n.section 0x80000002 hex \"01\"",
                "3:10: RFA0301: two sections have the ID 0x80000002 (BC-02)");
    }

    @Test
    public static void identifiersHaveTheLimitOfBc21() throws Exception {
        String longest = "a".repeat(Limits.MAX_IDENTIFIER_BYTES);
        ModuleReader.read(assemble(".module " + longest + " 0.0.0"));
        String message = "RFA0302: an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)";
        unassemblable(".module " + longest + "b 0.0.0", "1:9: " + message);
        unassemblable(".module m 0.0.0\n.requires " + longest + "b", "2:11: " + message);
        unassemblable(".module m 0.0.0\n.exports " + longest + "b", "2:10: " + message);
        unassemblable(".module m 0.0.0\n.exports a to " + longest + "b", "2:15: " + message);
    }
}
