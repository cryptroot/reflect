package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleInstructionsTest {
    // The instruction under test is on line 7.
    private static final String B = ".module m 0.0.0\n.requires other\n.class a.A {\n.field int f\n"
            + ".method static void m(int x) {\n.locals (int y, long z, int z)\n";
    private static final String E = "\n}\n}";

    @Test
    public static void instructionsGiveTheCodeThatTheyState() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".requires reflect.core\n"
                + ".requires other\n"
                + ".class public demo.A {\n"
                + "    .field private int count\n"
                + "    .field private static reflect.core.String name\n"
                + "    .method public void new() {\n"
                + "        ldarg 0\n"
                + "        call demo.A::reset(int)\n"
                + "        ret\n"
                + "    }\n"
                + "    .method public int reset(int value) {\n"
                + "        ldarg value\n"
                + "        stloc total\n"
                + "        .locals (long total, demo.A self, double)\n"
                + "        ldloc 1\n"
                + "        ldloca self\n"
                + "        starg value\n"
                + "        ldc.i4 -2147483648\n"
                + "        ldc.i8 0x7FFFFFFFFFFFFFFF\n"
                + "        ldc.r4 0.1\n"
                + "        ldc.r4 1.00000017881393432617187499\n"
                + "        ldc.r4 -inf\n"
                + "        ldc.r4 bits 0x7FC00001\n"
                + "        ldc.r8 2.5e-3\n"
                + "        ldc.r8 -0.0\n"
                + "        ldc.r8 bits 0xFFF8000000000000\n"
                + "        ldstr \"a\\tb\"\n"
                + "        ldfld demo.A::count\n"
                + "        ldsfld reflect.core.String demo.A::name\n"
                + "        ldsfld reflect.core.String other.Names::first\n"
                + "        stsfld reflect.core.String other.Names::first\n"
                + "        ldtype demo.A\n"
                + "        castclass reflect.core.String\n"
                + "        call static void other.Log::write(reflect.core.Object?[]...)\n"
                + "        call static void other.Log::write(int)\n"
                + "        callvirt reflect.core.String reflect.core.Object::toString()\n"
                + "        call.constrained demo.S demo.S::hash()\n"
                + "        newobj void demo.A::new()\n"
                + "        syscall 1 2\n"
                + "        ret\n"
                + "    }\n"
                + "    .method public static void loop(int n) {\n"
                + "    TOP:\n"
                + "        ldarg n\n"
                + "        brfalse END\n"
                + "        ldarg 0\n"
                + "        switch ()\n"
                + "        ldarg 0\n"
                + "        switch (TOP, END, MID)\n"
                + "    MID: br TOP\n"
                + "    END: ret\n"
                + "    }\n"
                + "}\n"
                + ".class public struct demo.S {\n"
                + "    .method public int hash() {\n"
                + "        ldc.i4 7\n"
                + "        ret\n"
                + "    }\n"
                + "}\n";
        var expected = new ModuleDef("demo", 1, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var other = new ModuleRef("other", 0);
        var string = new TypeRef(core, "reflect.core", "String");
        var object = new TypeRef(core, "reflect.core", "Object");
        var names = new TypeRef(other, "other", "Names");
        var log = new TypeRef(other, "other", "Log");
        var a = new TypeDef("demo", "A", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var s = new TypeDef("demo", "S", Flags.KIND_STRUCT, Flags.PUBLIC, 0, null);
        TypeSig i32 = new TypeSig.Primitive(SigCode.I32);
        TypeSig voidType = new TypeSig.Primitive(SigCode.VOID);
        TypeSig stringType = new TypeSig.Named(false, string);
        var count = new FieldDef(Flags.PRIVATE, "count", i32);
        var name = new FieldDef(Flags.PRIVATE | Flags.FIELD_STATIC, "name", stringType);
        a.fields().add(count);
        a.fields().add(name);
        var first = new MemberRef(names, "first", new FieldSig(stringType));
        var write = new MemberRef(log, "write", new MethodSig(SigCode.METHOD_STATIC | SigCode.METHOD_VARIADIC, 0, voidType,
                list(new TypeSig.ArrayOf(new TypeSig.NullableRef(new TypeSig.Named(false, object))))));
        var writeInt = new MemberRef(log, "write", new MethodSig(SigCode.METHOD_STATIC, 0, voidType, list(i32)));
        var toString = new MemberRef(object, "toString", new MethodSig(0, 0, stringType, list()));
        var constructor = new MethodDef(Flags.PUBLIC | Flags.METHOD_CONSTRUCTOR, "new", new MethodSig(0, 0, voidType, list()));
        var reset = new MethodDef(Flags.PUBLIC, "reset", new MethodSig(0, 0, i32, list(i32)));
        reset.params().add(new ParamDef(0, "value"));
        var loop = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "loop",
                new MethodSig(SigCode.METHOD_STATIC, 0, voidType, list(i32)));
        loop.params().add(new ParamDef(0, "n"));
        var hash = new MethodDef(Flags.PUBLIC, "hash", new MethodSig(0, 0, i32, list()));
        a.methods().add(constructor);
        a.methods().add(reset);
        a.methods().add(loop);
        s.methods().add(hash);
        constructor.setBody(body(new Instruction.Index(Opcode.LDARG, 0), new Instruction.Method(Opcode.CALL, reset),
                new Instruction.Plain(Opcode.RET)));
        MethodBody resetBody = body(new Instruction.Index(Opcode.LDARG, 1), new Instruction.Index(Opcode.STLOC, 0),
                new Instruction.Index(Opcode.LDLOC, 1), new Instruction.Index(Opcode.LDLOCA, 1),
                new Instruction.Index(Opcode.STARG, 1), new Instruction.Value32(Opcode.LDC_I4, Integer.MIN_VALUE),
                new Instruction.Value64(Opcode.LDC_I8, Long.MAX_VALUE), new Instruction.Value32(Opcode.LDC_R4, 0x3DCCCCCD),
                new Instruction.Value32(Opcode.LDC_R4, 0x3F800001), new Instruction.Value32(Opcode.LDC_R4, 0xFF800000),
                new Instruction.Value32(Opcode.LDC_R4, 0x7FC00001),
                new Instruction.Value64(Opcode.LDC_R8, 0x3F647AE147AE147BL),
                new Instruction.Value64(Opcode.LDC_R8, 0x8000000000000000L),
                new Instruction.Value64(Opcode.LDC_R8, 0xFFF8000000000000L), new Instruction.Literal(Opcode.LDSTR, "a\tb"),
                new Instruction.Field(Opcode.LDFLD, count), new Instruction.Field(Opcode.LDSFLD, name),
                new Instruction.Field(Opcode.LDSFLD, first), new Instruction.Field(Opcode.STSFLD, first),
                new Instruction.Type(Opcode.LDTYPE, a), new Instruction.Type(Opcode.CASTCLASS, string),
                new Instruction.Method(Opcode.CALL, write), new Instruction.Method(Opcode.CALL, writeInt),
                new Instruction.Method(Opcode.CALLVIRT, toString), new Instruction.Constrained(Opcode.CALL_CONSTRAINED, s, hash),
                new Instruction.Method(Opcode.NEWOBJ, constructor), new Instruction.Indexes(Opcode.SYSCALL, 1, 2),
                new Instruction.Plain(Opcode.RET));
        resetBody.locals().add(new TypeSig.Primitive(SigCode.I64));
        resetBody.locals().add(new TypeSig.Named(false, a));
        resetBody.locals().add(new TypeSig.Primitive(SigCode.F64));
        resetBody.localNames().add(new LocalName(0, "total"));
        resetBody.localNames().add(new LocalName(1, "self"));
        reset.setBody(resetBody);
        var top = new Label();
        var middle = new Label();
        var end = new Label();
        MethodBody loopBody = body(new Instruction.Index(Opcode.LDARG, 0), new Instruction.Branch(Opcode.BRFALSE, end),
                new Instruction.Index(Opcode.LDARG, 0), new Instruction.Switch(Opcode.SWITCH, list()),
                new Instruction.Index(Opcode.LDARG, 0), new Instruction.Switch(Opcode.SWITCH, list(top, end, middle)),
                new Instruction.Branch(Opcode.BR, top), new Instruction.Plain(Opcode.RET));
        top.mark(loopBody, 0);
        middle.mark(loopBody, 6);
        end.mark(loopBody, 7);
        loop.setBody(loopBody);
        hash.setBody(body(new Instruction.Value32(Opcode.LDC_I4, 7), new Instruction.Plain(Opcode.RET)));
        expected.requires().add(core);
        expected.requires().add(other);
        for (TypeRef ref : new TypeRef[] {string, object, names, log}) {
            expected.typeRefs().add(ref);
        }
        for (MemberRef ref : new MemberRef[] {first, write, writeInt, toString}) {
            expected.memberRefs().add(ref);
        }
        expected.types().add(a);
        expected.types().add(s);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
    }

    @Test
    public static void eachInstructionHasItsDiagnostics() {
        unassemblable(B + "foo" + E, "7:1: RFA0204: no instruction is named foo");
        unassemblable(B + "5" + E, "7:1: RFA0101: expected an instruction, a label, .locals, .override or }, found 5");
        unassemblable(B + "ldc.i4 x" + E, "7:8: RFA0101: expected a 32-bit integer, found x");
        unassemblable(B + "ldc.i4 2147483648" + E,
                "7:8: RFA0103: 2147483648 is outside the range of a 32-bit integer, from -2147483648 to 2147483647");
        unassemblable(B + "ldc.r4 1.5f" + E, "7:8: RFA0101: expected a float, found 1.5f");
        unassemblable(B + "ldc.r8 1e" + E, "7:8: RFA0101: expected a double, found 1e");
        unassemblable(B + "ldc.r4 bits -1" + E,
                "7:13: RFA0103: -1 is outside the range of the bits of a float, from 0 to 4294967295");
        unassemblable(B + "ldc.r8 bits -1" + E,
                "7:13: RFA0103: -1 is outside the range of the bits of a double, from 0 to 18446744073709551615");
        unassemblable(B + "ldstr 5" + E, "7:7: RFA0101: expected a string, found 5");
        unassemblable(B + "ldarg 4294967296" + E,
                "7:7: RFA0103: 4294967296 is outside the range of an argument or a local, from 0 to 4294967295");
        unassemblable(B + "ldarg w" + E, "7:7: RFA0207: the method has no argument named w");
        unassemblable(B + "ldloc x" + E, "7:7: RFA0207: the body has no local named x");
        unassemblable(B + "ldloc z" + E, "7:7: RFA0207: the body has more than one local named z");
        unassemblable(B + "ldloc 3" + E, "7:1: RFA0301: local 3 does not exist; the body has 3 locals (BC-04)");
        unassemblable(B + "breakpoint" + E, "7:1: RFA0301: a module never contains breakpoint (BC-04)");
        unassemblable(B + "br L" + E, "7:4: RFA0206: the body marks no label L");
        unassemblable(B + "L: nop\nL: nop" + E, "8:1: RFA0104: the label L marks two places");
        unassemblable(B + "br L\nL:" + E, "7:1: RFA0301: br targets the end of the code, after its last instruction (BC-17)");
        unassemblable(B + "switch (L M)" + E, "7:11: RFA0101: expected ), found M");
        unassemblable(B + "ldfld a.A::g" + E, "7:12: RFA0205: the module defines no field a.A::g");
        unassemblable(B + "ldfld long a.A::f" + E, "7:17: RFA0205: the module defines no field a.A::f of this type");
        unassemblable(B + "call a.A::m(long)" + E,
                "7:11: RFA0205: the module defines no method a.A::m with these parameter types");
        unassemblable(B + "call void a.A::m(int)" + E, "7:16: RFA0205: the module defines no method a.A::m with this signature");
        unassemblable(B + "ldsfld other.C::s" + E, "7:8: RFA0101: a field of another module states its type (Q69)");
        unassemblable(B + "call other.C::v()" + E, "7:6: RFA0101: a method of another module states its return type (Q69)");
        unassemblable(B + "call void other.C::v<int>()" + E, "7:21: RFA0303: the MethodSpec table arrives in v0.0.9");
        unassemblable(B + "ldtype int" + E, "7:8: RFA0303: the type needs a TypeSpec row, which arrives in v0.0.6");
        unassemblable(B + "call static void other.C::v(int...)" + E,
                "7:27: RFA0301: the last parameter of a VARIADIC signature is an array (BC-03)");
        unassemblable(B + "ldsfld !!0 other.C::f" + E, "7:8: RFA0301: MVAR 0 names no type parameter of the method (BC-03)");
        unassemblable(B + "ldsfld int x.Y::f" + E,
                "7:12: RFA0202: no required module holds the namespace x of x.Y; name its module in brackets (Q76)");
        String longest = "b".repeat(Limits.MAX_IDENTIFIER_BYTES + 1);
        unassemblable(B + "call void other.C::" + longest + "()" + E,
                "7:20: RFA0302: an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)");
    }

    private static MethodBody body(Instruction... code) {
        var body = new MethodBody(0);
        for (Instruction instruction : code) {
            body.code().add(instruction);
        }
        return body;
    }
}
