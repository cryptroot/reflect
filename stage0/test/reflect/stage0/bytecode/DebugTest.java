package reflect.stage0.bytecode;

import java.util.Arrays;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.CodeTest.STATIC_VOID;
import static reflect.stage0.bytecode.CodeTest.method;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.patchByte;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class DebugTest {
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final SourceFile A = new SourceFile("a", hash(0x11));
    // The reader tests: static void a.A::f() with the locals int and int: nop; br +0, which targets ret
    // at code offset 3; ret; and no regions and no frames.
    private static final int[] LOCALS = {SigCode.LOCALS, 0x02, SigCode.I32, SigCode.I32};
    private static final int[] BODY = {0x01, 0x06, 0x04, 0x00, 0x88, 0x00, 0xB0, 0x00, 0x00};
    // The token of f, Method row 1, and the entries of the source files a and b in files.
    private static final int[] F = {0x81, 0x80, 0x80, 0x48};
    private static final int[] FILE_A = file('a');
    private static final int[] FILE_B = file('b');

    @Test
    public static void debugListsTheFilesAtTheirFirstUseAndTheMethodsByToken() throws Exception {
        byte[] file = roundTrip(fixture(0, 0xAA, true));
        String main = "08 6D 61 69 6E 2E 72 66 6C " + "AA ".repeat(32);
        String lib = "07 6C 69 62 2E 72 66 6C " + "BB ".repeat(32);
        // The contentHash and two files: main.rfl, which the first line entry of f names, then lib.rfl,
        // against the order of their paths. Then f, row 1, with lines at code offsets 0 and 3, and h, row
        // 4, with a line; g has no line entry, and n has no body.
        assertEquals(Leb128Test.hex(ModuleFile.read(file).contentHash()) + " 02 " + main + lib + "02 81 80 80 48 02 00 00"
                + " 03 03 01 07 00 84 80 80 48 01 00 00 09 00", Leb128Test.hex(section(file, SectionId.DEBUG)));
        TypeDef read = ModuleReader.read(file).types().get(0);
        MethodBody f = read.methods().get(0).body();
        MethodBody h = read.methods().get(3).body();
        assertEquals("0 main.rfl 3, 2 lib.rfl 7", lines(f));
        assertEquals("0 main.rfl 9", lines(h));
        // The entry at ret shares its label with the br that targets ret, and f and h name one SourceFile.
        assertEquals(true, f.lines().get(1).at() == ((Instruction.Branch) f.code().get(1)).target());
        assertEquals(true, h.lines().get(0).file() == f.lines().get(0).file());
        assertEquals("AA ".repeat(31) + "AA", Leb128Test.hex(f.lines().get(0).file().hash()));
        assertEquals(true, read.methods().get(2).body().lines().isEmpty());
    }

    @Test
    public static void aStaleDebugIsTreatedAsAbsent() throws Exception {
        byte[] file = roundTrip(fixture(0, 0xAA, true));
        // Version 7 changes TABLES, and so the contentHash that DEBUG starts with.
        byte[] stale = patch(file, TableId.MODULE, 1, 1, 7);
        ModuleDef read = ModuleReader.read(stale);
        assertEquals(true, read.types().get(0).methods().get(0).body().lines().isEmpty());
        // The reader keeps the bytes of a stale DEBUG, so the writer gives the file back.
        assertEquals(Leb128Test.hex(section(file, SectionId.DEBUG)), Leb128Test.hex(read.ancillary().get(0).content()));
        assertTrue(Arrays.equals(stale, ModuleWriter.write(read)));
        // A DEBUG too short for a contentHash is stale too, and so is one whose content breaks every rule.
        byte[] stripped = strip(file, SectionId.DEBUG);
        byte[] filler = new byte[40];
        Arrays.fill(filler, (byte) 0xFF);
        for (byte[] content : new byte[][] {{1, 2, 3}, filler}) {
            byte[] other = withSection(stripped, new Section(SectionId.DEBUG, content));
            ModuleDef kept = ModuleReader.read(other);
            assertEquals(Leb128Test.hex(content), Leb128Test.hex(kept.ancillary().get(0).content()));
            assertTrue(Arrays.equals(other, ModuleWriter.write(kept)));
        }
    }

    @Test
    public static void removingDebugKeepsTheContentHash() throws Exception {
        byte[] file = roundTrip(fixture(0, 0xAA, true));
        byte[] stripped = strip(file, SectionId.DEBUG);
        assertEquals(Leb128Test.hex(ModuleFile.read(file).contentHash()),
                Leb128Test.hex(ModuleFile.read(stripped).contentHash()));
        // The reader accepts the stripped file, which is the file of the module without line entries.
        assertEquals(true, ModuleReader.read(stripped).types().get(0).methods().get(0).body().lines().isEmpty());
        assertTrue(Arrays.equals(stripped, ModuleWriter.write(fixture(0, 0xAA, false))));
    }

    @Test
    public static void movingALineChangesDebugOnly() throws Exception {
        byte[] before = roundTrip(fixture(0, 0xAA, true));
        // A line inserted at the top of main.rfl moves its lines down by one, and changes its SHA-256.
        byte[] after = roundTrip(fixture(1, 0xAC, true));
        assertTrue(!Arrays.equals(section(before, SectionId.DEBUG), section(after, SectionId.DEBUG)));
        assertTrue(Arrays.equals(strip(before, SectionId.DEBUG), strip(after, SectionId.DEBUG)));
    }

    @Test
    public static void theWriterChecksLineEntries() {
        String entry = "method a.A::m: line entry 0: ";
        unwritableWith(w -> w.body.lines().add(new Line(new Label(), A, 1)),
                entry + "the line entry is at no instruction of the body (BC-17)");
        unwritableWith(w -> w.body.lines().add(new Line(w.labels[2], A, 1)),
                entry + "the line entry is at no instruction of the body (BC-17)");
        unwritableWith(w -> w.body.lines().add(new Line(w.labels[0], A, 0)),
                entry + "the line entry is at line 0; lines count from 1 (BC-17)");
        unwritableWith(w -> w.body.lines().add(new Line(w.labels[0], new SourceFile("", hash(0x11)), 1)),
                entry + "a source file has an empty path (BC-17)");
        unwritableWith(w -> w.body.lines().add(new Line(w.labels[0], new SourceFile("a", new byte[31]), 1)),
                entry + "the SHA-256 of the source file a has 31 bytes, not 32 (BC-17)");
        unwritableWith(w -> {
            w.body.lines().add(new Line(w.labels[1], A, 1));
            w.body.lines().add(new Line(w.labels[1], A, 2));
        }, "method a.A::m has two line entries at code offset 1 (BC-24)");
        unwritableWith(w -> {
            w.body.lines().add(new Line(w.labels[1], new SourceFile("a", hash(0x22)), 2));
            w.body.lines().add(new Line(w.labels[0], A, 1));
        }, "two source files have the path a (BC-24)");
    }

    @Test
    public static void theWriterKeepsOnlyAStaleDebugAsBytes() throws Exception {
        var current = new Writing();
        var content = new ByteSink();
        content.bytes(ModuleFile.read(roundTrip(current.module)).contentHash());
        content.u8(0);
        current.module.ancillary().add(new Section(SectionId.DEBUG, content.toByteArray()));
        unwritable(current.module, "section 0x80000001 is kept as bytes, but it starts with the module's contentHash,"
                + " so it is not a stale DEBUG (BC-20)");
        // A stale DEBUG and the DEBUG of the bodies would be two sections with one ID.
        var both = new Writing();
        both.module.ancillary().add(new Section(SectionId.DEBUG, new byte[] {1}));
        both.body.lines().add(new Line(both.labels[0], A, 1));
        unwritable(both.module, "two sections have the ID 0x80000001");
    }

    @Test
    public static void theReaderChecksTheFilesAndTheMethodsOfDebug() throws Exception {
        rejectedDebug(0, "a list of 5 source files runs past the end of DEBUG", 0x05);
        rejectedDebug(1, "a source file has an empty path (BC-17)", 0x01, 0x00, hashValues(), 0x00);
        rejectedDebug(2, "invalid UTF-8 lead byte 0xFF", 0x01, 0x01, 0xFF, hashValues(), 0x00);
        rejectedDebug(35, "two source files have the path a (BC-24)", 0x02, FILE_A, FILE_A, 0x00);
        rejectedDebug(1, "DEBUG lists no method; a section is present only when it has content (BC-24)", 0x00, 0x00);
        rejectedDebug(1, "a list of 5 methods runs past the end of DEBUG", 0x00, 0x05);
        rejectedDebug(2, "token 0x5000001 does not name a Method row (BC-17)", 0x00, 0x01, 0x81, 0x80, 0x80, 0x28,
                0x00, 0x00);
        rejectedDebug(2, "token 0x9000002 does not name a Method row (BC-17)", 0x00, 0x01, 0x82, 0x80, 0x80, 0x48,
                0x00, 0x00);
        // With the file a, the methods start at 36, and f with one line entry takes nine bytes.
        rejectedDebug(45, "DEBUG lists the method 0x9000001 twice (BC-24)", 0x01, FILE_A, 0x02, F, 0x01, 0x00, 0x00,
                0x01, 0x00, F, 0x01, 0x00, 0x00, 0x01, 0x00);
        rejectedDebug(2, "DEBUG lists the method 0x9000001, which has no line entry and no local name (BC-17)", 0x00,
                0x01, F, 0x00, 0x00);
        rejectedDebug(45, "DEBUG continues after its last method (BC-17)", 0x01, FILE_A, 0x01, F, 0x01, 0x00, 0x00, 0x01,
                0x00, 0x00);
        rejectedDebug(7, "this reader does not support local names yet", 0x00, 0x01, F, 0x00, 0x01, 0x00, 0x01, 'i');
        // n has no body; p and q, rows 2 and 3, have one line entry each, so their tokens start at
        // offsets 36 and 45 after the contentHash.
        byte[] three = threeMethods();
        int at = sectionOffset(three, SectionId.DEBUG) + Sha256.SIZE;
        assertEquals("82 83", Leb128Test.hex(new byte[] {three[at + 36], three[at + 45]}));
        rejected(patchByte(patchByte(three, at + 36, 0x83), at + 45, 0x82), at + 45,
                "the methods of DEBUG are not sorted by token (BC-24)");
        rejected(patchByte(three, at + 36, 0x81), at + 36,
                "the method 0x9000001 has no body, so it has no line entry and no local name (BC-17)");
    }

    @Test
    public static void theReaderChecksLineEntriesAtTheirOffsets() throws Exception {
        // In a, line 1 from the nop and line 2 from ret, at code offsets 0 and 3.
        MethodBody read = ModuleReader.read(debugFile(0x01, FILE_A, 0x01, F, 0x02, 0x00, 0x00, 0x01, 0x03, 0x00, 0x02,
                0x00)).types().get(0).methods().get(0).body();
        assertEquals("0 a 1, 2 a 2", lines(read));
        assertEquals(true, read.lines().get(1).at() == ((Instruction.Branch) read.code().get(1)).target());
        // The files end at 35, the token of f at 40, and the first entry starts at 41.
        rejectedDebug(40, "a list of 9 line entries runs past the end of DEBUG", 0x01, FILE_A, 0x01, F, 0x09, 0x00);
        rejectedDebug(41, "the line entry is at code offset 2, where no instruction starts (BC-17)", 0x01, FILE_A,
                0x01, F, 0x01, 0x02, 0x00, 0x01, 0x00);
        rejectedDebug(41, "the line entry is at no instruction of the body (BC-17)", 0x01, FILE_A, 0x01, F, 0x01, 0x04,
                0x00, 0x01, 0x00);
        rejectedDebug(44, "two line entries have code offset 0 (BC-24)", 0x01, FILE_A, 0x01, F, 0x02, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x02, 0x00);
        rejectedDebug(44, "the line entries are not sorted by code offset (BC-24)", 0x01, FILE_A, 0x01, F, 0x02, 0x03,
                0x00, 0x01, 0x00, 0x00, 0x02, 0x00);
        rejectedDebug(42, "the line entry names source file 1, but DEBUG lists 1 source files (BC-17)", 0x01, FILE_A,
                0x01, F, 0x01, 0x00, 0x01, 0x01, 0x00);
        rejectedDebug(41, "the line entry is at line 0; lines count from 1 (BC-17)", 0x01, FILE_A, 0x01, F, 0x01, 0x00,
                0x00, 0x00, 0x00);
        // With the files a and b, the token of f ends at 74, and the file of the first entry is at 76.
        rejectedDebug(76, "the line entry names source file 1 before source file 0; DEBUG lists the files in the order"
                + " of their first use (BC-24)", 0x02, FILE_A, FILE_B, 0x01, F, 0x01, 0x00, 0x01, 0x01, 0x00);
        rejectedDebug(35, "no line entry names the source file b; DEBUG lists only the files that line entries name"
                + " (BC-24)", 0x02, FILE_A, FILE_B, 0x01, F, 0x01, 0x00, 0x00, 0x01, 0x00);
    }

    // a.A: static void f(): L0: nop; br L2; L2: ret, with the lines (L2, lib.rfl, 7) and (L0, main.rfl,
    // 3), given in that order; static native void n(); static void g(): ret; static void h() with the
    // locals int and int: L0: nop; ret, with the line (L0, main.rfl, 9). The lines in main.rfl move
    // down by shift, every byte of its SHA-256 is a, and every byte of that of lib.rfl is 0xBB.
    // Without debug, the bodies have no line entries.
    private static ModuleDef fixture(int shift, int a, boolean debug) {
        var module = new ModuleDef("a", 0, 0, 0);
        // The UNSAFE flag enters the contentHash that DEBUG starts with (BC-22).
        module.setUnsafe(true);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        module.types().add(type);
        var f = new MethodBody(0);
        var nop = new Label();
        var ret = new Label();
        f.mark(nop);
        f.code().add(new Instruction.Plain(Opcode.NOP));
        f.code().add(new Instruction.Branch(Opcode.BR, ret));
        f.mark(ret);
        f.code().add(new Instruction.Plain(Opcode.RET));
        var h = new MethodBody(0);
        var start = new Label();
        h.mark(start);
        h.code().add(new Instruction.Plain(Opcode.NOP));
        h.code().add(new Instruction.Plain(Opcode.RET));
        h.locals().add(INT);
        h.locals().add(INT);
        if (debug) {
            f.lines().add(new Line(ret, new SourceFile("lib.rfl", hash(0xBB)), 7));
            f.lines().add(new Line(nop, new SourceFile("main.rfl", hash(a)), 3 + shift));
            // Another SourceFile with the path and the SHA-256 of main.rfl names the same file.
            h.lines().add(new Line(start, new SourceFile("main.rfl", hash(a)), 9 + shift));
        }
        type.methods().add(withBody("f", f));
        type.methods().add(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "n", STATIC_VOID));
        type.methods().add(withBody("g", CodeTest.code(0, new Instruction.Plain(Opcode.RET))));
        type.methods().add(withBody("h", h));
        return module;
    }

    // a.A: static native void n(); static void p() and static void q(), each ret, with the line (L0, a, 1).
    private static byte[] threeMethods() throws ModuleFormatException {
        var module = new ModuleDef("a", 0, 0, 0);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        module.types().add(type);
        type.methods().add(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "n", STATIC_VOID));
        for (String name : new String[] {"p", "q"}) {
            var body = new MethodBody(0);
            var start = new Label();
            body.mark(start);
            body.code().add(new Instruction.Plain(Opcode.RET));
            body.lines().add(new Line(start, A, 1));
            type.methods().add(withBody(name, body));
        }
        return roundTrip(module);
    }

    private static MethodDef withBody(String name, MethodBody body) {
        var method = new MethodDef(Flags.METHOD_STATIC, name, STATIC_VOID);
        method.setBody(body);
        return method;
    }

    // static void a.A::m() with the local int: L0: nop; L1: ret, and L2 at the end of the code.
    private static final class Writing {
        final MethodBody body = new MethodBody(0);
        final Label[] labels = {new Label(), new Label(), new Label()};
        final ModuleDef module = method(Flags.METHOD_STATIC, STATIC_VOID, body);

        Writing() {
            body.locals().add(INT);
            body.mark(labels[0]);
            body.code().add(new Instruction.Plain(Opcode.NOP));
            body.mark(labels[1]);
            body.code().add(new Instruction.Plain(Opcode.RET));
            body.mark(labels[2]);
        }
    }

    @FunctionalInterface
    private interface Change {
        void apply(Writing fixture);
    }

    // Expects the writer to reject the fixture after the change.
    private static void unwritableWith(Change change, String message) {
        var fixture = new Writing();
        change.apply(fixture);
        unwritable(fixture.module, message);
    }

    // Expects the reader to reject the DEBUG of these parts at this offset after its contentHash.
    private static void rejectedDebug(int at, String message, Object... parts) throws ModuleFormatException {
        byte[] file = debugFile(parts);
        rejected(file, sectionOffset(file, SectionId.DEBUG) + Sha256.SIZE + at, message);
    }

    // The module of CodeTest.bodyFile with LOCALS and BODY, and a DEBUG of its contentHash and these parts.
    private static byte[] debugFile(Object... parts) throws ModuleFormatException {
        byte[] file = CodeTest.bodyFile(LOCALS, BODY);
        var content = new ByteSink();
        content.bytes(ModuleFile.read(file).contentHash());
        content.bytes(TestModules.bytes(values(parts)));
        return withSection(file, new Section(SectionId.DEBUG, content.toByteArray()));
    }

    // The values of the parts, each an int, a char or an int[], one after another.
    private static int[] values(Object... parts) {
        var values = new java.util.ArrayList<Integer>();
        for (Object part : parts) {
            if (part instanceof int[] array) {
                for (int value : array) {
                    values.add(value);
                }
            } else if (part instanceof Character letter) {
                values.add((int) letter);
            } else {
                values.add((Integer) part);
            }
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    // The file with one more section.
    private static byte[] withSection(byte[] file, Section section) throws ModuleFormatException {
        ModuleFile read = ModuleFile.read(file);
        var sections = new ArrayList<Section>();
        for (int i = 0; i < read.sectionCount(); i++) {
            sections.add(new Section(read.sectionId(i), read.content(i)));
        }
        sections.add(section);
        return ModuleFile.write(read.flags(), sections);
    }

    // The file without the section with this ID.
    private static byte[] strip(byte[] file, int id) throws ModuleFormatException {
        ModuleFile read = ModuleFile.read(file);
        var sections = new ArrayList<Section>();
        for (int i = 0; i < read.sectionCount(); i++) {
            if (read.sectionId(i) != id) {
                sections.add(new Section(read.sectionId(i), read.content(i)));
            }
        }
        return ModuleFile.write(read.flags(), sections);
    }

    // A SHA-256 whose 32 bytes are all b.
    private static byte[] hash(int b) {
        byte[] hash = new byte[Sha256.SIZE];
        Arrays.fill(hash, (byte) b);
        return hash;
    }

    // The 32 bytes of the SHA-256 of the source files of the reader tests.
    private static int[] hashValues() {
        int[] values = new int[Sha256.SIZE];
        Arrays.fill(values, 0x11);
        return values;
    }

    // An entry of files: a path of one ASCII letter, and its SHA-256.
    private static int[] file(char letter) {
        int[] entry = new int[2 + Sha256.SIZE];
        entry[0] = 1;
        entry[1] = letter;
        System.arraycopy(hashValues(), 0, entry, 2, Sha256.SIZE);
        return entry;
    }

    // Each line entry as its instruction, its path and its line.
    private static String lines(MethodBody body) {
        var text = new StringBuilder();
        for (Line line : body.lines()) {
            text.append(text.length() > 0 ? ", " : "").append(line.at().index()).append(' ').append(line.file().path())
                    .append(' ').append(line.number());
        }
        return text.toString();
    }
}
