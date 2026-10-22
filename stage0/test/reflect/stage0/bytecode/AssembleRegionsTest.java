package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleRegionsTest {
    // The body starts on line 4.
    private static final String B = ".module m 0.0.0\n.class a.A {\n.method static void m() {\n";
    private static final String E = "\n}\n}";

    @Test
    public static void regionsAndMaxStackGiveTheBodiesThatTheyState() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".requires reflect.core\n"
                + ".class public demo.A {\n"
                + "    .method public static int run(int x) {\n"
                + "        .try T0 T1 .catch reflect.core.Exception H0 H1\n"
                + "    T0: ldarg x\n"
                + "        pop\n"
                + "        leave END\n"
                + "    T1:\n"
                + "    H0: pop\n"
                + "        leave END\n"
                + "    H1:\n"
                + "    F0: endfinally\n"
                + "    F1:\n"
                + "    END: ldc.i4 0\n"
                + "        ret\n"
                + "        .try T0 T1 .catch reflect.core.Error H0 H1\n"
                + "        .try T0 H1 .finally F0 F1\n"
                + "    }\n"
                + "    .method public static void fixed() {\n"
                + "        .maxstack 7\n"
                + "        pop\n"
                + "        ret\n"
                + "    }\n"
                + "}\n";
        var expected = new ModuleDef("demo", 1, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var exception = new TypeRef(core, "reflect.core", "Exception");
        var error = new TypeRef(core, "reflect.core", "Error");
        var a = new TypeDef("demo", "A", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        TypeSig i32 = new TypeSig.Primitive(SigCode.I32);
        TypeSig voidType = new TypeSig.Primitive(SigCode.VOID);
        var run = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "run",
                new MethodSig(SigCode.METHOD_STATIC, 0, i32, TestModules.list(i32)));
        run.params().add(new ParamDef(0, "x"));
        var body = new MethodBody(1);
        var tryStart = new Label();
        var handler = new Label();
        var finallyStart = new Label();
        var end = new Label();
        Instruction[] code = {new Instruction.Index(Opcode.LDARG, 0), new Instruction.Plain(Opcode.POP),
            new Instruction.Branch(Opcode.LEAVE, end), new Instruction.Plain(Opcode.POP),
            new Instruction.Branch(Opcode.LEAVE, end), new Instruction.Plain(Opcode.ENDFINALLY),
            new Instruction.Value32(Opcode.LDC_I4, 0), new Instruction.Plain(Opcode.RET)};
        for (Instruction instruction : code) {
            body.code().add(instruction);
        }
        tryStart.mark(body, 0);
        handler.mark(body, 3);
        finallyStart.mark(body, 5);
        end.mark(body, 6);
        body.regions().add(new Region(Flags.REGION_CATCH, tryStart, handler, handler, finallyStart, exception));
        body.regions().add(new Region(Flags.REGION_CATCH, tryStart, handler, handler, finallyStart, error));
        body.regions().add(new Region(Flags.REGION_FINALLY, tryStart, finallyStart, finallyStart, end, null));
        run.setBody(body);
        var fixed = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "fixed",
                new MethodSig(SigCode.METHOD_STATIC, 0, voidType, TestModules.list()));
        var fixedBody = new MethodBody(7);
        fixedBody.code().add(new Instruction.Plain(Opcode.POP));
        fixedBody.code().add(new Instruction.Plain(Opcode.RET));
        fixed.setBody(fixedBody);
        a.methods().add(run);
        a.methods().add(fixed);
        expected.requires().add(core);
        expected.typeRefs().add(exception);
        expected.typeRefs().add(error);
        expected.types().add(a);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
    }

    @Test
    public static void handlersStartAtTheirDepths() throws Exception {
        String text = ".module m 0.0.0\n.requires reflect.core\n.class a.A {\n"
                + ".method static void caught() {\n.try A B .catch reflect.core.Exception B C\n"
                + "A: leave C\nB: pop\nleave C\nC: ret\n}\n"
                + ".method static void deep() {\n.try A B .catch reflect.core.Exception B C\n"
                + "A: leave C\nB: ldc.i4 0\npop\npop\nleave C\nC: ret\n}\n"
                + ".method static void done() {\n.try A B .finally B C\nA: leave C\nB: endfinally\nC: ret\n}\n}";
        var depths = new StringBuilder();
        for (MethodDef method : ModuleReader.read(assemble(text)).types().get(0).methods()) {
            MethodBody body = method.body();
            depths.append(method.name()).append(' ').append(body == null ? -1 : body.maxStack()).append("; ");
        }
        assertEquals("caught 1; deep 2; done 0; ", depths.toString());
    }

    @Test
    public static void eachRegionAndMaxStackHasItsDiagnostics() {
        String body = "A: nop\nB: nop\nC: nop\nD: ret";
        unassemblable(B + ".try A B .bogus C D\n" + body + E, "4:10: RFA0101: expected .catch or .finally, found .bogus");
        unassemblable(B + ".try A B .finally C" + E, "5:1: RFA0101: expected a label, found }");
        unassemblable(B + ".try A B .catch int C D\n" + body + E,
                "4:17: RFA0303: the type needs a TypeSpec row, which arrives in v0.0.6");
        unassemblable(B + ".try A X .finally C D\n" + body + E, "4:8: RFA0206: the body marks no label X");
        unassemblable(B + ".try A A .finally C D\n" + body + E, "4:1: RFA0301: the try of the region holds no instruction (BC-18)");
        unassemblable(B + ".try A C .finally B D\n" + body + E, "4:1: RFA0301: the handler of the region overlaps its try (BC-18)");
        unassemblable(B + ".try A C .finally C D\n.try B D .finally A B\n" + body + E,
                "5:1: RFA0301: a try or handler of the region overlaps one of region 0 without nesting (BC-18)");
        unassemblable(B + ".try A B .finally C D\n.try A B .finally C D\n" + body + E,
                "5:1: RFA0301: two exception regions are equal (BC-18)");
        unassemblable(B + ".try A B .catch a.A B C\nA: nop\nB: pop\nC: ret" + E,
                "6:4: RFA0304: two paths reach pop with stack depths 1 and 0 (Q77)");
        unassemblable(B + ".maxstack 65536\nret" + E,
                "4:11: RFA0302: the maximum stack depth of 65536 exceeds the limit of 65535 (BC-21)");
        unassemblable(B + ".maxstack 1\n.maxstack 1\nret" + E, "5:1: RFA0104: the body has one .maxstack");
        unassemblable(B + ".maxstack x\nret" + E, "4:11: RFA0101: expected a stack depth, found x");
        unassemblable(B + ".catch a.A B C\nret" + E,
                "4:1: RFA0101: expected an instruction, a label, a directive of a body or }, found .catch");
    }
}
