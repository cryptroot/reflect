package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.blobOffset;
import static reflect.stage0.bytecode.TestModules.bytes;
import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.idents;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class MemberTableTest {
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);
    private static final MethodSig INSTANCE_VOID = new MethodSig(0, 0, VOID, list());
    private static final MethodSig INSTANCE_INT_TO_VOID = new MethodSig(0, 0, VOID, list(INT));
    private static final MethodSig STATIC_VOID = new MethodSig(SigCode.METHOD_STATIC, 0, VOID, list());
    private static final MethodSig STATIC_INT_TO_VOID = new MethodSig(SigCode.METHOD_STATIC, 0, VOID, list(INT));

    @Test
    public static void fieldsMethodsAndParamsFollowTheRunsOfTheirOwners() throws Exception {
        byte[] file = roundTrip(members());
        // a.A has fields 1 and 2 and method 1; a.B has none; a.C has field 3 and methods 2 and 3.
        assertEquals("1 3 3 / 1 2 2", columns(file, TableId.TYPE_DEF, 3, 7) + " / " + columns(file, TableId.TYPE_DEF, 3, 8));
        // A::m has params 1 and 2, C::new none, and C::run param 3.
        assertEquals("1 3 3", columns(file, TableId.METHOD, 3, 4));
        ModuleDef read = ModuleReader.read(file);
        TypeDef a = read.types().get(0);
        TypeDef c = read.types().get(2);
        assertEquals("x y", a.fields().get(0).name() + " " + a.fields().get(1).name());
        assertEquals(Flags.PRIVATE | Flags.FIELD_STATIC | Flags.FIELD_FINAL, a.fields().get(1).flags());
        assertEquals(0, read.types().get(1).fields().size() + read.types().get(1).methods().size());
        assertEquals("p q", a.methods().get(0).params().get(0).name() + " " + a.methods().get(0).params().get(1).name());
        assertEquals(Flags.PARAM_SCOPED, a.methods().get(0).params().get(1).flags());
        assertEquals("new run", c.methods().get(0).name() + " " + c.methods().get(1).name());
        assertEquals("", c.methods().get(1).params().get(0).name());
        assertEquals(STATIC_INT_TO_VOID, c.methods().get(1).signature());
        assertEquals(INT, c.fields().get(0).type());
        assertEquals("21 01 01 01 08", Leb128Test.hex(TestModules.blobBytes(file, TableId.METHOD, 3, 2)));
        // One BLOBS entry serves both fields of type int.
        assertEquals(column(file, TableId.FIELD, 1, 2), column(file, TableId.FIELD, 3, 2));
    }

    @Test
    public static void constructorsAndStaticInitializersHaveTheirNames() throws Exception {
        roundTrip(withMethod(new MethodDef(Flags.METHOD_CONSTRUCTOR | Flags.METHOD_NATIVE, "new", INSTANCE_VOID)));
        roundTrip(withMethod(new MethodDef(Flags.METHOD_CONSTRUCTOR | Flags.METHOD_STATIC | Flags.METHOD_NATIVE,
                "static", STATIC_VOID)));
        unwritable(withMethod(new MethodDef(Flags.METHOD_NATIVE, "new", INSTANCE_VOID)), "method a.A::new: only"
                + " constructors and static initializers are named new and static (BC-11)");
        unwritable(withMethod(new MethodDef(Flags.METHOD_CONSTRUCTOR, "init", INSTANCE_VOID)), "method a.A::init: a"
                + " constructor is named new and a static initializer static, so this one is new (BC-11)");
        unwritable(withMethod(new MethodDef(Flags.METHOD_CONSTRUCTOR | Flags.METHOD_STATIC, "new", STATIC_VOID)),
                "method a.A::new: a constructor is named new and a static initializer static, so this one is static (BC-11)");
    }

    @Test
    public static void theWriterRejectsInvalidMembers() {
        unwritable(withField(new FieldDef(0x80, "x", INT)), "field a.A::x has the undefined flags 0x80 (BC-20)");
        unwritable(withField(new FieldDef(Flags.PUBLIC | Flags.PRIVATE, "x", INT)),
                "field a.A::x has more than one visibility flag (BC-11)");
        unwritable(withField(new FieldDef(Flags.FIELD_CONST, "x", INT)),
                "field a.A::x is const but not static; a const field is also STATIC (BC-11)");
        unwritable(withMethod(new MethodDef(0x8000 | Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID)),
                "method a.A::m has the undefined flags 0x8000 (BC-20)");
        var param = new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_INT_TO_VOID);
        param.params().add(new ParamDef(0x04, "p"));
        unwritable(withMethod(param), "parameter p of method a.A::m has the undefined flags 0x4 (BC-20)");
        var shared = new FieldDef(0, "x", INT);
        ModuleDef twoTypes = withField(shared);
        var other = new TypeDef("a", "B", Flags.KIND_CLASS, 0, 0, null);
        other.fields().add(shared);
        twoTypes.types().add(other);
        unwritable(twoTypes, "field a.B::x belongs to two types or appears twice");
        var method = new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID);
        ModuleDef twice = withMethod(method);
        twice.types().get(0).methods().add(method);
        unwritable(twice, "method a.A::m belongs to two types or appears twice");
        var many = new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID);
        for (int i = 0; i <= Limits.MAX_PARAMS; i++) {
            many.params().add(new ParamDef(0, ""));
        }
        unwritable(withMethod(many), "method a.A::m has 65536 parameters, more than the limit of 65535 (BC-21)");
        unwritable(withMethod(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID)),
                "method a.A::m: the signature has STATIC exactly when the method has (BC-11)");
        unwritable(withMethod(new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_INT_TO_VOID)),
                "method a.A::m: the signature has 1 parameters, but the method has 0 Param rows (BC-11)");
        var generic = new MethodSig(SigCode.METHOD_GENERIC, 1, VOID, list(new TypeSig.TypeVar(true, 0)));
        var genericMethod = new MethodDef(Flags.METHOD_ABSTRACT, "m", generic);
        genericMethod.params().add(new ParamDef(0, "t"));
        unwritable(withMethod(genericMethod), "method a.A::m: the signature is GENERIC, but the method has no"
                + " GenericParam rows (BC-24)");
    }

    @Test
    public static void aTypeHasOneMemberPerNameAndSignature() throws Exception {
        ModuleDef fields = withField(new FieldDef(0, "x", INT));
        fields.types().get(0).fields().add(new FieldDef(Flags.FIELD_STATIC, "x", INT));
        unwritable(fields, "type a.A has two fields x of one type (BC-11)");
        ModuleDef methods = withMethod(new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID));
        methods.types().get(0).methods().add(new MethodDef(Flags.METHOD_NATIVE, "m", INSTANCE_VOID));
        unwritable(methods, "type a.A has two methods m with one signature (BC-11)");
        // Overloads, and fields of one name with other types, are valid, as in CIL.
        TypeDef type = typeWith("x", "w", "m", "n");
        type.fields().add(new FieldDef(0, "x", new TypeSig.Primitive(SigCode.I64)));
        type.methods().add(new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "m", STATIC_VOID));
        var m = new MethodDef(Flags.METHOD_NATIVE, "m", INSTANCE_INT_TO_VOID);
        m.params().add(new ParamDef(0, "p"));
        type.methods().add(m);
        roundTrip(module(type));
        // The second member gets the name of the first; its own name, the last IDENTS entry, is then unused.
        byte[] twoFields = roundTrip(module(typeWith("x", "w", null, null)));
        rejected(patch(twoFields, TableId.FIELD, 2, 1, column(twoFields, TableId.FIELD, 1, 1)),
                columnOffset(twoFields, TableId.FIELD, 2, 0), "type a.A has two fields x of one type (BC-11)");
        byte[] twoMethods = roundTrip(module(typeWith(null, null, "m", "n")));
        rejected(patch(twoMethods, TableId.METHOD, 2, 1, column(twoMethods, TableId.METHOD, 1, 1)),
                columnOffset(twoMethods, TableId.METHOD, 2, 0), "type a.A has two methods m with one signature (BC-11)");
        String a = ".module mod 0.0.0\n.class a.A {\n";
        TestModules.assemble(a + ".field int x\n.field long x\n.method static void m() {\nret\n}\n"
                + ".method void m() {\nret\n}\n.method static void m(int) {\nret\n}\n}\n");
        TestModules.unassemblable(a + ".field int x\n.field static int x\n}",
                "4:19: RFA0301: type a.A has two fields x of one type (BC-11)");
        TestModules.unassemblable(a + ".method static void m() {\nret\n}\n.method static void m() {\nret\n}\n}",
                "6:21: RFA0301: type a.A has two methods m with one signature (BC-11)");
    }

    // a.A with an int field of each of the first two names, and an abstract void method of each of the
    // other two, where the name is not null.
    private static TypeDef typeWith(String first, String second, String third, String fourth) {
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        for (String name : new String[] {first, second}) {
            if (name != null) {
                type.fields().add(new FieldDef(0, name, INT));
            }
        }
        for (String name : new String[] {third, fourth}) {
            if (name != null) {
                type.methods().add(new MethodDef(Flags.METHOD_ABSTRACT, name, INSTANCE_VOID));
            }
        }
        return type;
    }

    private static ModuleDef module(TypeDef type) {
        var module = new ModuleDef("mod", 0, 0, 0);
        module.types().add(type);
        return module;
    }

    @Test
    public static void theReaderRejectsInvalidMemberColumns() throws Exception {
        byte[] file = ModuleWriter.write(members());
        reject(file, TableId.FIELD, 1, 0, 0x80, "Field flags 0x80 are not defined (BC-20)");
        reject(file, TableId.FIELD, 1, 0, Flags.FIELD_CONST, "a const field is also STATIC (BC-11)");
        reject(file, TableId.METHOD, 1, 0, 0x8000, "Method flags 0x8000 are not defined (BC-20)");
        // Method 2 is C::new; without CONSTRUCTOR its name is wrong.
        rejected(patch(file, TableId.METHOD, 2, 0, Flags.METHOD_NATIVE), columnOffset(file, TableId.METHOD, 2, 1),
                "only constructors and static initializers are named new and static (BC-11)");
        reject(file, TableId.METHOD, 1, 3, 8, "an ABSTRACT, NATIVE or EXTERN method has no body, so its code is 0 (BC-11)");
        reject(file, TableId.PARAM, 1, 0, 0x04, "Param flags 0x4 are not defined (BC-20)");
        rejected(patch(file, TableId.METHOD, 1, 0, Flags.PUBLIC | Flags.METHOD_ABSTRACT | Flags.METHOD_STATIC),
                blobOffset(file, TableId.METHOD, 1, 2), "the signature has STATIC exactly when the method has (BC-11)");
        // C::new starting its run at row 2 leaves A::m one Param row for two parameters.
        rejected(patch(file, TableId.METHOD, 2, 4, 2), columnOffset(file, TableId.METHOD, 1, 4),
                "the signature has 2 parameters, but the method has 1 Param rows (BC-11)");
    }

    @Test
    public static void theReaderChecksTheRunsOfRows() throws Exception {
        byte[] file = ModuleWriter.write(members());
        int def = TableId.TYPE_DEF;
        reject(file, def, 1, 7, 2, "the first run of Field rows starts at row 2, not at row 1 (BC-11)");
        rejected(patch(file, def, 2, 7, 4), columnOffset(file, def, 3, 7),
                "the run of Field rows starts at row 3, outside rows 4 to 4 (BC-11)");
        reject(file, def, 3, 7, 5, "the run of Field rows starts at row 5, outside rows 3 to 4 (BC-11)");
        reject(file, def, 2, 8, 0, "the run of Method rows starts at row 0, outside rows 1 to 4 (BC-11)");
        reject(file, TableId.METHOD, 1, 4, 2, "the first run of Param rows starts at row 2, not at row 1 (BC-11)");
        reject(file, TableId.METHOD, 1, 5, 2, "the first run of GenericParam rows starts at row 2, not at row 1 (BC-11)");
        byte[] orphans = TestModules.file(idents("m", "f"), new TestModules.Tables().rows(TableId.MODULE, 1, 0, 0, 0, 0, 0)
                .rows(TableId.FIELD, 0, 3, 1).bytes(), new Section(SectionId.BLOBS, bytes(0, 2, SigCode.FIELD, SigCode.I32)));
        rejected(orphans, columnOffset(orphans, TableId.FIELD, 1, 0), "Field rows belong to no owner (BC-11)");
    }

    // a.A with fields x, y and method m(p, scoped q); a.B with none; a.C with field z and methods
    // new() and run(unnamed). Every method is native or abstract, since bodies come later.
    private static ModuleDef members() {
        var module = new ModuleDef("m", 0, 0, 0);
        var a = new TypeDef("a", "A", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var b = new TypeDef("a", "B", Flags.KIND_CLASS, 0, 0, null);
        var c = new TypeDef("a", "C", Flags.KIND_CLASS, 0, 0, null);
        a.fields().add(new FieldDef(Flags.PUBLIC, "x", INT));
        a.fields().add(new FieldDef(Flags.PRIVATE | Flags.FIELD_STATIC | Flags.FIELD_FINAL, "y",
                new TypeSig.Primitive(SigCode.F64)));
        var m = new MethodDef(Flags.PUBLIC | Flags.METHOD_ABSTRACT | Flags.METHOD_VIRTUAL, "m",
                new MethodSig(0, 0, VOID, list(INT, new TypeSig.Primitive(SigCode.I64))));
        m.params().add(new ParamDef(0, "p"));
        m.params().add(new ParamDef(Flags.PARAM_SCOPED, "q"));
        a.methods().add(m);
        c.fields().add(new FieldDef(0, "z", INT));
        c.methods().add(new MethodDef(Flags.METHOD_CONSTRUCTOR | Flags.METHOD_NATIVE, "new", INSTANCE_VOID));
        var run = new MethodDef(Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "run", STATIC_INT_TO_VOID);
        run.params().add(new ParamDef(0, ""));
        c.methods().add(run);
        module.types().add(c);
        module.types().add(a);
        module.types().add(b);
        return module;
    }

    private static ModuleDef withField(FieldDef field) {
        var module = new ModuleDef("m", 0, 0, 0);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        type.fields().add(field);
        module.types().add(type);
        return module;
    }

    private static ModuleDef withMethod(MethodDef method) {
        var module = new ModuleDef("m", 0, 0, 0);
        var type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        type.methods().add(method);
        module.types().add(type);
        return module;
    }

    private static String columns(byte[] file, int table, int rows, int column) throws ModuleFormatException {
        var values = new StringBuilder();
        for (int row = 1; row <= rows; row++) {
            values.append(row == 1 ? "" : " ").append(column(file, table, row, column));
        }
        return values.toString();
    }

    private static void reject(byte[] file, int table, int row, int column, int value, String message) throws Exception {
        rejected(patch(file, table, row, column, value), columnOffset(file, table, row, column), message);
    }
}
