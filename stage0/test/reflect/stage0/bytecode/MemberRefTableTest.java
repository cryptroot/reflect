package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.bytes;
import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class MemberRefTableTest {
    // Opaque until signatures get their model: static void (Object?) and static void (String).
    private static final Blob PRINT_OBJECT = new Blob(bytes(SigCode.METHOD, SigCode.METHOD_STATIC, 1, SigCode.VOID,
            SigCode.NULLABLE, SigCode.CLASS, 2));
    private static final Blob PRINT_STRING = new Blob(bytes(SigCode.METHOD, SigCode.METHOD_STATIC, 1, SigCode.VOID,
            SigCode.CLASS, 3));
    private static final Blob INT_FIELD = new Blob(bytes(SigCode.FIELD, SigCode.I32));

    @Test
    public static void memberRefsSortByParentNameAndSignatureBytes() throws Exception {
        byte[] file = roundTrip(memberRefs());
        ModuleDef read = ModuleReader.read(file);
        var order = new StringBuilder();
        for (MemberRef ref : read.memberRefs()) {
            order.append(ref.parent().qualifiedName()).append("::").append(ref.name()).append(' ')
                    .append(Leb128Test.hex(ref.signature().toByteArray())).append("; ");
        }
        // String is TypeRef row 1 and Console row 2; the two println rows differ in the fifth byte.
        assertEquals("reflect.core.String::length 20 08; reflect.io.Console::print 21 01 01 01 10 03; "
                + "reflect.io.Console::println 21 01 01 01 10 03; reflect.io.Console::println 21 01 01 01 15 10 02; ",
                order.toString());
        assertEquals(0x06000001, column(file, TableId.MEMBER_REF, 1, 0));
        assertEquals(0x06000002, column(file, TableId.MEMBER_REF, 4, 0));
        assertTrue(read.memberRefs().get(3).parent() == read.typeRefs().get(1));
        // print and println(String) share one BLOBS entry.
        assertEquals(column(file, TableId.MEMBER_REF, 2, 2), column(file, TableId.MEMBER_REF, 3, 2));
    }

    @Test
    public static void theWriterRejectsInvalidMemberRefs() {
        var module = memberRefs();
        var app = new TypeDef("app", "App", Flags.KIND_CLASS, 0, 0, null);
        module.types().add(app);
        module.memberRefs().add(new MemberRef(app, "run", PRINT_STRING));
        unwritable(module, "MemberRef app.App::run has a TypeDef as parent; a MemberRef names a member of a TypeRef"
                + " or TypeSpec (BC-11)");
        var unlisted = memberRefs();
        var core = unlisted.requires().get(0);
        unlisted.memberRefs().add(new MemberRef(new TypeRef(core, "reflect.core", "Object"), "hash", INT_FIELD));
        unwritable(unlisted, "MemberRef reflect.core.Object::hash has a parent that the module does not list");
        var twice = memberRefs();
        twice.memberRefs().add(new MemberRef(twice.typeRefs().get(0), "length", INT_FIELD));
        unwritable(twice, "two MemberRef rows have the key of reflect.core.String::length (BC-24)");
        var same = memberRefs();
        same.memberRefs().add(same.memberRefs().get(0));
        unwritable(same, "the module lists the MemberRef reflect.core.String::length twice");
    }

    @Test
    public static void theReaderRejectsInvalidMemberRefRows() throws Exception {
        byte[] file = ModuleWriter.write(memberRefs());
        int ref = TableId.MEMBER_REF;
        rejected(patch(file, ref, 1, 0, 0x05000001), columnOffset(file, ref, 1, 0),
                "token 0x5000001 does not name a TypeRef or TypeSpec row");
        rejected(patch(file, ref, 1, 0, 0x06000003), columnOffset(file, ref, 1, 0),
                "token 0x6000003 names row 3 of table 0x6, which does not exist");
        rejected(patch(file, ref, 4, 0, 0x06000001), columnOffset(file, ref, 4, 0),
                "the MemberRef rows are not sorted by parent, name and signature (BC-24)");
        // Rows 2 and 3 have one parent and one BLOBS offset; the name of row 2 in row 3 makes the keys equal.
        rejected(patch(file, ref, 3, 1, column(file, ref, 2, 1)), columnOffset(file, ref, 3, 0),
                "two MemberRef rows have equal keys (BC-24)");
    }

    // Four MemberRefs on reflect.core.String and reflect.io.Console, added out of order.
    private static ModuleDef memberRefs() {
        var module = new ModuleDef("app", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var io = new ModuleRef("reflect.io", 0);
        module.requires().add(core);
        module.requires().add(io);
        var string = new TypeRef(core, "reflect.core", "String");
        var console = new TypeRef(io, "reflect.io", "Console");
        module.typeRefs().add(string);
        module.typeRefs().add(console);
        module.memberRefs().add(new MemberRef(string, "length", INT_FIELD));
        module.memberRefs().add(new MemberRef(console, "println", PRINT_OBJECT));
        module.memberRefs().add(new MemberRef(console, "println", PRINT_STRING));
        module.memberRefs().add(new MemberRef(console, "print", PRINT_STRING));
        return module;
    }
}
