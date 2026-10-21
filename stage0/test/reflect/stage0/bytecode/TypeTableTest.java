package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class TypeTableTest {
    @Test
    public static void typeDefsSortByNamespaceNameAndEnclosingType() throws Exception {
        byte[] file = roundTrip(types());
        ModuleDef read = ModuleReader.read(file);
        assertEquals("G a.A/Tag/Inner a.A/Tag a.Z/Tag a.A a.Z b.B", names(read));
        // Inner is in row 2 and its enclosing type in row 3; the two Tags follow the rows of a.A and a.Z.
        assertEquals("0 3 5 6 0 0 0", enclosingColumns(file, 7));
        TypeDef tag = read.types().get(2);
        assertEquals(Flags.KIND_ENUM, tag.kind());
        assertEquals(SigCode.I32, tag.backing());
        assertTrue(tag.enclosing() == read.types().get(4));
        assertTrue(read.types().get(1).enclosing() == tag);
    }

    @Test
    public static void typeRefsSortByScopeNamespaceAndName() throws Exception {
        byte[] file = roundTrip(typeRefs());
        ModuleDef read = ModuleReader.read(file);
        var order = new StringBuilder();
        for (TypeRef ref : read.typeRefs()) {
            order.append(ref.qualifiedName()).append(' ');
        }
        assertEquals("reflect.core.Object reflect.core.Outer reflect.core.String X reflect.io.Console"
                + " reflect.core.Outer/Entry ", order.toString());
        var scopes = new StringBuilder();
        for (int row = 1; row <= 6; row++) {
            scopes.append(Integer.toHexString(column(file, TableId.TYPE_REF, row, 0))).append(' ');
        }
        assertEquals("2000001 2000001 2000001 2000002 2000002 6000002 ", scopes.toString());
        assertTrue(read.typeRefs().get(5).scope() == read.typeRefs().get(1));
        assertTrue(read.typeRefs().get(0).scope() == read.requires().get(0));
    }

    @Test
    public static void baseTypesTheMainClassAndEnumsRoundTrip() throws Exception {
        var module = new ModuleDef("app", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        module.requires().add(core);
        var object = new TypeRef(core, "reflect.core", "Object");
        module.typeRefs().add(object);
        var app = new TypeDef("app", "App", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_FINAL, 0, null);
        var shape = new TypeDef("app", "Shape", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_ABSTRACT, 0, null);
        var circle = new TypeDef("app", "Circle", Flags.KIND_RECORD, Flags.PUBLIC, 0, null);
        var level = new TypeDef("app", "Level", Flags.KIND_ENUM, 0, SigCode.U8, null);
        app.setBase(object);
        shape.setBase(object);
        circle.setBase(shape);
        module.types().add(app);
        module.types().add(shape);
        module.types().add(circle);
        module.types().add(level);
        module.setMain(app);
        byte[] file = roundTrip(module);
        ModuleDef read = ModuleReader.read(file);
        assertEquals("app.App app.Circle app.Level app.Shape", names(read));
        assertTrue(read.main() == read.types().get(0));
        assertEquals(1, column(file, TableId.MODULE, 1, 5));
        assertEquals(0x06000001, column(file, TableId.TYPE_DEF, 1, 4));
        assertEquals(0x05000004, column(file, TableId.TYPE_DEF, 2, 4));
        assertTrue(read.types().get(1).base() == read.types().get(3));
        assertTrue(read.types().get(0).base() == read.typeRefs().get(0));
        assertEquals(null, read.types().get(2).base());
        assertEquals(SigCode.U8, read.types().get(2).backing());
    }

    @Test
    public static void theWriterRejectsInvalidTypes() {
        unwritable(withTypes(type("a", "A"), type("a", "A")), "two TypeDef rows have the key of a.A (BC-24)");
        TypeDef same = type("a", "A");
        unwritable(withTypes(same, same), "the module lists the type A twice");
        unwritable(withTypes(new TypeDef("a", "A", 8, 0, 0, null)), "type A has the undefined kind 8 (BC-20)");
        unwritable(withTypes(new TypeDef("a", "A", Flags.KIND_CLASS, 0x80, 0, null)),
                "type A has the undefined flags 0x80 (BC-20)");
        unwritable(withTypes(new TypeDef("a", "A", Flags.KIND_CLASS, Flags.PUBLIC | Flags.PRIVATE, 0, null)),
                "type A has more than one visibility flag (BC-11)");
        unwritable(withTypes(new TypeDef("a", "E", Flags.KIND_ENUM, 0, SigCode.CHAR, null)),
                "enum E has the backing type code 7; the backing type is one of I8, U8, I16, U16, I32, U32, I64 and U64 (DAT-05)");
        unwritable(withTypes(new TypeDef("a", "E", Flags.KIND_ENUM, 0, 0, null)),
                "enum E has the backing type code 0; the backing type is one of I8, U8, I16, U16, I32, U32, I64 and U64 (DAT-05)");
        unwritable(withTypes(new TypeDef("a", "A", Flags.KIND_CLASS, 0, SigCode.I32, null)),
                "type A is not an enum, but has a backing type (BC-11)");
        TypeDef outer = type("a", "A");
        unwritable(withTypes(outer, new TypeDef("a", "T", Flags.KIND_CLASS, 0, 0, outer)),
                "nested type T has a namespace; a nested type has namespace 0 (BC-11)");
        unwritable(withTypes(new TypeDef("", "T", Flags.KIND_CLASS, 0, 0, outer)),
                "type T is nested in a type that the module does not list");
        TypeDef derived = type("a", "A");
        derived.setBase(new TypeRef(new ModuleRef("reflect.core", 0), "reflect.core", "Object"));
        unwritable(withTypes(derived), "the base type of a.A is reflect.core.Object, which the module does not list");
        ModuleDef main = withTypes(type("a", "A"));
        main.setMain(type("a", "B"));
        unwritable(main, "the module's main class is a.B, which the module does not list");
    }

    @Test
    public static void theWriterRejectsInvalidTypeRefs() {
        var core = new ModuleRef("reflect.core", 0);
        var unrequired = new ModuleDef("m", 0, 0, 0);
        unrequired.typeRefs().add(new TypeRef(core, "reflect.core", "String"));
        unwritable(unrequired, "TypeRef String names the module reflect.core, which the module does not require");
        var outer = new TypeRef(core, "reflect.core", "Outer");
        var named = withRefs(core, outer, new TypeRef(outer, "x", "Entry"));
        unwritable(named, "nested TypeRef Entry has a namespace; a nested type has namespace 0 (BC-11)");
        unwritable(withRefs(core, new TypeRef(outer, "", "Entry")),
                "TypeRef Entry is nested in a TypeRef that the module does not list");
        unwritable(withRefs(core, new TypeRef(core, "reflect.core", "String"), new TypeRef(core, "reflect.core", "String")),
                "two TypeRef rows have the key of reflect.core.String (BC-24)");
    }

    @Test
    public static void theReaderRejectsInvalidTypeDefColumns() throws Exception {
        byte[] file = ModuleWriter.write(types());
        int def = TableId.TYPE_DEF;
        reject(file, def, 1, 2, 8, "TypeDef kind 0x8 is not defined (BC-20)");
        reject(file, def, 1, 3, 0x80, "TypeDef flags 0x80 are not defined (BC-20)");
        reject(file, def, 1, 3, 0x03, "the flags hold more than one visibility flag (BC-11)");
        reject(file, def, 3, 5, SigCode.CHAR, "the backing type of an enum is one of I8, U8, I16, U16, I32, U32, I64"
                + " and U64, not 0x7 (DAT-05)");
        reject(file, def, 1, 5, SigCode.I32, "TypeDef backing is 0x8, but only an enum has a backing type (BC-11)");
        reject(file, def, 1, 6, 99, "row 99 of table 0x5 does not exist");
        rejected(patch(file, def, 5, 6, 1), columnOffset(file, def, 5, 1), "a nested type has namespace 0 (BC-11)");
        reject(file, def, 1, 4, 0x08000001, "token 0x8000001 does not name a TypeDef, TypeRef or TypeSpec row");
        reject(file, def, 1, 4, 0x07000001, "token 0x7000001 names row 1 of table 0x7, which does not exist");
        reject(file, def, 1, 4, 0x05000063, "token 0x5000063 names row 99 of table 0x5, which does not exist");
        reject(file, def, 1, 7, 2, "the first run of Field rows starts at row 2, not at row 1 (BC-11)");
        reject(file, def, 2, 9, 2, "the run of GenericParam rows starts at row 2, outside rows 1 to 1 (BC-11)");
        reject(file, TableId.MODULE, 1, 5, 99, "row 99 of table 0x5 does not exist");
    }

    @Test
    public static void theReaderRejectsUnsortedRowsAndCycles() throws Exception {
        byte[] file = ModuleWriter.write(types());
        int def = TableId.TYPE_DEF;
        // Rows 3 and 4 are the two Tags, in the types of rows 5 and 6.
        rejected(patch(patch(file, def, 3, 6, 6), def, 4, 6, 5), columnOffset(file, def, 4, 0),
                "the TypeDef rows are not sorted by namespace, name and enclosing type (BC-24)");
        rejected(patch(file, def, 4, 6, 5), columnOffset(file, def, 4, 0), "two TypeDef rows have equal keys (BC-24)");
        // Row 2, Inner, is in row 3; row 3 in row 2 closes a cycle and keeps the order.
        rejected(patch(file, def, 3, 6, 2), columnOffset(file, def, 2, 6), "the enclosing types of TypeDef row 2 form a cycle");
        byte[] refs = ModuleWriter.write(typeRefs());
        int ref = TableId.TYPE_REF;
        rejected(patch(refs, ref, 4, 0, 0x02000001), columnOffset(refs, ref, 4, 0),
                "the TypeRef rows are not sorted by scope, namespace and name (BC-24)");
        rejected(patch(refs, ref, 6, 0, 0x06000006), columnOffset(refs, ref, 6, 0),
                "the enclosing types of TypeRef row 6 form a cycle");
        rejected(patch(refs, ref, 1, 0, 0), columnOffset(refs, ref, 1, 0), "token 0x0 does not name a ModuleRef or TypeRef row");
        rejected(patch(refs, ref, 1, 0, 0x05000001), columnOffset(refs, ref, 1, 0),
                "token 0x5000001 does not name a ModuleRef or TypeRef row");
        rejected(patch(refs, ref, 3, 0, 0x06000001), columnOffset(refs, ref, 3, 1), "a nested type has namespace 0 (BC-11)");
    }

    // G, a.A, a.Z, b.B, a.A/Tag (an enum), a.Z/Tag and a.A/Tag/Inner, added out of order.
    private static ModuleDef types() {
        var module = new ModuleDef("demo", 0, 0, 0);
        TypeDef b = type("b", "B");
        TypeDef az = type("a", "Z");
        TypeDef aa = new TypeDef("a", "A", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_FINAL, 0, null);
        TypeDef global = new TypeDef("", "G", Flags.KIND_INTERFACE, 0, 0, null);
        TypeDef tagZ = new TypeDef("", "Tag", Flags.KIND_CLASS, Flags.PRIVATE, 0, az);
        TypeDef tagA = new TypeDef("", "Tag", Flags.KIND_ENUM, Flags.PUBLIC, SigCode.I32, aa);
        TypeDef inner = new TypeDef("", "Inner", Flags.KIND_CLASS, 0, 0, tagA);
        for (TypeDef type : new TypeDef[] {b, tagZ, inner, az, global, aa, tagA}) {
            module.types().add(type);
        }
        return module;
    }

    // Six TypeRefs of two modules, one of them nested, added out of order.
    private static ModuleDef typeRefs() {
        var module = new ModuleDef("app", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var io = new ModuleRef("reflect.io", 0);
        module.requires().add(io);
        module.requires().add(core);
        var outer = new TypeRef(core, "reflect.core", "Outer");
        for (TypeRef ref : new TypeRef[] {new TypeRef(outer, "", "Entry"), new TypeRef(io, "reflect.io", "Console"),
                new TypeRef(core, "reflect.core", "String"), new TypeRef(io, "", "X"),
                new TypeRef(core, "reflect.core", "Object"), outer}) {
            module.typeRefs().add(ref);
        }
        return module;
    }

    private static TypeDef type(String namespace, String name) {
        return new TypeDef(namespace, name, Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
    }

    private static ModuleDef withTypes(TypeDef... types) {
        var module = new ModuleDef("m", 0, 0, 0);
        for (TypeDef type : types) {
            module.types().add(type);
        }
        return module;
    }

    private static ModuleDef withRefs(ModuleRef required, TypeRef... refs) {
        var module = new ModuleDef("m", 0, 0, 0);
        module.requires().add(required);
        for (TypeRef ref : refs) {
            module.typeRefs().add(ref);
        }
        return module;
    }

    private static String names(ModuleDef module) {
        var names = new StringBuilder();
        for (TypeDef type : module.types()) {
            names.append(names.length() == 0 ? "" : " ").append(type.qualifiedName());
        }
        return names.toString();
    }

    private static String enclosingColumns(byte[] file, int rows) throws ModuleFormatException {
        var values = new StringBuilder();
        for (int row = 1; row <= rows; row++) {
            values.append(row == 1 ? "" : " ").append(column(file, TableId.TYPE_DEF, row, 6));
        }
        return values.toString();
    }

    private static void reject(byte[] file, int table, int row, int column, int value, String message) throws Exception {
        rejected(patch(file, table, row, column, value), columnOffset(file, table, row, column), message);
    }
}
