package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleTypesTest {
    private static final String M = ".module m 0.0.0\n";

    @Test
    public static void classesAndFieldsGiveTheRowsThatTheyState() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".requires reflect.core\n"
                + ".requires reflect.collections\n"
                + ".requires other\n"
                + ".main demo.zoo.Zoo\n"
                + ".class public abstract demo.zoo.Animal extends reflect.core.Object implements demo.zoo.Named {\n"
                + "    .field protected reflect.core.String name\n"
                + "    .field public static final int count\n"
                + "    .class public enum Kind : ubyte {\n"
                + "        .field public static final valuetype demo.zoo.Animal/Kind DOG\n"
                + "    }\n"
                + "}\n"
                + ".class public interface demo.zoo.Named {\n"
                + "}\n"
                + ".class public final demo.zoo.Zoo extends demo.zoo.Animal implements demo.zoo.Named,"
                + " reflect.collections.Iterable {\n"
                + "    .field private demo.zoo.Animal?[] animals\n"
                + "    .field private reflect.collections.List<? extends demo.zoo.Animal> list\n"
                + "    .field private reflect.collections.Map<reflect.core.String, reflect.core.Int32> counts\n"
                + "    .field private (reflect.core.Object & demo.zoo.Named) both\n"
                + "    .field private valuetype other.Point point\n"
                + "    .field private [reflect.core]reflect.meta.Type type\n"
                + "    .field private ref int byRef\n"
                + "    .field private byte* pointer\n"
                + "    .field private backing boolean flag\n"
                + "    .field private reflect.collections.List<?> any\n"
                + "    .field private reflect.collections.List<? super reflect.core.String> sink\n"
                + "    .field private out reflect.core.Object result\n"
                + "    .field private in long readOnly\n"
                + "    .field private demo.Pair pair\n"
                + "}\n"
                + ".class public record struct demo.Pair {\n"
                + "    .class private ref struct Inner {\n"
                + "        .class annotation Tag {\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
                + ".class G {\n"
                + "}\n";
        var expected = new ModuleDef("demo", 1, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var collections = new ModuleRef("reflect.collections", 0);
        var other = new ModuleRef("other", 0);
        var object = new TypeRef(core, "reflect.core", "Object");
        var string = new TypeRef(core, "reflect.core", "String");
        var iterable = new TypeRef(collections, "reflect.collections", "Iterable");
        var listType = new TypeRef(collections, "reflect.collections", "List");
        var map = new TypeRef(collections, "reflect.collections", "Map");
        var point = new TypeRef(other, "other", "Point");
        var metaType = new TypeRef(core, "reflect.meta", "Type");
        var animal = new TypeDef("demo.zoo", "Animal", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_ABSTRACT, 0, null);
        var kind = new TypeDef("", "Kind", Flags.KIND_ENUM, Flags.PUBLIC, SigCode.U8, animal);
        var named = new TypeDef("demo.zoo", "Named", Flags.KIND_INTERFACE, Flags.PUBLIC, 0, null);
        var zoo = new TypeDef("demo.zoo", "Zoo", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_FINAL, 0, null);
        var pair = new TypeDef("demo", "Pair", Flags.KIND_RECORD_STRUCT, Flags.PUBLIC, 0, null);
        var inner = new TypeDef("", "Inner", Flags.KIND_REF_STRUCT, Flags.PRIVATE, 0, pair);
        var tag = new TypeDef("", "Tag", Flags.KIND_ANNOTATION, 0, 0, inner);
        var global = new TypeDef("", "G", Flags.KIND_CLASS, 0, 0, null);
        animal.setBase(object);
        animal.interfaces().add(named);
        animal.fields().add(new FieldDef(Flags.PROTECTED, "name", new TypeSig.Named(false, string)));
        int constant = Flags.PUBLIC | Flags.FIELD_STATIC | Flags.FIELD_FINAL;
        animal.fields().add(new FieldDef(constant, "count", primitive(SigCode.I32)));
        kind.fields().add(new FieldDef(constant, "DOG", new TypeSig.Named(true, kind)));
        zoo.setBase(animal);
        zoo.interfaces().add(named);
        zoo.interfaces().add(iterable);
        var animalSig = new TypeSig.Named(false, animal);
        var stringSig = new TypeSig.Named(false, string);
        var listSig = new TypeSig.Named(false, listType);
        int hidden = Flags.PRIVATE;
        zoo.fields().add(new FieldDef(hidden, "animals", new TypeSig.ArrayOf(new TypeSig.NullableRef(animalSig))));
        zoo.fields().add(new FieldDef(hidden, "list", new TypeSig.GenericInst(listSig,
                list(new TypeSig.Wildcard(SigCode.WILDCARD_EXTENDS, animalSig)))));
        zoo.fields().add(new FieldDef(hidden, "counts", new TypeSig.GenericInst(new TypeSig.Named(false, map),
                list(stringSig, primitive(SigCode.I32)))));
        zoo.fields().add(new FieldDef(hidden, "both", new TypeSig.Intersection(list(new TypeSig.Named(false, object),
                new TypeSig.Named(false, named)))));
        zoo.fields().add(new FieldDef(hidden, "point", new TypeSig.Named(true, point)));
        zoo.fields().add(new FieldDef(hidden, "type", new TypeSig.Named(false, metaType)));
        zoo.fields().add(new FieldDef(hidden, "byRef", new TypeSig.ByRef(SigCode.BYREF_REF, primitive(SigCode.I32))));
        zoo.fields().add(new FieldDef(hidden, "pointer", new TypeSig.PointerTo(primitive(SigCode.I8))));
        zoo.fields().add(new FieldDef(hidden | Flags.FIELD_BACKING, "flag", primitive(SigCode.BOOLEAN)));
        zoo.fields().add(new FieldDef(hidden, "any", new TypeSig.GenericInst(listSig,
                list(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null)))));
        zoo.fields().add(new FieldDef(hidden, "sink", new TypeSig.GenericInst(listSig,
                list(new TypeSig.Wildcard(SigCode.WILDCARD_SUPER, stringSig)))));
        zoo.fields().add(new FieldDef(hidden, "result", new TypeSig.ByRef(SigCode.BYREF_OUT,
                new TypeSig.Named(false, object))));
        zoo.fields().add(new FieldDef(hidden, "readOnly", new TypeSig.ByRef(SigCode.BYREF_IN, primitive(SigCode.I64))));
        // A value type of the module needs no valuetype: its kind tells (Q69).
        zoo.fields().add(new FieldDef(hidden, "pair", new TypeSig.Named(true, pair)));
        for (ModuleRef ref : new ModuleRef[] {core, collections, other}) {
            expected.requires().add(ref);
        }
        for (TypeRef ref : new TypeRef[] {object, string, iterable, listType, map, point, metaType}) {
            expected.typeRefs().add(ref);
        }
        for (TypeDef type : new TypeDef[] {animal, kind, named, zoo, pair, inner, tag, global}) {
            expected.types().add(type);
        }
        expected.setMain(zoo);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
    }

    @Test
    public static void aTypeRefBelongsToTheRequiredModuleOfTheLongestPrefix() throws Exception {
        byte[] file = assemble(M + ".requires a\n.requires a.b\n.class m.C {\n"
                + "    .field a.b.c.X x\n    .field a.bc.Y y\n    .field a.Z z\n    .field [a.b]a.W w\n"
                + "    .field [a]a.b.V v\n    .field a.b.U/Inner/Deep u\n    .field a.b.c.X again\n}\n");
        var scopes = new StringBuilder();
        for (TypeRef ref : ModuleReader.read(file).typeRefs()) {
            TypeScope scope = ref.scope();
            scopes.append(ref.qualifiedName()).append(" in ")
                    .append(scope instanceof ModuleRef required ? required.name() : ((TypeRef) scope).qualifiedName())
                    .append("; ");
        }
        assertEquals("a.Z in a; a.b.V in a; a.bc.Y in a; a.W in a.b; a.b.U in a.b; a.b.c.X in a.b; "
                + "a.b.U/Inner in a.b.U; a.b.U/Inner/Deep in a.b.U/Inner; ", scopes.toString());
    }

    @Test
    public static void anAtMakesAWordOfTheGrammarAName() throws Exception {
        // static, record, int and ref are words of the grammar; after @ each one names a module or a type (Q82).
        String text = M + ".requires @static\n"
                + ".class public @record {\n"
                + "    .field @int x\n"
                + "    .field [@static]@ref y\n"
                + "}\n"
                + ".class @int {\n"
                + "}\n";
        var expected = new ModuleDef("m", 0, 0, 0);
        var required = new ModuleRef("static", 0);
        var ref = new TypeRef(required, "", "ref");
        var record = new TypeDef("", "record", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var integer = new TypeDef("", "int", Flags.KIND_CLASS, 0, 0, null);
        record.fields().add(new FieldDef(0, "x", new TypeSig.Named(false, integer)));
        record.fields().add(new FieldDef(0, "y", new TypeSig.Named(false, ref)));
        expected.requires().add(required);
        expected.typeRefs().add(ref);
        expected.types().add(record);
        expected.types().add(integer);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
        String body = M + ".class a.A {\n.method static void m() {\n";
        unassemblable(body + "@nop\n}\n}", "4:1: RFA0101: expected an instruction, a label, a directive of a body or }, found"
                + " @nop");
        unassemblable(body + "ldc.r4 @inf\n}\n}", "4:8: RFA0101: expected a float, found @inf");
    }

    @Test
    public static void bracketsNameAPrimitivesStructAndTheModuleItself() throws Exception {
        String code = "    .method static void f() {\n"
                + "        ldc.i4 1\n"
                + "        box [reflect.core]reflect.core.Int32\n"
                + "        pop\n"
                + "        call static void [reflect.core]reflect.core.Int32::check()\n"
                + "        ret\n"
                + "    }\n";
        var empty = new MethodSig(SigCode.METHOD_STATIC, 0, primitive(SigCode.VOID), list());
        // In another module the brackets name the TypeRef row of the struct (Q81).
        var expected = new ModuleDef("m", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var struct = new TypeRef(core, "reflect.core", "Int32");
        var check = new MemberRef(struct, "check", empty);
        var user = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        user.setBase(struct);
        user.methods().add(f(struct, check));
        expected.requires().add(core);
        expected.typeRefs().add(struct);
        expected.memberRefs().add(check);
        expected.types().add(user);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(M
                + ".requires reflect.core\n.class a.A extends [reflect.core]reflect.core.Int32 {\n" + code + "}\n")));
        // In module reflect.core, brackets with its own name name its TypeDef row of the struct.
        expected = new ModuleDef("reflect.core", 0, 0, 0);
        var own = new TypeDef("reflect.core", "Int32", Flags.KIND_STRUCT, Flags.PUBLIC, 0, null);
        var ownCheck = new MethodDef(Flags.PUBLIC | Flags.METHOD_STATIC, "check", empty);
        ownCheck.setBody(new MethodBody(0));
        ownCheck.body().code().add(new Instruction.Plain(Opcode.RET));
        own.methods().add(ownCheck);
        user = new TypeDef("reflect.core", "User", Flags.KIND_CLASS, 0, 0, null);
        user.methods().add(f(own, ownCheck));
        expected.types().add(own);
        expected.types().add(user);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(".module reflect.core 0.0.0\n"
                + ".class public struct reflect.core.Int32 {\n    .method public static void check() {\n        ret\n    }\n}\n"
                + ".class reflect.core.User {\n" + code + "}\n")));
        String core32 = M + ".requires reflect.core\n.class a.A {\n";
        unassemblable(core32 + ".field [reflect.core]reflect.core.Int32 x\n}",
                "4:8: RFA0301: the primitive reflect.core.Int32 is its code, not its struct by name (BC-03)");
        unassemblable(core32 + ".method static void f() {\nbox reflect.core.Int32\n}\n}",
                "5:5: RFA0303: the type needs a TypeSpec row, which arrives in v0.0.6");
        unassemblable(M + ".class a.A extends [m]a.B {\n}", "2:23: RFA0201: the module defines no type a.B");
        unassemblable(M + ".requires m", "2:11: RFA0301: the module m requires itself; a requires cycle is an error (MOD-03)");
    }

    // static void f(): ldc.i4 1; box the struct; pop; call its method check; ret.
    private static MethodDef f(TypeHandle struct, MethodHandle check) {
        var method = new MethodDef(Flags.METHOD_STATIC, "f", new MethodSig(SigCode.METHOD_STATIC, 0,
                primitive(SigCode.VOID), list()));
        var body = new MethodBody(1);
        body.code().add(new Instruction.Value32(Opcode.LDC_I4, 1));
        body.code().add(new Instruction.Type(Opcode.BOX, struct));
        body.code().add(new Instruction.Plain(Opcode.POP));
        body.code().add(new Instruction.Method(Opcode.CALL, check));
        body.code().add(new Instruction.Plain(Opcode.RET));
        method.setBody(body);
        return method;
    }

    @Test
    public static void typeRefAndMemberRefDeclareRowsThatNothingNames() throws Exception {
        String text = M + ".requires reflect.core\n"
                + ".requires other\n"
                + ".typeref reflect.core.Object\n"
                + ".typeref [other]x.Y/Z\n"
                + ".typeref [reflect.core]reflect.core.Int32\n"
                + ".memberref reflect.core.String reflect.core.Object::name\n"
                + ".memberref static void other.Log::write(reflect.core.Object?[]...)\n"
                + ".memberref reflect.core.Object reflect.core.Object::copy()\n"
                + ".class a.A {\n"
                + "    .field reflect.core.Object o\n"
                + "}\n"
                + ".typeref reflect.core.Object\n";
        var expected = new ModuleDef("m", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var other = new ModuleRef("other", 0);
        var object = new TypeRef(core, "reflect.core", "Object");
        var y = new TypeRef(other, "x", "Y");
        var z = new TypeRef(y, "", "Z");
        var int32 = new TypeRef(core, "reflect.core", "Int32");
        var string = new TypeRef(core, "reflect.core", "String");
        var log = new TypeRef(other, "other", "Log");
        var objectSig = new TypeSig.Named(false, object);
        expected.requires().add(core);
        expected.requires().add(other);
        for (TypeRef ref : new TypeRef[] {object, y, z, int32, string, log}) {
            expected.typeRefs().add(ref);
        }
        expected.memberRefs().add(new MemberRef(object, "name", new FieldSig(new TypeSig.Named(false, string))));
        expected.memberRefs().add(new MemberRef(log, "write", new MethodSig(SigCode.METHOD_STATIC | SigCode.METHOD_VARIADIC,
                0, primitive(SigCode.VOID), list(new TypeSig.ArrayOf(new TypeSig.NullableRef(objectSig))))));
        expected.memberRefs().add(new MemberRef(object, "copy", new MethodSig(0, 0, objectSig, list())));
        var a = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        a.fields().add(new FieldDef(0, "o", objectSig));
        expected.types().add(a);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
        String core32 = M + ".requires reflect.core\n.requires other\n.class a.A {\n}\n";
        unassemblable(core32 + ".typeref a.A", "6:10: RFA0301: the module defines a.A, and .typeref declares a type of"
                + " another module (Q80)");
        unassemblable(core32 + ".typeref reflect.core.Int32", "6:10: RFA0301: reflect.core.Int32 alone is the primitive;"
                + " brackets name the row of its struct (Q81)");
        unassemblable(core32 + ".typeref [x]x.Y", "6:11: RFA0203: the module does not require x");
        unassemblable(core32 + ".typeref other.X<int>", "6:17: RFA0101: expected a directive of the module, found <");
        unassemblable(core32 + ".memberref int a.A::x", "6:16: RFA0301: the module defines a.A, and .memberref declares a"
                + " member of another module (Q80)");
        unassemblable(core32 + ".memberref static int other.X::x", "6:33: RFA0101: expected (, found the end of the file");
        unassemblable(core32 + ".memberref void other.X::x", "6:12: RFA0301: VOID stands outside a return type (BC-03)");
        unassemblable(core32 + ".memberref other.X::x()", "6:19: RFA0101: expected the type that holds the member, found ::");
        unassemblable(core32 + ".memberref void other.X::m<int>()", "6:27: RFA0303: the MethodSpec table arrives in v0.0.9");
        unassemblable(core32 + ".memberref void other.X::m() x", "6:30: RFA0101: expected a directive of the module, found x");
    }

    @Test
    public static void eachClassAndFieldHasItsDiagnostics() {
        unassemblable(M + ".class public public a.A {\n}", "2:15: RFA0104: public appears twice");
        unassemblable(M + ".class public private a.A {\n}", "2:23: RFA0301: type A has more than one visibility flag (BC-11)");
        unassemblable(M + ".class a.A {\n.class b.C {\n}\n}", "3:8: RFA0101: a nested type names only itself (BC-08)");
        unassemblable(M + ".class a.A/B {\n}",
                "2:8: RFA0101: a nested type is declared inside the type that encloses it (BC-08)");
        unassemblable(M + ".class a.A {\n}\n.class a.A {\n}", "4:8: RFA0301: two TypeDef rows have the key of a.A (BC-24)");
        unassemblable(M + ".class enum a.E {\n}", "2:13: RFA0301: enum E has the backing type code 0; the backing type is"
                + " one of I8, U8, I16, U16, I32, U32, I64 and U64 (DAT-05)");
        unassemblable(M + ".class a.A : int {\n}", "2:8: RFA0301: type A is not an enum, but has a backing type (BC-11)");
        String backing = "RFA0301: the backing type of an enum is one of I8, U8, I16, U16, I32, U32, I64 and U64 (DAT-05)";
        unassemblable(M + ".class enum a.E : char {\n}", "2:19: " + backing);
        unassemblable(M + ".class enum a.E : a.B {\n}", "2:19: " + backing);
        unassemblable(M + ".class ref a.A {\n}", "2:12: RFA0101: expected struct, found a.A");
        unassemblable(M + ".class a.A<T> {\n}", "2:11: RFA0303: the GenericParam table arrives in v0.0.9");
        String typeSpec = "RFA0303: the type needs a TypeSpec row, which arrives in v0.0.6";
        unassemblable(M + ".class a.A extends int {\n}", "2:20: " + typeSpec);
        unassemblable(M + ".class a.A extends a.B<int> {\n}\n.class a.B {\n}", "2:20: " + typeSpec);
        unassemblable(M + ".class a.A implements a.B {\n}\n.class a.B {\n}",
                "2:23: RFA0301: type a.A implements a.B, which is not an interface (BC-11)");
        unassemblable(M + ".class a.A implements a.I, a.I {\n}\n.class interface a.I {\n}",
                "2:28: RFA0301: type a.A implements a.I twice (BC-24)");
        unassemblable(M + ".class a.A extends x.Y {\n}",
                "2:20: RFA0202: no required module holds the namespace x of x.Y; name its module in brackets (Q76)");
        unassemblable(M + ".class a.A extends G {\n}",
                "2:20: RFA0202: no required module holds the global namespace of G; name its module in brackets (Q76)");
        unassemblable(M + ".class a.A extends [x]x.Y {\n}", "2:21: RFA0203: the module does not require x");
        unassemblable(M + ".class a.A", "2:11: RFA0101: expected {, found the end of the file");
        unassemblable(M + ".class a.A {\n", "3:1: RFA0101: expected .field, .method, .class or }, found the end of the file");
        unassemblable(M + ".main a.B\n.class a.A {\n}", "2:7: RFA0201: the module defines no type a.B");
        String field = M + ".class a.A {\n.field ";
        unassemblable(field + "int x = 1\n}", "3:14: RFA0303: the Constant table arrives in v0.0.6");
        unassemblable(field + "static static int x\n}", "3:15: RFA0104: static appears twice");
        unassemblable(field + "const int x\n}",
                "3:18: RFA0301: field a.A::x is const but not static; a const field is also STATIC (BC-11)");
        unassemblable(field + "void x\n}", "3:8: RFA0101: void stands only as a return type (BC-03)");
        unassemblable(field + "valuetype a.A x\n}", "3:8: RFA0301: a.A is a reference type, so CLASS names it (BC-03)");
        unassemblable(field + "a.A/B x\n}", "3:8: RFA0201: the module defines no type a.A/B");
        unassemblable(field + "int x y\n}", "3:14: RFA0101: expected .field, .method, .class or }, found y");
        unassemblable(field + "int\n}", "4:1: RFA0101: expected the name of the field, found }");
        unassemblable(field + "!0 x\n}", "3:8: RFA0301: VAR 0 names no type parameter of the type (BC-03)");
        unassemblable(field + "reflect.core.Int32<int> x\n}", "3:8: RFA0301: a primitive takes no type arguments (BC-03)");
        unassemblable(field + "(a.A & a.A) x\n}", "3:8: RFA0301: the class of an INTERSECTION is its first component (GEN-06)");
        unassemblable(field + "a.A?? x\n}",
                "3:8: RFA0301: NULLABLE wraps CLASS, a GENERICINST of CLASS, ARRAY, VAR or MVAR (BC-03)");
        unassemblable(field + "[x]Q x\n}", "3:9: RFA0203: the module does not require x");
    }

    @Test
    public static void signaturesAndIdentifiersHaveTheLimitsOfBc21() throws Exception {
        String deep = "int" + "[]".repeat(254);
        ModuleReader.read(assemble(M + ".class a.A {\n.field " + deep + " x\n}"));
        unassemblable(M + ".class a.A {\n.field " + deep + "[] x\n}",
                "3:8: RFA0302: the signature nests types deeper than 255 (BC-21)");
        String longest = "b".repeat(Limits.MAX_IDENTIFIER_BYTES + 1);
        String limit = "RFA0302: an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)";
        unassemblable(M + ".class a." + longest + " {\n}", "2:8: " + limit);
        unassemblable(M + ".class " + longest + ".A {\n}", "2:8: " + limit);
        unassemblable(M + ".class a.A {\n.field int " + longest + "\n}", "3:12: " + limit);
        unassemblable(M + ".requires x\n.class a.A {\n.field x." + longest + " y\n}", "4:8: " + limit);
    }

    private static TypeSig primitive(int code) {
        return new TypeSig.Primitive(code);
    }
}
