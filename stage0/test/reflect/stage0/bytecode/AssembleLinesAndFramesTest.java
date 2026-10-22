package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleLinesAndFramesTest {
    private static final String HASH = "hex \"" + "ab".repeat(32) + "\"";
    // The body starts on line 5.
    private static final String B = ".module m 0.0.0\n.source \"a\" " + HASH + "\n.class a.A {\n"
            + ".method static void m(int x) {\n";
    private static final String E = "\n}\n}";

    @Test
    public static void linesAndFramesGiveTheBodiesThatTheyState() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".source \"src/A.rfl\" hex \"" + "11".repeat(32) + "\"\n"
                + ".class public demo.A {\n"
                + "    .method public static int run(int x) {\n"
                + "        .line \"src/A.rfl\" 3\n"
                + "        ldarg x\n"
                + "        brtrue L\n"
                + "        .line 4\n"
                + "        ldc.i4 0\n"
                + "        ret\n"
                + "    L:\n"
                + "        .stackmap (i32) ()\n"
                + "        .line \"src/B.rfl\" 9\n"
                + "        ldc.i4 1\n"
                + "        ret\n"
                + "    }\n"
                + "    .method public static void all() {\n"
                + "        .locals (int, int, int, int, int, int, int, int, int, int, int, int)\n"
                + "        ldnull\n"
                + "        brtrue L\n"
                + "    L:\n"
                + "        .stackmap (i32, i64, f32, f64, null, ref demo.A, uninit demo.A, byref demo.A,\n"
                + "            byref.readonly demo.A, ptr, struct demo.S, class demo.A) ()\n"
                + "        ret\n"
                + "    }\n"
                + "}\n"
                + ".class public struct demo.S {\n"
                + "}\n"
                + ".source \"src/B.rfl\" hex \"" + "22".repeat(32) + "\"\n";
        var expected = new ModuleDef("demo", 1, 0, 0);
        var a = new TypeDef("demo", "A", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var s = new TypeDef("demo", "S", Flags.KIND_STRUCT, Flags.PUBLIC, 0, null);
        TypeSig i32 = new TypeSig.Primitive(SigCode.I32);
        var fileA = new SourceFile("src/A.rfl", filled(0x11));
        var fileB = new SourceFile("src/B.rfl", filled(0x22));
        var run = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "run",
                new MethodSig(SigCode.METHOD_STATIC, 0, i32, list(i32)));
        run.params().add(new ParamDef(0, "x"));
        var runBody = new MethodBody(1);
        var target = new Label();
        Instruction[] code = {new Instruction.Index(Opcode.LDARG, 0), new Instruction.Branch(Opcode.BRTRUE, target),
            new Instruction.Value32(Opcode.LDC_I4, 0), new Instruction.Plain(Opcode.RET),
            new Instruction.Value32(Opcode.LDC_I4, 1), new Instruction.Plain(Opcode.RET)};
        for (Instruction instruction : code) {
            runBody.code().add(instruction);
        }
        target.mark(runBody, 4);
        runBody.lines().add(new Line(at(runBody, 0), fileA, 3));
        runBody.lines().add(new Line(at(runBody, 2), fileA, 4));
        runBody.lines().add(new Line(at(runBody, 4), fileB, 9));
        runBody.frames().add(new Frame(target, list(new Frame.VType(VerificationType.I32, null)), list()));
        run.setBody(runBody);
        var all = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "all",
                new MethodSig(SigCode.METHOD_STATIC, 0, new TypeSig.Primitive(SigCode.VOID), list()));
        var allBody = new MethodBody(1);
        for (int i = 0; i < 12; i++) {
            allBody.locals().add(i32);
        }
        var place = new Label();
        allBody.code().add(new Instruction.Plain(Opcode.LDNULL));
        allBody.code().add(new Instruction.Branch(Opcode.BRTRUE, place));
        allBody.code().add(new Instruction.Plain(Opcode.RET));
        place.mark(allBody, 2);
        var slots = new reflect.stage0.lib.ArrayList<Frame.VType>();
        for (int tag = VerificationType.I32; tag <= VerificationType.CLASS; tag++) {
            slots.add(new Frame.VType(tag, !VerificationType.hasToken(tag) ? null : tag == VerificationType.STRUCT ? s : a));
        }
        allBody.frames().add(new Frame(place, slots, list()));
        all.setBody(allBody);
        a.methods().add(run);
        a.methods().add(all);
        expected.types().add(a);
        expected.types().add(s);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
    }

    @Test
    public static void eachSourceLineAndFrameHasItsDiagnostics() {
        String m = ".module m 0.0.0\n";
        unassemblable(m + ".source \"\" " + HASH, "2:9: RFA0301: a source file has an empty path (BC-17)");
        unassemblable(m + ".source \"a\" hex \"00\"",
                "2:9: RFA0301: the SHA-256 of the source file a has 1 bytes, not 32 (BC-17)");
        unassemblable(m + ".source \"a\" " + HASH + "\n.source \"a\" " + HASH,
                "3:9: RFA0301: two source files have the path a (BC-24)");
        unassemblable(B + ".line \"b\" 1\nret" + E, "5:7: RFA0208: no .source declares b");
        unassemblable(B + ".line 1\nret" + E, "5:1: RFA0208: the first .line of a method names its file");
        unassemblable(B + ".line \"a\" x\nret" + E, "5:11: RFA0101: expected a line number, found x");
        unassemblable(B + ".line \"a\" 0\nret" + E, "5:1: RFA0301: the line entry is at line 0; lines count from 1 (BC-17)");
        unassemblable(B + "ret\n.line \"a\" 1" + E, "6:1: RFA0301: the line entry is at no instruction of the body (BC-17)");
        unassemblable(B + ".line \"a\" 1\n.line 2\nret" + E, "6:1: RFA0104: two .line directives precede one instruction");
        String branch = "ldarg x\nbrtrue L\nL: ";
        unassemblable(B + branch + ".stackmap (foo) ()\nret" + E, "7:15: RFA0101: expected a verification type, found foo");
        unassemblable(B + branch + ".stackmap (i32 i64) ()\nret" + E, "7:19: RFA0101: expected ), found i64");
        unassemblable(B + branch + ".stackmap (ref int) ()\nret" + E,
                "7:19: RFA0303: the type needs a TypeSpec row, which arrives in v0.0.6");
        unassemblable(B + branch + ".stackmap (i32) ()\n.stackmap (i32) ()\nret" + E,
                "8:1: RFA0104: two .stackmap directives precede one instruction");
        unassemblable(B + ".stackmap (i32) ()\nret" + E, "5:1: RFA0301: the stack map frame is at instruction 0, which is"
                + " neither a branch target nor a handler start (BC-19)");
        unassemblable(B + branch + ".stackmap () ()\nret" + E,
                "7:4: RFA0301: the stack map frame has 0 slots, but the method has 1 arguments and 0 locals (BC-19)");
        unassemblable(B + branch + ".stackmap (i32) (i32, i32)\nret" + E,
                "7:4: RFA0301: the stack map frame has a stack of 2, deeper than the maximum of 1 (BC-19)");
        unassemblable(B + "ret\n.stackmap (i32) ()" + E, "6:1: RFA0301: the stack map frame is at no instruction of the body (BC-19)");
    }

    private static Label at(MethodBody body, int index) {
        var label = new Label();
        label.mark(body, index);
        return label;
    }

    private static byte[] filled(int value) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) value;
        }
        return hash;
    }
}
