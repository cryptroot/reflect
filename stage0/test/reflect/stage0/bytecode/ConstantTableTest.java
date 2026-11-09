package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.blobBytes;
import static reflect.stage0.bytecode.TestModules.blobOffset;
import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.patchByte;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class ConstantTableTest {
    private static final int CONST = Flags.PUBLIC | Flags.FIELD_STATIC | Flags.FIELD_CONST;
    private static final ModuleRef CORE = new ModuleRef("reflect.core", 0);
    private static final TypeRef OBJECT = new TypeRef(CORE, "reflect.core", "Object");
    private static final TypeRef STRING = new TypeRef(CORE, "reflect.core", "String");
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);

    @Test
    public static void constantsHoldTheValuesOfTheirTypes() throws Exception {
        byte[] file = roundTrip(constants());
        TypeDef read = ModuleReader.read(file).types().get(0);
        TypeDef written = constants().types().get(0);
        var rows = new StringBuilder();
        for (int row = 1; row <= 13; row++) {
            FieldDef field = read.fields().get(column(file, TableId.CONSTANT, row, 0) - 1);
            assertEquals(written.fields().get(row - 1).constant(), field.constant());
            rows.append(field.name()).append(' ').append(Leb128Test.hex(blobBytes(file, TableId.CONSTANT, row, 1)))
                    .append("; ");
        }
        // The literal of text is at LITERALS offset 1, so its blob is that of z, which BLOBS holds once.
        assertEquals("z 01; b 80 7F; ub FF 01; s FF FF 01; us FF FF 03; c BA 4C; i 80 80 80 80 78; ui FF FF FF FF 0F;"
                + " l 80 80 80 80 80 80 80 80 80 7F; ul FF FF FF FF FF FF FF FF FF 01; f 00 00 C0 BF;"
                + " d 00 00 00 00 00 00 00 80; text 01; ", rows.toString());
        assertEquals(null, read.fields().get(13).constant());
        // The literal of a Constant comes before those of the bodies (BC-24).
        assertEquals("00 01 62 00 01 61 00", Leb128Test.hex(section(file, SectionId.LITERALS)));
        // The module reflect.core holds constants of its own String.
        var core = new ModuleDef("reflect.core", 0, 0, 0);
        var string = new TypeDef("reflect.core", "String", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        string.fields().add(field("EMPTY", new TypeSig.Named(false, string), new Constant.Text("")));
        core.types().add(string);
        assertEquals(new Constant.Text(""), ModuleReader.read(roundTrip(core)).types().get(0).fields().get(0).constant());
    }

    @Test
    public static void theWriterRejectsInvalidConstants() {
        unwritable(with(field("x", INT, null)), "field a.K::x is const, so it has a Constant row (Q98)");
        var notConst = new FieldDef(Flags.FIELD_STATIC, "x", INT);
        notConst.setConstant(new Constant.Bits(1));
        unwritable(with(notConst), "field a.K::x has a Constant row, but is not const (Q98)");
        String types = "a Constant holds a primitive or a reflect.core.String until later stages, not ";
        unwritable(with(field("x", new TypeSig.Named(false, OBJECT), new Constant.Bits(0))),
                "field a.K::x: " + types + "reflect.core.Object (Q98)");
        unwritable(with(field("x", new TypeSig.NullableRef(new TypeSig.Named(false, STRING)), new Constant.Text("x"))),
                "field a.K::x: " + types + "reflect.core.String? (Q98)");
        unwritable(with(field("x", new TypeSig.ArrayOf(INT), new Constant.Bits(0))),
                "field a.K::x: " + types + "reflect.core.Int32[] (Q98)");
        unwritable(with(field("x", new TypeSig.Named(true, STRING), new Constant.Text("x"))),
                "field a.K::x: " + types + "reflect.core.String (Q98)");
        var other = new ModuleRef("other", 0);
        ModuleDef elsewhere = with(field("x", new TypeSig.Named(false, new TypeRef(other, "reflect.core", "String")),
                new Constant.Text("x")));
        elsewhere.requires().add(other);
        unwritable(elsewhere, "field a.K::x: " + types + "reflect.core.String (Q98)");
        var ownString = new TypeDef("reflect.core", "String", Flags.KIND_CLASS, 0, 0, null);
        ModuleDef own = with(field("x", new TypeSig.Named(false, ownString), new Constant.Text("x")));
        own.types().add(ownString);
        unwritable(own, "field a.K::x: " + types + "reflect.core.String (Q98)");
        unwritable(with(field("x", INT, new Constant.Text("1"))),
                "field a.K::x: the Constant of a reflect.core.Int32 is a number (Q98)");
        unwritable(with(field("x", new TypeSig.Named(false, STRING), new Constant.Bits(1))),
                "field a.K::x: the Constant of a reflect.core.String is a string (Q98)");
        unfit(SigCode.BOOLEAN, 2, "Boolean");
        unfit(SigCode.I8, 128, "Int8");
        unfit(SigCode.I8, -129, "Int8");
        unfit(SigCode.U8, 256, "UInt8");
        unfit(SigCode.U8, -1, "UInt8");
        unfit(SigCode.I16, 32_768, "Int16");
        unfit(SigCode.U16, 65_536, "UInt16");
        unfit(SigCode.CHAR, -1, "Char");
        unfit(SigCode.I32, 1L << 31, "Int32");
        unfit(SigCode.U32, 1L << 32, "UInt32");
        unfit(SigCode.F32, -1, "Float32");
    }

    @Test
    public static void theReaderRejectsInvalidConstantRows() throws Exception {
        byte[] file = ModuleWriter.write(constants());
        int constant = TableId.CONSTANT;
        rejected(patch(file, constant, 2, 0, 1), columnOffset(file, constant, 2, 0), "two Constant rows have equal keys (BC-24)");
        // Row 1 then names ub, whose value 1 fits, and row 2 names b, before it.
        rejected(patch(file, constant, 1, 0, 3), columnOffset(file, constant, 2, 0),
                "the Constant rows are not sorted by field (BC-24)");
        rejected(patch(file, constant, 13, 0, 14), columnOffset(file, constant, 13, 0),
                "field a.K::n has a Constant row, but is not const (Q98)");
        rejected(patch(file, TableId.FIELD, 14, 0, CONST), columnOffset(file, TableId.FIELD, 14, 0),
                "field a.K::n is const, so it has a Constant row (Q98)");
        rejected(patch(file, constant, 1, 0, 0), columnOffset(file, constant, 1, 0), "row 0 of table 0x8 does not exist");
        int z = blobOffset(file, constant, 1, 1);
        rejected(patchByte(file, z, 2), z, "field a.K::z: the Constant does not fit reflect.core.Boolean (Q98)");
        int ub = blobOffset(file, constant, 3, 1);
        rejected(patchByte(file, ub + 1, 3), ub, "field a.K::ub: the Constant does not fit reflect.core.UInt8 (Q98)");
        // The FIELD signature of z, 20 02, holds a byte more than a boolean.
        rejected(patch(file, constant, 1, 1, column(file, TableId.FIELD, 1, 2)), blobOffset(file, TableId.FIELD, 1, 2) + 1,
                "the Constant ends before its blob does (Q98)");
        // The type of text, reflect.core.String, TypeRef row 2, becomes reflect.core.Object, row 1.
        int text = blobOffset(file, TableId.FIELD, 13, 2);
        rejected(patchByte(file, text + 2, 0x81), blobOffset(file, constant, 13, 1), "a Constant holds a primitive or a"
                + " reflect.core.String until later stages, not reflect.core.Object (Q98)");
    }

    // a.K holds a const field per type that a Constant holds, each at an end of its range, the field n, which is
    // not const, and the method f, which loads the literals a and b.
    static ModuleDef constants() {
        var module = new ModuleDef("a", 0, 0, 0);
        module.requires().add(CORE);
        module.typeRefs().add(STRING);
        module.typeRefs().add(OBJECT);
        var type = new TypeDef("a", "K", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        type.fields().add(field("z", primitive(SigCode.BOOLEAN), new Constant.Bits(1)));
        type.fields().add(field("b", primitive(SigCode.I8), new Constant.Bits(-128)));
        type.fields().add(field("ub", primitive(SigCode.U8), new Constant.Bits(255)));
        type.fields().add(field("s", primitive(SigCode.I16), new Constant.Bits(32_767)));
        type.fields().add(field("us", primitive(SigCode.U16), new Constant.Bits(65_535)));
        type.fields().add(field("c", primitive(SigCode.CHAR), new Constant.Bits(0x263A)));
        type.fields().add(field("i", INT, new Constant.Bits(Integer.MIN_VALUE)));
        type.fields().add(field("ui", primitive(SigCode.U32), new Constant.Bits(0xFFFF_FFFFL)));
        type.fields().add(field("l", primitive(SigCode.I64), new Constant.Bits(Long.MIN_VALUE)));
        type.fields().add(field("ul", primitive(SigCode.U64), new Constant.Bits(-1)));
        type.fields().add(field("f", primitive(SigCode.F32), new Constant.Bits(Float.floatToRawIntBits(-1.5f)
                & 0xFFFF_FFFFL)));
        type.fields().add(field("d", primitive(SigCode.F64), new Constant.Bits(Double.doubleToRawLongBits(-0.0))));
        type.fields().add(field("text", new TypeSig.Named(false, STRING), new Constant.Text("b")));
        type.fields().add(new FieldDef(Flags.PUBLIC | Flags.FIELD_STATIC, "n", INT));
        var f = new MethodDef(Flags.METHOD_STATIC, "f", new MethodSig(SigCode.METHOD_STATIC, 0,
                primitive(SigCode.VOID), TestModules.list()));
        var body = new MethodBody(2);
        body.code().add(new Instruction.Literal(Opcode.LDSTR, "a"));
        body.code().add(new Instruction.Literal(Opcode.LDSTR, "b"));
        body.code().add(new Instruction.Plain(Opcode.POP));
        body.code().add(new Instruction.Plain(Opcode.POP));
        body.code().add(new Instruction.Plain(Opcode.RET));
        f.setBody(body);
        type.methods().add(f);
        module.types().add(type);
        return module;
    }

    // Module a, which requires reflect.core and lists its Object and String, with the type a.K of this field.
    private static ModuleDef with(FieldDef field) {
        var module = new ModuleDef("a", 0, 0, 0);
        module.requires().add(CORE);
        module.typeRefs().add(OBJECT);
        module.typeRefs().add(STRING);
        var type = new TypeDef("a", "K", Flags.KIND_CLASS, 0, 0, null);
        type.fields().add(field);
        module.types().add(type);
        return module;
    }

    private static void unfit(int code, long bits, String struct) {
        unwritable(with(field("x", primitive(code), new Constant.Bits(bits))), "field a.K::x: the Constant does not fit"
                + " reflect.core." + struct + " (Q98)");
    }

    // A public static const field with the value.
    private static FieldDef field(String name, TypeSig type, Constant value) {
        var field = new FieldDef(CONST, name, type);
        field.setConstant(value);
        return field;
    }

    private static TypeSig primitive(int code) {
        return new TypeSig.Primitive(code);
    }
}
