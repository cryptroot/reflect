package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;

public final class CodeTest {
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);
    private static final MethodSig STATIC_VOID = new MethodSig(SigCode.METHOD_STATIC, 0, VOID, list());

    @Test
    public static void bodiesFollowTheirMethodRowsWithEachOperandKind() throws Exception {
        byte[] file = roundTrip(calc());
        assertEquals("02 00 06 10 00 10 01 18 B0 00 00 "
                // show: maxStack 2, LOCALS at BLOBS 0x12, 0x35 bytes of code, then no regions and no frames.
                + "02 12 35 02 7F 14 00 06 01 14 01 03 80 80 80 80 80 20 0A 04 00 00 C0 3F 0A "
                + "05 00 00 00 00 00 00 00 80 0A 06 06 0A 06 01 0A 10 00 0A 02 02 02 03 FE 00 0A FE 65 3C 01 B0 00 00",
                Leb128Test.hex(section(file, SectionId.CODE)));
        // m and n have no body; add starts CODE, so its code is 0 as well.
        assertEquals("0 0 11 0", column(file, TableId.METHOD, 1, 3) + " " + column(file, TableId.METHOD, 2, 3) + " "
                + column(file, TableId.METHOD, 3, 3) + " " + column(file, TableId.METHOD, 4, 3));
        // "hi" enters LITERALS at its first use, before "bye"; the second "hi" shares its entry.
        assertEquals("00 02 68 00 69 00 03 62 00 79 00 65 00", Leb128Test.hex(section(file, SectionId.LITERALS)));
        TypeDef read = ModuleReader.read(file).types().get(0);
        assertEquals(calc().types().get(0).methods().get(2).body().code(), read.methods().get(2).body().code());
        assertEquals("I32 reflect.core.String?", SignatureTest.describe(read.methods().get(2).body().locals().get(0))
                + " " + SignatureTest.describe(read.methods().get(2).body().locals().get(1)));
        assertEquals(null, read.methods().get(0).body());
    }

    @Test
    public static void theWriterChecksBodies() throws Exception {
        var body = new MethodBody(0);
        body.code().add(new Instruction.Plain(Opcode.RET));
        unwritable(method(Flags.METHOD_ABSTRACT, new MethodSig(0, 0, VOID, list()), body), "method a.A::m has a body;"
                + " a method has one exactly when it is not ABSTRACT, NATIVE or EXTERN (BC-11)");
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, null), "method a.A::m has no body; a method has one"
                + " exactly when it is not ABSTRACT, NATIVE or EXTERN (BC-11)");
        instruction(new Instruction.Plain(0x0B), "opcode 0xB is not defined (BC-04)");
        instruction(new Instruction.Plain(0xFE70), "opcode 0xFE70 is not defined (BC-04)");
        instruction(new Instruction.Plain(Opcode.BREAKPOINT), "a module never contains breakpoint (BC-04)");
        instruction(new Instruction.Plain(Opcode.LDC_I4), "ldc.i4 takes the operands <i4> (BC-04)");
        instruction(new Instruction.Value32(Opcode.ADD_I32, 1), "add.i32 takes no operand (BC-04)");
        instruction(new Instruction.Index(Opcode.LDLOC, 0), "local 0 does not exist; the body has 0 locals (BC-04)");
        // An instance method's argument 0 is this, so void m(int) has arguments 0 and 1.
        var instance = new MethodSig(0, 0, VOID, list(INT));
        roundTrip(method(Flags.PUBLIC, instance, code(0, new Instruction.Index(Opcode.LDARG, 1))));
        unwritable(method(Flags.PUBLIC, instance, code(0, new Instruction.Index(Opcode.STARG, 2))),
                "method a.A::m: instruction 0: argument 2 does not exist; the method has 2 arguments (BC-04)");
        var withLocal = code(0, new Instruction.Index(Opcode.LDLOCA, 1));
        withLocal.locals().add(INT);
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, withLocal),
                "method a.A::m: instruction 0: local 1 does not exist; the body has 1 locals (BC-04)");
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, code(65_536)),
                "method a.A::m: the maximum stack depth of 65536 exceeds the limit of 65535 (BC-21)");
        var voidLocal = code(0);
        voidLocal.locals().add(VOID);
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, voidLocal),
                "method a.A::m: locals: VOID stands outside a return type (BC-03)");
        var manyLocals = code(0);
        for (int i = 0; i <= Limits.MAX_LOCALS; i++) {
            manyLocals.locals().add(INT);
        }
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, manyLocals),
                "method a.A::m: locals: the body has 65536 locals, more than the limit of 65535 (BC-21)");
        // ldc.r8 takes 9 bytes: 1,864,135 of them fill the limit of 16,777,215 bytes, and one more passes it.
        var large = code(1);
        var r8 = new Instruction.Value64(Opcode.LDC_R8, 0);
        for (int i = 0; i <= Limits.MAX_CODE_SIZE / 9; i++) {
            large.code().add(r8);
        }
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, large),
                "method a.A::m has 16777224 bytes of code, more than the limit of 16777215 (BC-21)");
    }

    @Test
    public static void theReaderChecksEachInstructionAtItsOffset() throws Exception {
        int[] none = {};
        body(3, "opcode 0xB is not defined (BC-04)", none, 0x01, 0x00, 0x01, 0x0B, 0x00, 0x00);
        body(3, "opcode 0xFE70 is not defined (BC-04)", none, 0x01, 0x00, 0x02, 0xFE, 0x70, 0x00, 0x00);
        body(3, "a module never contains breakpoint (BC-04)", none, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x00);
        body(4, "SLEB128 value is not minimal", none, 0x01, 0x00, 0x03, 0x02, 0x81, 0x00, 0x00, 0x00);
        body(4, "truncated SLEB128 value", none, 0x01, 0x00, 0x01, 0x02, 0x00, 0x00);
        body(4, "needs 4 bytes, but 2 remain", none, 0x01, 0x00, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00);
        body(3, "argument 0 does not exist; the method has 0 arguments (BC-04)", none,
                0x01, 0x00, 0x02, 0x10, 0x00, 0x00, 0x00);
        body(4, "LITERALS offset 0x5 is not the start of an entry", none, 0x01, 0x00, 0x02, 0x06, 0x05, 0x00, 0x00);
        body(3, "local 1 does not exist; the body has 1 locals (BC-04)", new int[] {0x22, 0x01, 0x08},
                0x01, 0x06, 0x02, 0x13, 0x01, 0x00, 0x00);
    }

    @Test
    public static void theReaderChecksTheFieldsOfABody() throws Exception {
        int[] none = {};
        body(0, "the maximum stack depth of 65536 exceeds the limit of 65535 (BC-21)", none,
                0x80, 0x80, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00);
        body(2, "the code of 16777216 bytes exceeds the limit of 16777215 (BC-21)", none,
                0x01, 0x00, 0x80, 0x80, 0x80, 0x08, 0x00, 0x00);
        body(2, "the code of 5 bytes runs past the end of CODE", none, 0x01, 0x00, 0x05, 0x00, 0x00, 0x00);
        body(4, "this reader does not support exception regions yet", none, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00);
        body(5, "this reader does not support stack maps yet", none, 0x01, 0x00, 0x01, 0x00, 0x00, 0x01);
        body(6, "CODE continues after the last body (BC-17)", none, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00);
        locals(0, "a LOCALS signature lists at least one type; a body without locals has locals 0 (BC-17)", 0x22, 0x00);
        locals(0, "VOID stands outside a return type (BC-03)", 0x22, 0x01, 0x01);
        locals(0, "a locals signature starts with LOCALS (BC-03)", 0x21, 0x00, 0x00, 0x01);
        byte[] file = roundTrip(calc());
        rejected(patch(file, TableId.METHOD, 3, 3, 12), columnOffset(file, TableId.METHOD, 3, 3),
                "the body of the method starts at CODE offset 0xB (BC-17)");
        // Without NATIVE, the method n needs a body, and the module has no CODE.
        var natives = new ModuleDef("a", 0, 0, 0);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        type.methods().add(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "n", STATIC_VOID));
        natives.types().add(type);
        byte[] bodiless = roundTrip(natives);
        rejected(patch(bodiless, TableId.METHOD, 1, 0, Flags.METHOD_STATIC), columnOffset(bodiless, TableId.METHOD, 1, 3),
                "the method has a body, but the module has no CODE section (BC-24)");
    }

    @Test
    public static void tokensNameTypesFieldsAndMethods() throws Exception {
        ModuleDef module = box();
        byte[] file = roundTrip(module);
        // Tokens: Box 05000001, Object 06000001, String 06000002, value 08000001, get 09000001,
        // Object::hashCode 0F000001, String::length 0F000002; each takes four bytes of ULEB128.
        assertEquals("01 00 08 10 00 BA 81 80 80 40 B0 00 00 01 00 3C 07 82 80 80 30 0A 10 00 C2 81 80 80 28 "
                + "E8 81 80 80 48 0A 10 00 EA 81 80 80 78 0A 10 00 C2 81 80 80 28 BA 81 80 80 40 0A 10 00 "
                + "BA 82 80 80 78 0A 12 00 EC 81 80 80 30 81 80 80 78 B0 00 00",
                Leb128Test.hex(section(file, SectionId.CODE)));
        MethodDef read = ModuleReader.read(file).types().get(0).methods().get(1);
        assertEquals(describe(module.types().get(0).methods().get(1).body()), describe(read.body()));
        // The reader resolves a MemberRef token to its row: patching String::length to Object::hashCode,
        // which is a method, makes ldfld name a method. The ldfld is at offset 42 of the code of use.
        int ldfld = sectionOffset(file, SectionId.CODE) + 13 + 3 + 42;
        rejected(TestModules.patchByte(file, ldfld + 1, 0x81), ldfld,
                "ldfld names a field, but the MemberRef reflect.core.Object::hashCode is a method (BC-04)");
    }

    @Test
    public static void theWriterChecksTokenOperands() {
        ModuleDef module = box();
        TypeDef type = module.types().get(0);
        TypeRef object = module.typeRefs().get(0);
        MemberRef hashCode = module.memberRefs().get(0);
        MemberRef length = module.memberRefs().get(1);
        token(new Instruction.Type(Opcode.LDFLD, type), "ldfld names a field (BC-04)");
        token(new Instruction.Field(Opcode.CALL, type.fields().get(0)), "call names a method (BC-04)");
        token(new Instruction.Method(Opcode.LDTYPE, type.methods().get(0)), "ldtype names a type (BC-04)");
        token(new Instruction.Constrained(Opcode.CALL, object, hashCode), "call takes the operands <tok> (BC-04)");
        token(new Instruction.Type(Opcode.CALL_CONSTRAINED, object), "call.constrained takes the operands <tok> <tok> (BC-04)");
        token(new Instruction.Field(Opcode.LDSFLD, hashCode),
                "ldsfld names a field, but the MemberRef reflect.core.Object::hashCode is a method (BC-04)");
        token(new Instruction.Method(Opcode.CALLVIRT, length),
                "callvirt names a method, but the MemberRef reflect.core.String::length is a field (BC-04)");
        token(new Instruction.Constrained(Opcode.CALL_CONSTRAINED, object, length),
                "call.constrained names a method, but the MemberRef reflect.core.String::length is a field (BC-04)");
        unlisted(new Instruction.Field(Opcode.LDFLD, new FieldDef(0, "x", INT)),
                "an instruction names the field x, which the module does not list");
        unlisted(new Instruction.Method(Opcode.CALL, new MemberRef(object, "toString", new MethodSig(0, 0, VOID, list()))),
                "an instruction names the MemberRef reflect.core.Object::toString, which the module does not list");
        unlisted(new Instruction.Type(Opcode.LDTYPE, new TypeRef(module.requires().get(0), "reflect.core", "Thing")),
                "a type in an instruction is reflect.core.Thing, which the module does not list");
    }

    @Test
    public static void theReaderChecksTheTablesThatTokensName() throws Exception {
        int[] none = {};
        body(4, "token 0x9000001 does not name a TypeDef, TypeRef or TypeSpec row (BC-04)", none,
                0x01, 0x00, 0x05, 0x07, 0x81, 0x80, 0x80, 0x48, 0x00, 0x00);
        body(4, "token 0x5000001 does not name a Field or MemberRef row (BC-04)", none,
                0x01, 0x00, 0x05, 0xBA, 0x81, 0x80, 0x80, 0x28, 0x00, 0x00);
        body(4, "token 0x5000001 does not name a Method, MemberRef or MethodSpec row (BC-04)", none,
                0x01, 0x00, 0x05, 0xE8, 0x81, 0x80, 0x80, 0x28, 0x00, 0x00);
        body(4, "token 0x9000002 does not name a Method, MemberRef or MethodSpec row (BC-04)", none,
                0x01, 0x00, 0x05, 0xE8, 0x82, 0x80, 0x80, 0x48, 0x00, 0x00);
        body(8, "token 0x8000001 does not name a Method, MemberRef or MethodSpec row (BC-04)", none,
                0x01, 0x00, 0x09, 0xEC, 0x81, 0x80, 0x80, 0x28, 0x81, 0x80, 0x80, 0x40, 0x00, 0x00);
        // f calls itself: Method row 1.
        ModuleReader.read(bodyFile(none, 0x01, 0x00, 0x06, 0xE8, 0x81, 0x80, 0x80, 0x48, 0xB0, 0x00, 0x00));
    }

    @Test
    public static void branchOffsetsTakeTheBytesThatTheyNeed() throws Exception {
        // Forward over 63 and 64 bytes: SLEB128 takes one byte for 63 and two for 64.
        assertEquals("88 3F " + nops(63) + "B0", forward(63));
        assertEquals("88 C0 00 " + nops(64) + "B0", forward(64));
        // Forward over 8,191 and 8,192 bytes: two bytes, then three.
        assertEquals("88 FF 3F 00", forward(8191).substring(0, 11));
        assertEquals("88 80 C0 00 00", forward(8192).substring(0, 14));
        // Backward over 62 nops: -64 from the end of a two-byte br fits one byte, 40. Over 63 nops,
        // -65 needs two bytes, so the br takes three, and its offset is -66, BE 7F.
        assertEquals(nops(62) + "88 40", backward(62));
        assertEquals(nops(63) + "88 BE 7F", backward(63));
        // A br to itself: -2.
        assertEquals("88 7E", backward(0));
    }

    @Test
    public static void theLayoutRepeatsUntilNoSizeChanges() throws Exception {
        // br A, br B, 61 nops, A: 3 nops, B: ret. br B spans 64 bytes and grows to three; then br A
        // spans 64 bytes too and grows, which a single pass would miss.
        var body = new MethodBody(0);
        var a = new Label();
        var b = new Label();
        body.code().add(new Instruction.Branch(Opcode.BR, a));
        body.code().add(new Instruction.Branch(Opcode.BR, b));
        addNops(body, 61);
        body.mark(a);
        addNops(body, 3);
        body.mark(b);
        body.code().add(new Instruction.Plain(Opcode.RET));
        assertEquals("88 C0 00 88 C0 00 " + nops(64) + "B0", codeBytes(body));
        // switch (L0, L2) after L0: nop, then nop and L2: ret. Both offsets count from the end of the switch.
        var choice = new MethodBody(0);
        var l0 = new Label();
        var l2 = new Label();
        choice.mark(l0);
        choice.code().add(new Instruction.Plain(Opcode.NOP));
        choice.code().add(new Instruction.Switch(Opcode.SWITCH, list(l0, l2)));
        choice.code().add(new Instruction.Plain(Opcode.NOP));
        choice.mark(l2);
        choice.code().add(new Instruction.Plain(Opcode.RET));
        assertEquals("00 AD 02 7B 01 00 B0", codeBytes(choice));
        MethodBody read = ModuleReader.read(roundTrip(method(Flags.METHOD_STATIC, STATIC_VOID, choice))).types().get(0)
                .methods().get(0).body();
        var targets = ((Instruction.Switch) read.code().get(1)).targets();
        assertEquals("0 3", targets.get(0).index() + " " + targets.get(1).index());
    }

    @Test
    public static void theWriterChecksTheTargetsOfBranches() {
        instruction(new Instruction.Branch(Opcode.BR, new Label()), "br targets a label that this body does not mark (BC-17)");
        var other = new MethodBody(0);
        var elsewhere = new Label();
        other.mark(elsewhere);
        instruction(new Instruction.Switch(Opcode.SWITCH, list(elsewhere)),
                "switch targets a label that this body does not mark (BC-17)");
        var end = new Label();
        var body = code(0, new Instruction.Branch(Opcode.BRTRUE, end));
        body.mark(end);
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, body),
                "method a.A::m: instruction 0: brtrue targets the end of the code, after its last instruction (BC-17)");
        instruction(new Instruction.Plain(Opcode.BR), "br takes the operands <off> (BC-04)");
        assertEquals("The label is marked already",
                assertThrows(IllegalStateException.class, () -> body.mark(end)).getMessage());
    }

    @Test
    public static void theReaderChecksThatBranchesLandOnInstructions() throws Exception {
        int[] none = {};
        // ldc.i4 64 takes three bytes, and br -4 lands at its second.
        body(7, "the branch lands at code offset 1, inside an instruction (BC-17)", none,
                0x01, 0x00, 0x05, 0x02, 0xC0, 0x00, 0x88, 0x7C, 0x00, 0x00);
        body(8, "the branch lands at code offset 2, inside an instruction (BC-17)", none,
                0x01, 0x00, 0x06, 0x02, 0xC0, 0x00, 0xAD, 0x01, 0x7C, 0x00, 0x00);
        body(4, "the branch lands at code offset 2, outside the code (BC-17)", none, 0x01, 0x00, 0x02, 0x88, 0x00, 0x00, 0x00);
        body(4, "the branch lands at code offset -1, outside the code (BC-17)", none, 0x01, 0x00, 0x02, 0x88, 0x7D, 0x00, 0x00);
        body(4, "SLEB128 value is not minimal", none, 0x01, 0x00, 0x03, 0x88, 0x80, 0x00, 0x00, 0x00);
        body(4, "a switch of 5 targets runs past the end of the code", none, 0x01, 0x00, 0x02, 0xAD, 0x05, 0x00, 0x00);
    }

    // The code of a module whose only method is static void a.A::m() with this body.
    private static String codeBytes(MethodBody body) throws ModuleFormatException {
        byte[] file = roundTrip(method(Flags.METHOD_STATIC, STATIC_VOID, body));
        var in = new ByteSource(section(file, SectionId.CODE));
        in.uleb();
        in.uleb();
        return Leb128Test.hex(in.bytes(in.uleb()));
    }

    // br L, n nops, L: ret.
    private static String forward(int nops) throws ModuleFormatException {
        var body = new MethodBody(0);
        var target = new Label();
        body.code().add(new Instruction.Branch(Opcode.BR, target));
        addNops(body, nops);
        body.mark(target);
        body.code().add(new Instruction.Plain(Opcode.RET));
        return codeBytes(body);
    }

    // L: n nops, br L.
    private static String backward(int nops) throws ModuleFormatException {
        var body = new MethodBody(0);
        var target = new Label();
        body.mark(target);
        addNops(body, nops);
        body.code().add(new Instruction.Branch(Opcode.BR, target));
        return codeBytes(body);
    }

    private static void addNops(MethodBody body, int count) {
        for (int i = 0; i < count; i++) {
            body.code().add(new Instruction.Plain(Opcode.NOP));
        }
    }

    private static String nops(int count) {
        return "00 ".repeat(count);
    }

    // public class app.Box: int value; int get(); static void use(reflect.core.Object), whose code
    // names a TypeDef, TypeRefs, a Field row, a Method row and MemberRefs of a field and a method.
    private static ModuleDef box() {
        var module = new ModuleDef("app", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        module.requires().add(core);
        var object = new TypeRef(core, "reflect.core", "Object");
        var string = new TypeRef(core, "reflect.core", "String");
        module.typeRefs().add(object);
        module.typeRefs().add(string);
        var hashCode = new MemberRef(object, "hashCode", new MethodSig(0, 0, INT, list()));
        var length = new MemberRef(string, "length", new FieldSig(INT));
        module.memberRefs().add(hashCode);
        module.memberRefs().add(length);
        var box = new TypeDef("app", "Box", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        module.types().add(box);
        var value = new FieldDef(0, "value", INT);
        box.fields().add(value);
        var get = new MethodDef(0, "get", new MethodSig(0, 0, INT, list()));
        get.setBody(code(1, new Instruction.Index(Opcode.LDARG, 0), new Instruction.Field(Opcode.LDFLD, value),
                new Instruction.Plain(Opcode.RET)));
        box.methods().add(get);
        var use = new MethodDef(Flags.METHOD_STATIC, "use", new MethodSig(SigCode.METHOD_STATIC, 0, VOID,
                list(new TypeSig.Named(false, object))));
        use.params().add(new ParamDef(0, "o"));
        var pop = new Instruction.Plain(Opcode.POP);
        var arg = new Instruction.Index(Opcode.LDARG, 0);
        var cast = new Instruction.Type(Opcode.CASTCLASS, box);
        use.setBody(code(1, new Instruction.Type(Opcode.LDTYPE, string), pop, arg, cast,
                new Instruction.Method(Opcode.CALL, get), pop, arg, new Instruction.Method(Opcode.CALLVIRT, hashCode), pop,
                arg, cast, new Instruction.Field(Opcode.LDFLD, value), pop, arg, new Instruction.Field(Opcode.LDFLD, length),
                pop, new Instruction.Index(Opcode.LDARGA, 0), new Instruction.Constrained(Opcode.CALL_CONSTRAINED, object,
                hashCode), new Instruction.Plain(Opcode.RET)));
        box.methods().add(use);
        return module;
    }

    // Expects the writer to reject app.Box::m, whose only instruction is this one.
    private static void token(Instruction instruction, String problem) {
        unwritable(boxWith(instruction), "method app.Box::m: instruction 0: " + problem);
    }

    // Expects the writer to reject app.Box::m, whose only instruction names a row that the module lacks.
    private static void unlisted(Instruction instruction, String message) {
        unwritable(boxWith(instruction), message);
    }

    private static ModuleDef boxWith(Instruction instruction) {
        ModuleDef module = box();
        var m = new MethodDef(Flags.METHOD_STATIC, "m", STATIC_VOID);
        m.setBody(code(1, instruction));
        module.types().get(0).methods().add(m);
        return module;
    }

    // One line per instruction: the mnemonic, and the rows that its tokens name.
    private static String describe(MethodBody body) {
        var text = new StringBuilder();
        for (Instruction instruction : body.code()) {
            text.append(Opcode.mnemonic(instruction.opcode()));
            if (instruction instanceof Instruction.Type type) {
                text.append(' ').append(type.type().qualifiedName());
            } else if (instruction instanceof Instruction.Field field) {
                text.append(' ').append(describe(field.field()));
            } else if (instruction instanceof Instruction.Method method) {
                text.append(' ').append(describe(method.method()));
            } else if (instruction instanceof Instruction.Constrained constrained) {
                text.append(' ').append(constrained.type().qualifiedName()).append(' ').append(describe(constrained.method()));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String describe(Handle handle) {
        return handle instanceof MemberRef ref ? ref.parent().qualifiedName() + "::" + ref.name()
                : handle instanceof FieldDef field ? "field " + field.name() : "method " + ((MethodDef) handle).name();
    }

    // public abstract class app.Calc: abstract void m(); static int add(int, int); void show(), with
    // two locals and every operand kind but tokens and branches; static native void n().
    private static ModuleDef calc() {
        var module = new ModuleDef("app", 0, 0, 0);
        module.setUnsafe(true);
        var core = new ModuleRef("reflect.core", 0);
        module.requires().add(core);
        var string = new TypeRef(core, "reflect.core", "String");
        module.typeRefs().add(string);
        var calc = new TypeDef("app", "Calc", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_ABSTRACT, 0, null);
        module.types().add(calc);
        calc.methods().add(new MethodDef(Flags.METHOD_ABSTRACT | Flags.METHOD_VIRTUAL, "m", new MethodSig(0, 0, VOID, list())));
        var add = new MethodDef(Flags.METHOD_STATIC, "add", new MethodSig(SigCode.METHOD_STATIC, 0, INT, list(INT, INT)));
        add.params().add(new ParamDef(0, "a"));
        add.params().add(new ParamDef(0, "b"));
        add.setBody(code(2, new Instruction.Index(Opcode.LDARG, 0), new Instruction.Index(Opcode.LDARG, 1),
                new Instruction.Plain(Opcode.ADD_I32), new Instruction.Plain(Opcode.RET)));
        calc.methods().add(add);
        var show = new MethodDef(0, "show", new MethodSig(0, 0, VOID, list()));
        var pop = new Instruction.Plain(Opcode.POP);
        var body = code(2, new Instruction.Value32(Opcode.LDC_I4, -1), new Instruction.Index(Opcode.STLOC, 0),
                new Instruction.Literal(Opcode.LDSTR, "hi"), new Instruction.Index(Opcode.STLOC, 1),
                new Instruction.Value64(Opcode.LDC_I8, 1L << 40), pop,
                new Instruction.Value32(Opcode.LDC_R4, Float.floatToRawIntBits(1.5f)), pop,
                new Instruction.Value64(Opcode.LDC_R8, Double.doubleToRawLongBits(-0.0)), pop,
                new Instruction.Literal(Opcode.LDSTR, "bye"), pop, new Instruction.Literal(Opcode.LDSTR, "hi"), pop,
                new Instruction.Index(Opcode.LDARG, 0), pop, new Instruction.Value32(Opcode.LDC_I4, 2),
                new Instruction.Value32(Opcode.LDC_I4, 3), new Instruction.Plain(Opcode.ADD_OVF_I32), pop,
                new Instruction.Indexes(Opcode.SYSCALL, 60, 1), new Instruction.Plain(Opcode.RET));
        body.locals().add(INT);
        body.locals().add(new TypeSig.NullableRef(new TypeSig.Named(false, string)));
        show.setBody(body);
        calc.methods().add(show);
        calc.methods().add(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "n", STATIC_VOID));
        return module;
    }

    private static MethodBody code(int maxStack, Instruction... instructions) {
        var body = new MethodBody(maxStack);
        for (Instruction instruction : instructions) {
            body.code().add(instruction);
        }
        return body;
    }

    // A module with the class a.A and its method m, with these flags, signature and body.
    private static ModuleDef method(int flags, MethodSig signature, MethodBody body) {
        var module = new ModuleDef("a", 0, 0, 0);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        var method = new MethodDef(flags, "m", signature);
        for (int i = 0; i < signature.params().size(); i++) {
            method.params().add(new ParamDef(0, "p" + i));
        }
        method.setBody(body);
        type.methods().add(method);
        module.types().add(type);
        return module;
    }

    private static void instruction(Instruction instruction, String problem) {
        unwritable(method(Flags.METHOD_STATIC, STATIC_VOID, code(0, instruction)), "method a.A::m: instruction 0: "
                + problem);
    }

    // A module with static void a.A::f() and this body, whose LOCALS, when given, are BLOBS offset 6.
    private static byte[] bodyFile(int[] locals, int... body) throws ModuleFormatException {
        var blobs = new ByteSink();
        blobs.bytes(TestModules.bytes(0x00, 0x04, SigCode.METHOD, SigCode.METHOD_STATIC, 0x00, SigCode.VOID));
        if (locals.length > 0) {
            blobs.uleb(locals.length);
            blobs.bytes(TestModules.bytes(locals));
        }
        byte[] tables = new TestModules.Tables()
                .rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.TYPE_DEF, 3, 5, Flags.KIND_CLASS, 0, 0, 0, 0, 1, 1, 1)
                .rows(TableId.METHOD, Flags.METHOD_STATIC, 7, 1, 0, 1, 1)
                .bytes();
        return TestModules.file(TestModules.idents("m", "A", "a", "f"), tables,
                new Section(SectionId.BLOBS, blobs.toByteArray()), new Section(SectionId.CODE, TestModules.bytes(body)));
    }

    // Expects the reader to reject the body at this offset into CODE.
    private static void body(int at, String message, int[] locals, int... body) throws ModuleFormatException {
        byte[] file = bodyFile(locals, body);
        rejected(file, sectionOffset(file, SectionId.CODE) + at, message);
    }

    // Expects the reader to reject the LOCALS signature at this offset into its payload.
    private static void locals(int at, String message, int... locals) throws ModuleFormatException {
        byte[] file = bodyFile(locals, 0x01, 0x06, 0x01, 0x00, 0x00, 0x00);
        rejected(file, sectionOffset(file, SectionId.BLOBS) + 7 + at, message);
    }
}
