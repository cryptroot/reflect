package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.blobBytes;
import static reflect.stage0.bytecode.TestModules.blobOffset;
import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.patchByte;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class TypeSpecTableTest {
    private static final ModuleRef CORE = new ModuleRef("reflect.core", 0);
    private static final TypeRef OBJECT = new TypeRef(CORE, "reflect.core", "Object");
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);

    @Test
    public static void typeSpecsSortByTheirBlobs() throws Exception {
        byte[] file = roundTrip(specs());
        ModuleDef read = ModuleReader.read(file);
        var rows = new StringBuilder();
        for (int row = 1; row <= read.typeSpecs().size(); row++) {
            rows.append(read.typeSpecs().get(row - 1).qualifiedName()).append(' ')
                    .append(Leb128Test.hex(blobBytes(file, TableId.TYPE_SPEC, row, 0))).append("; ");
        }
        // TypeRef rows: ArrayList 1, Iterable 2, Object 3; token 0x06000003 is ULEB128 83 80 80 30.
        assertEquals("reflect.core.Int32 08; reflect.core.Int64 0A;"
                + " reflect.collections.ArrayList<reflect.core.Int32> 12 10 81 80 80 30 01 08;"
                + " reflect.collections.Iterable<reflect.core.Int32> 12 10 82 80 80 30 01 08;"
                + " reflect.core.Object? 15 10 83 80 80 30; reflect.core.Int32[] 16 08; ", rows.toString());
        assertEquals(0x07000003, column(file, TableId.TYPE_DEF, 1, 4));
        assertEquals(0x07000004, column(file, TableId.INTERFACE_IMPL, 1, 1));
        TypeDef type = read.types().get(0);
        assertTrue(type.base() == read.typeSpecs().get(2));
        assertTrue(type.interfaces().get(0) == read.typeSpecs().get(3));
        var code = type.methods().get(0).body().code();
        assertTrue(((Instruction.Type) code.get(1)).type() == read.typeSpecs().get(0));
        assertTrue(((Instruction.Type) code.get(2)).type() == read.typeSpecs().get(4));
        // The MemberRef of TypeRef row 3 comes before the one of TypeSpec row 3.
        assertEquals(0x06000003, column(file, TableId.MEMBER_REF, 1, 0));
        assertEquals(0x07000003, column(file, TableId.MEMBER_REF, 2, 0));
        assertTrue(read.memberRefs().get(1).parent() == read.typeSpecs().get(2));
    }

    @Test
    public static void aTypeSpecIsNamedAsGen12SpellsItsType() {
        var map = new TypeSig.Named(false, new TypeRef(new ModuleRef("reflect.collections", 0), "reflect.collections",
                "Map"));
        var object = new TypeSig.Named(false, OBJECT);
        assertEquals("reflect.collections.Map<reflect.core.Object?, ? extends reflect.core.Int8*>", name(
                new TypeSig.GenericInst(map, list(new TypeSig.NullableRef(object), new TypeSig.Wildcard(
                        SigCode.WILDCARD_EXTENDS, new TypeSig.PointerTo(new TypeSig.Primitive(SigCode.I8)))))));
        assertEquals("reflect.collections.Map<?, ? super !!1[]>", name(new TypeSig.GenericInst(map, list(
                new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null),
                new TypeSig.Wildcard(SigCode.WILDCARD_SUPER, new TypeSig.ArrayOf(new TypeSig.TypeVar(true, 1)))))));
        assertEquals("reflect.collections.Map<reflect.core.Boolean, reflect.core.Float64>", name(new TypeSig.GenericInst(
                map, list(new TypeSig.Primitive(SigCode.BOOLEAN), new TypeSig.Primitive(SigCode.F64)))));
        var named = new TypeDef("a", "Named", Flags.KIND_INTERFACE, 0, 0, null);
        assertEquals("(reflect.core.Object & a.Named)", name(new TypeSig.Intersection(list(object,
                new TypeSig.Named(false, named)))));
        assertEquals("ref !0", name(new TypeSig.ByRef(SigCode.BYREF_REF, new TypeSig.TypeVar(false, 0))));
        assertEquals("in reflect.core.Int32", name(new TypeSig.ByRef(SigCode.BYREF_IN, INT)));
        assertEquals("out reflect.core.Object", name(new TypeSig.ByRef(SigCode.BYREF_OUT, object)));
        assertEquals("!4294967295", name(new TypeSig.TypeVar(false, -1)));
        assertEquals("void", name(VOID));
        assertEquals("code 0xE", name(new TypeSig.Primitive(0x0E)));
    }

    @Test
    public static void theWriterRejectsInvalidTypeSpecs() {
        unwritable(with(new TypeSig.Named(false, OBJECT)), "TypeSpec reflect.core.Object: a type by name is its TypeDef"
                + " or TypeRef row, never a TypeSpec (Q99)");
        unwritable(with(new TypeSig.ByRef(SigCode.BYREF_REF, INT)), "TypeSpec ref reflect.core.Int32: BYREF stands only"
                + " as a type that a signature lists, never in a TypeSpec (Q99)");
        unwritable(with(VOID), "TypeSpec void: VOID stands outside a return type (BC-03)");
        unwritable(with(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null)),
                "TypeSpec ?: WILDCARD stands outside a type argument (BC-03)");
        unwritable(with(new TypeSig.TypeVar(false, 0)), "TypeSpec !0: VAR 0 names no type parameter of the type (BC-03)");
        unwritable(with(new TypeSig.ArrayOf(new TypeSig.Named(false, new TypeRef(CORE, "reflect.core", "Thing")))),
                "a type in a signature is reflect.core.Thing, which the module does not list");
        unwritable(with(INT, new TypeSig.Primitive(SigCode.I32)), "two TypeSpec rows have the key of reflect.core.Int32"
                + " (BC-24)");
        ModuleDef twice = with(INT);
        twice.typeSpecs().add(twice.typeSpecs().get(0));
        unwritable(twice, "the module lists the TypeSpec reflect.core.Int32 twice");
        ModuleDef field = with(INT);
        var holder = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        holder.fields().add(new FieldDef(0, "x", new TypeSig.Named(false, field.typeSpecs().get(0))));
        field.types().add(holder);
        unwritable(field, "field a.A::x: CLASS and VALUETYPE name a TypeDef or TypeRef row, never a TypeSpec (BC-03)");
        ModuleDef base = with();
        var derived = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        derived.setBase(new TypeSpec(new TypeSig.ArrayOf(INT)));
        base.types().add(derived);
        unwritable(base, "the base type of a.A is reflect.core.Int32[], which the module does not list");
        ModuleDef parent = with();
        parent.memberRefs().add(new MemberRef(new TypeSpec(new TypeSig.ArrayOf(INT)), "length", new FieldSig(INT)));
        unwritable(parent, "MemberRef reflect.core.Int32[]::length has a parent that the module does not list");
    }

    @Test
    public static void theReaderRejectsInvalidTypeSpecRows() throws Exception {
        byte[] file = ModuleWriter.write(specs());
        int spec = TableId.TYPE_SPEC;
        // Rows 1 and 2 hold int, 08, and long, 0A; 07 is char.
        rejected(patchByte(file, blobOffset(file, spec, 2, 0), SigCode.CHAR), columnOffset(file, spec, 2, 0),
                "the TypeSpec rows are not sorted by signature (BC-24)");
        rejected(patch(file, spec, 2, 0, column(file, spec, 1, 0)), columnOffset(file, spec, 2, 0),
                "two TypeSpec rows have equal keys (BC-24)");
        int first = blobOffset(file, spec, 1, 0);
        rejected(patchByte(file, first, SigCode.VOID), first, "VOID stands outside a return type (BC-03)");
        // Row 6 holds int[], 16 08, and 08 08 holds two types.
        int last = blobOffset(file, spec, 6, 0);
        rejected(patchByte(file, last, SigCode.I32), last + 1, "the signature ends before its blob does (BC-03)");
        // The local of f is reflect.core.Object, TypeRef row 3, which a signature cannot name by TypeSpec row 1.
        int local = indexOf(file, sectionOffset(file, SectionId.BLOBS), 0x22, 0x01, 0x10, 0x83, 0x80, 0x80, 0x30) + 3;
        rejected(patchByte(patchByte(file, local, 0x81), local + 3, 0x38), local,
                "token 0x7000001 does not name a TypeDef or TypeRef row (BC-03)");
        // newarr names TypeSpec row 1 of 6.
        int newarr = indexOf(file, sectionOffset(file, SectionId.CODE), Opcode.NEWARR, 0x81, 0x80, 0x80, 0x38) + 1;
        rejected(patchByte(file, newarr, 0x87), newarr,
                "token 0x7000007 does not name a TypeDef, TypeRef or TypeSpec row (BC-04)");
        rejected(patchByte(file, newarr, 0x80), newarr,
                "token 0x7000000 does not name a TypeDef, TypeRef or TypeSpec row (BC-04)");
    }

    // a.Ints extends reflect.collections.ArrayList<int> and implements reflect.collections.Iterable<int>, and its
    // static f() makes an int[] and casts it to reflect.core.Object?. The TypeSpec and MemberRef rows come out of
    // order, and nothing names long and int[].
    static ModuleDef specs() {
        var module = new ModuleDef("a", 0, 0, 0);
        var collections = new ModuleRef("reflect.collections", 0);
        module.requires().add(CORE);
        module.requires().add(collections);
        var arrayList = new TypeRef(collections, "reflect.collections", "ArrayList");
        var iterable = new TypeRef(collections, "reflect.collections", "Iterable");
        module.typeRefs().add(OBJECT);
        module.typeRefs().add(iterable);
        module.typeRefs().add(arrayList);
        var ints = new TypeSpec(new TypeSig.GenericInst(new TypeSig.Named(false, arrayList), list(INT)));
        var iterableInts = new TypeSpec(new TypeSig.GenericInst(new TypeSig.Named(false, iterable), list(INT)));
        var nullable = new TypeSpec(new TypeSig.NullableRef(new TypeSig.Named(false, OBJECT)));
        var integer = new TypeSpec(INT);
        for (TypeSpec spec : new TypeSpec[] {new TypeSpec(new TypeSig.ArrayOf(INT)), nullable, iterableInts,
            new TypeSpec(new TypeSig.Primitive(SigCode.I64)), integer, ints}) {
            module.typeSpecs().add(spec);
        }
        var type = new TypeDef("a", "Ints", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        type.setBase(ints);
        type.interfaces().add(iterableInts);
        var f = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "f", new MethodSig(SigCode.METHOD_STATIC, 0, VOID,
                list()));
        var body = new MethodBody(1);
        body.locals().add(new TypeSig.Named(false, OBJECT));
        body.code().add(new Instruction.Value32(Opcode.LDC_I4, 1));
        body.code().add(new Instruction.Type(Opcode.NEWARR, integer));
        body.code().add(new Instruction.Type(Opcode.CASTCLASS, nullable));
        body.code().add(new Instruction.Index(Opcode.STLOC, 0));
        body.code().add(new Instruction.Plain(Opcode.RET));
        f.setBody(body);
        type.methods().add(f);
        module.types().add(type);
        module.memberRefs().add(new MemberRef(ints, "add", new MethodSig(0, 0, new TypeSig.Primitive(SigCode.BOOLEAN),
                list(new TypeSig.TypeVar(false, 0)))));
        module.memberRefs().add(new MemberRef(OBJECT, "hashCode", new MethodSig(0, 0, INT, list())));
        return module;
    }

    // Module a, which requires reflect.core, lists reflect.core.Object, and has one TypeSpec row per type.
    private static ModuleDef with(TypeSig... types) {
        var module = new ModuleDef("a", 0, 0, 0);
        module.requires().add(CORE);
        module.typeRefs().add(OBJECT);
        for (TypeSig type : types) {
            module.typeSpecs().add(new TypeSpec(type));
        }
        return module;
    }

    private static String name(TypeSig type) {
        return new TypeSpec(type).qualifiedName();
    }

    // The offset of the first run of these bytes in the file from an offset.
    private static int indexOf(byte[] file, int from, int... bytes) {
        for (int i = from; i + bytes.length <= file.length; i++) {
            boolean found = true;
            for (int j = 0; j < bytes.length; j++) {
                found &= (file[i + j] & 0xFF) == bytes[j];
            }
            if (found) {
                return i;
            }
        }
        throw new AssertionError("the file does not hold the bytes");
    }
}
