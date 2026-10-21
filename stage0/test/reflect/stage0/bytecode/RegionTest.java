package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.CodeTest.STATIC_VOID;
import static reflect.stage0.bytecode.CodeTest.body;
import static reflect.stage0.bytecode.CodeTest.method;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class RegionTest {
    // The code of the reader tests: three nops and ret, which start at code offsets 0 to 3.
    private static final int[] NOPS = {0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0xB0};

    @Test
    public static void theWriterSortsRegionsInnermostFirst() throws Exception {
        // try { try { nop; leave L8 } catch (String) { pop; leave L8 } catch (Object) { pop; leave L8 } }
        // finally { nop; endfinally } L8: ret. The instructions start at 0, 1, 3, 4, 6, 7, 9, 10 and 11.
        var body = new MethodBody(1);
        Label[] labels = labels(10);
        var pop = new Instruction.Plain(Opcode.POP);
        var leave = new Instruction.Branch(Opcode.LEAVE, labels[8]);
        add(body, labels, new Instruction.Plain(Opcode.NOP), leave, pop, leave, pop, leave,
                new Instruction.Plain(Opcode.NOP), new Instruction.Plain(Opcode.ENDFINALLY), new Instruction.Plain(Opcode.RET));
        ModuleDef module = withTypes(body);
        TypeRef object = module.typeRefs().get(0);
        TypeRef string = module.typeRefs().get(1);
        body.regions().add(new Region(Flags.REGION_FINALLY, labels[0], labels[6], labels[6], labels[8], null));
        body.regions().add(new Region(Flags.REGION_CATCH, labels[0], labels[2], labels[4], labels[6], object));
        body.regions().add(new Region(Flags.REGION_CATCH, labels[0], labels[2], labels[2], labels[4], string));
        byte[] file = roundTrip(module);
        // Object is TypeRef 1, String TypeRef 2.
        assertEquals("03 00 00 03 03 06 82 80 80 30 00 00 03 06 09 81 80 80 30 01 00 09 09 0B 00 00", regions(file));
        MethodBody read = ModuleReader.read(file).types().get(0).methods().get(0).body();
        Region first = read.regions().get(0);
        assertEquals("0 2 2 4 reflect.core.String", first.tryStart().index() + " " + first.tryEnd().index() + " "
                + first.handlerStart().index() + " " + first.handlerEnd().index() + " " + first.type().qualifiedName());
        // The region shares its label with leave, which targets the same instruction.
        assertEquals(true, read.regions().get(2).handlerEnd() == ((Instruction.Branch) read.code().get(1)).target());
    }

    @Test
    public static void theRegionsOfAMultiCatchSortByType() throws Exception {
        // try { nop; leave L4 } catch (String | Object) { pop; leave L4 } L4: ret, with String given first.
        var body = new MethodBody(1);
        Label[] labels = labels(5);
        var leave = new Instruction.Branch(Opcode.LEAVE, labels[4]);
        add(body, labels, new Instruction.Plain(Opcode.NOP), leave, new Instruction.Plain(Opcode.POP), leave,
                new Instruction.Plain(Opcode.RET));
        ModuleDef module = withTypes(body);
        body.regions().add(new Region(Flags.REGION_CATCH, labels[0], labels[2], labels[2], labels[4], module.typeRefs().get(1)));
        body.regions().add(new Region(Flags.REGION_CATCH, labels[0], labels[2], labels[2], labels[4], module.typeRefs().get(0)));
        assertEquals("02 00 00 03 03 06 81 80 80 30 00 00 03 03 06 82 80 80 30 00", regions(roundTrip(module)));
        body.regions().add(body.regions().get(0));
        unwritable(module, "method a.A::m has two equal exception regions (BC-18)");
    }

    @Test
    public static void theWriterChecksEachRegion() {
        // Over nop, nop, nop, ret: labels 0 to 3 name the instructions, and 4 the end.
        region(2, 0, 1, 1, 2, false, "region kind 0x2 is not defined (BC-20)");
        region(Flags.REGION_CATCH, 0, 1, 1, 2, false, "a CATCH region has a type, and a FINALLY region none (BC-18)");
        region(Flags.REGION_FINALLY, 0, 1, 1, 2, true, "a CATCH region has a type, and a FINALLY region none (BC-18)");
        region(Flags.REGION_FINALLY, 0, 1, 4, 4, false, "the handlerStart of the region names no instruction of the body"
                + " (BC-18)");
        region(Flags.REGION_FINALLY, 1, 1, 2, 3, false, "the try of the region holds no instruction (BC-18)");
        region(Flags.REGION_FINALLY, 0, 1, 2, 2, false, "the handler of the region holds no instruction (BC-18)");
        region(Flags.REGION_FINALLY, 0, 2, 1, 3, false, "the handler of the region overlaps its try (BC-18)");
        region(Flags.REGION_FINALLY, 0, 2, 2, 4, false, null);
        Label[] labels = labels(5);
        var body = nops(labels);
        body.regions().add(new Region(Flags.REGION_FINALLY, new Label(), labels[1], labels[1], labels[2], null));
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, body), "method a.A::m: region 0: the tryStart of the region"
                + " names no instruction of the body (BC-18)");
        var other = new MethodBody(0);
        var elsewhere = new Label();
        other.mark(elsewhere);
        body.regions().set(0, new Region(Flags.REGION_FINALLY, labels[0], elsewhere, labels[1], labels[2], null));
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, body), "method a.A::m: region 0: the tryEnd of the region"
                + " names no instruction of the body, nor its end (BC-18)");
    }

    @Test
    public static void theWriterChecksThatRegionsNest() {
        Label[] labels = labels(5);
        var body = nops(labels);
        // try [0, 2) with handler [2, 3), and try [1, 3), which crosses both.
        body.regions().add(new Region(Flags.REGION_FINALLY, labels[0], labels[2], labels[2], labels[3], null));
        body.regions().add(new Region(Flags.REGION_FINALLY, labels[1], labels[3], labels[3], labels[4], null));
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, body), "method a.A::m: region 1: a try or handler of the"
                + " region overlaps one of region 0 without nesting (BC-18)");
        Label[] marks = labels(5);
        var many = nops(marks);
        var region = new Region(Flags.REGION_FINALLY, marks[0], marks[1], marks[1], marks[2], null);
        for (int i = 0; i <= Limits.MAX_REGIONS; i++) {
            many.regions().add(region);
        }
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, many), "method a.A::m: the body has 65536 exception regions,"
                + " more than the limit of 65535 (BC-21)");
    }

    @Test
    public static void theReaderChecksRegionsAtTheirOffsets() throws Exception {
        int[] none = {};
        // The regions start at offset 7 into CODE, and the first region at 8.
        body(8, "region kind 0x2 is not defined (BC-20)", none, code(NOPS, 0x01, 0x02, 0x00, 0x01, 0x01, 0x02, 0x00, 0x00));
        body(8, "a CATCH region has a type, and a FINALLY region none (BC-18)", none,
                code(NOPS, 0x01, 0x01, 0x00, 0x01, 0x01, 0x02, 0x81, 0x80, 0x80, 0x28, 0x00));
        body(8, "the tryStart of the region names no instruction of the body (BC-18)", none,
                code(NOPS, 0x01, 0x01, 0x04, 0x04, 0x01, 0x02, 0x00, 0x00));
        body(13, "token 0x9000001 does not name a TypeDef, TypeRef or TypeSpec row (BC-18)", none,
                code(NOPS, 0x01, 0x00, 0x00, 0x01, 0x01, 0x02, 0x81, 0x80, 0x80, 0x48, 0x00));
        // ldc.i4 64 takes three bytes, so code offset 1 starts no instruction.
        body(9, "the tryStart of the region is code offset 1, where no instruction starts (BC-18)", none,
                0x01, 0x00, 0x04, 0x02, 0xC0, 0x00, 0xB0, 0x01, 0x01, 0x01, 0x03, 0x03, 0x04, 0x00, 0x00);
        // Region 0 has tryEnd 3 and region 1 tryEnd 1, so region 1 comes first.
        body(14, "the exception regions are not sorted by tryEnd, tryStart, handlerStart, handlerEnd and type (BC-18)",
                none, code(NOPS, 0x02, 0x01, 0x02, 0x03, 0x03, 0x04, 0x00, 0x01, 0x00, 0x01, 0x01, 0x02, 0x00, 0x00));
        // try [0, 2) holds try [1, 2), which ends with it; the inner one, with the later tryStart, comes first.
        ModuleReader.read(CodeTest.bodyFile(none, code(NOPS, 0x02,
                0x01, 0x01, 0x02, 0x02, 0x03, 0x00, 0x01, 0x00, 0x02, 0x03, 0x04, 0x00, 0x00)));
        body(14, "the exception regions are not sorted by tryEnd, tryStart, handlerStart, handlerEnd and type (BC-18)",
                none, code(NOPS, 0x02, 0x01, 0x00, 0x02, 0x03, 0x04, 0x00, 0x01, 0x01, 0x02, 0x02, 0x03, 0x00, 0x00));
        body(17, "two exception regions are equal (BC-18)", none, code(NOPS, 0x02,
                0x00, 0x00, 0x01, 0x01, 0x02, 0x81, 0x80, 0x80, 0x28, 0x00, 0x00, 0x01, 0x01, 0x02, 0x81, 0x80, 0x80, 0x28, 0x00));
        body(14, "a try or handler of the region overlaps one of region 0 without nesting (BC-18)", none,
                code(NOPS, 0x02, 0x01, 0x00, 0x02, 0x02, 0x03, 0x00, 0x01, 0x01, 0x03, 0x03, 0x04, 0x00, 0x00));
        body(7, "a list of 5 exception regions runs past the end of CODE", none, code(NOPS, 0x05, 0x00));
        body(7, "the body has 65536 exception regions, more than the limit of 65535 (BC-21)", none,
                code(NOPS, 0x80, 0x80, 0x04, 0x00));
    }

    private static Label[] labels(int count) {
        var labels = new Label[count];
        for (int i = 0; i < count; i++) {
            labels[i] = new Label();
        }
        return labels;
    }

    // Marks label i before instruction i, and the last label at the end of the code.
    private static void add(MethodBody body, Label[] labels, Instruction... instructions) {
        for (int i = 0; i < instructions.length; i++) {
            body.mark(labels[i]);
            body.code().add(instructions[i]);
        }
        for (int i = instructions.length; i < labels.length; i++) {
            body.mark(labels[i]);
        }
    }

    // nop, nop, nop, ret, with label i before instruction i and label 4 at the end.
    private static MethodBody nops(Label[] labels) {
        var body = new MethodBody(0);
        var nop = new Instruction.Plain(Opcode.NOP);
        add(body, labels, nop, nop, nop, new Instruction.Plain(Opcode.RET));
        return body;
    }

    // Writes a.A::m over nop, nop, nop, ret with one region; a type makes it catch reflect.core.Object.
    private static void region(int kind, int tryStart, int tryEnd, int handlerStart, int handlerEnd, boolean typed,
            String problem) {
        Label[] labels = labels(5);
        var body = nops(labels);
        ModuleDef module = withTypes(body);
        body.regions().add(new Region(kind, labels[tryStart], labels[tryEnd], labels[handlerStart], labels[handlerEnd],
                typed ? module.typeRefs().get(0) : null));
        if (problem == null) {
            try {
                roundTrip(module);
            } catch (ModuleFormatException e) {
                throw new AssertionError(e);
            }
            return;
        }
        unwritable(module, "method a.A::m: region 0: " + problem);
    }

    // static void a.A::m() with this body, in a module with the TypeRefs reflect.core.Object and String.
    private static ModuleDef withTypes(MethodBody body) {
        ModuleDef module = method(Flags.METHOD_STATIC, STATIC_VOID, body);
        var core = new ModuleRef("reflect.core", 0);
        module.requires().add(core);
        module.typeRefs().add(new TypeRef(core, "reflect.core", "Object"));
        module.typeRefs().add(new TypeRef(core, "reflect.core", "String"));
        return module;
    }

    private static int[] code(int[] code, int... rest) {
        int[] body = new int[code.length + rest.length];
        System.arraycopy(code, 0, body, 0, code.length);
        System.arraycopy(rest, 0, body, code.length, rest.length);
        return body;
    }

    // The bytes of the body after its code: the regions, then the frames.
    private static String regions(byte[] file) throws ModuleFormatException {
        var in = new ByteSource(section(file, SectionId.CODE));
        in.uleb();
        in.uleb();
        in.skip(in.uleb());
        return Leb128Test.hex(in.bytes(in.remaining()));
    }
}
