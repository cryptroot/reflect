package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.bytes;
import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.idents;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class MemberTableTest {
    private static final Blob INT_FIELD = new Blob(bytes(SigCode.FIELD, SigCode.I32));
    private static final Blob INSTANCE_VOID = new Blob(bytes(SigCode.METHOD, 0, 0, SigCode.VOID));
    private static final Blob STATIC_VOID = new Blob(bytes(SigCode.METHOD, SigCode.METHOD_STATIC, 0, SigCode.VOID));
    private static final Blob STATIC_INT_TO_VOID = new Blob(bytes(SigCode.METHOD, SigCode.METHOD_STATIC, 1, SigCode.VOID,
            SigCode.I32));

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
        assertEquals(Leb128Test.hex(STATIC_INT_TO_VOID.toByteArray()),
                Leb128Test.hex(c.methods().get(1).signature().toByteArray()));
        assertEquals(Leb128Test.hex(INT_FIELD.toByteArray()), Leb128Test.hex(c.fields().get(0).signature().toByteArray()));
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
        unwritable(withField(new FieldDef(0x80, "x", INT_FIELD)), "field a.A::x has the undefined flags 0x80 (BC-20)");
        unwritable(withField(new FieldDef(Flags.PUBLIC | Flags.PRIVATE, "x", INT_FIELD)),
                "field a.A::x has more than one visibility flag (BC-11)");
        unwritable(withField(new FieldDef(Flags.FIELD_CONST, "x", INT_FIELD)),
                "field a.A::x is const but not static; a const field is also STATIC (BC-11)");
        unwritable(withMethod(new MethodDef(0x8000 | Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID)),
                "method a.A::m has the undefined flags 0x8000 (BC-20)");
        var param = new MethodDef(Flags.METHOD_ABSTRACT, "m", INSTANCE_VOID);
        param.params().add(new ParamDef(0x04, "p"));
        unwritable(withMethod(param), "parameter p of method a.A::m has the undefined flags 0x4 (BC-20)");
        var shared = new FieldDef(0, "x", INT_FIELD);
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
        reject(file, TableId.METHOD, 1, 3, 8, "this reader does not support method bodies yet");
        reject(file, TableId.PARAM, 1, 0, 0x04, "Param flags 0x4 are not defined (BC-20)");
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
        a.fields().add(new FieldDef(Flags.PUBLIC, "x", INT_FIELD));
        a.fields().add(new FieldDef(Flags.PRIVATE | Flags.FIELD_STATIC | Flags.FIELD_FINAL, "y",
                new Blob(bytes(SigCode.FIELD, SigCode.F64))));
        var m = new MethodDef(Flags.PUBLIC | Flags.METHOD_ABSTRACT | Flags.METHOD_VIRTUAL, "m",
                new Blob(bytes(SigCode.METHOD, 0, 2, SigCode.VOID, SigCode.I32, SigCode.I64)));
        m.params().add(new ParamDef(0, "p"));
        m.params().add(new ParamDef(Flags.PARAM_SCOPED, "q"));
        a.methods().add(m);
        c.fields().add(new FieldDef(0, "z", INT_FIELD));
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
