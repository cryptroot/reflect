package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.blobBytes;
import static reflect.stage0.bytecode.TestModules.blobOffset;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class SignatureTest {
    // TypeRef rows of reflect.core by name: Int32 1, List 2, Object 3, Runnable 4, String 5, as
    // ULEB128 tokens; TypeDef app.Point is row 1.
    private static final String LIST = "82 80 80 30";
    private static final String OBJECT = "83 80 80 30";
    private static final String RUNNABLE = "84 80 80 30";
    private static final String STRING = "85 80 80 30";
    private static final String POINT = "81 80 80 28";
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final String NULLABLE = "NULLABLE wraps CLASS, a GENERICINST of CLASS, ARRAY, VAR or MVAR (BC-03)";

    @Test
    public static void everyTypeCodeHasItsEncodingAndDecodesBack() throws Exception {
        var types = new Types();
        TypeSig string = types.named("String");
        TypeSig object = types.named("Object");
        Object[][] fields = {
            {INT, "20 08", "I32"},
            {string, "20 10 " + STRING, "reflect.core.String"},
            {new TypeSig.NullableRef(string), "20 15 10 " + STRING, "reflect.core.String?"},
            {new TypeSig.ArrayOf(INT), "20 16 08", "I32[]"},
            {types.list(string), "20 12 10 " + LIST + " 01 10 " + STRING, "reflect.core.List<reflect.core.String>"},
            {types.list(new TypeSig.Wildcard(SigCode.WILDCARD_EXTENDS, object)), "20 12 10 " + LIST + " 01 19 01 10 " + OBJECT,
                "reflect.core.List<? extends reflect.core.Object>"},
            {types.list(new TypeSig.Wildcard(SigCode.WILDCARD_SUPER, string)), "20 12 10 " + LIST + " 01 19 02 10 " + STRING,
                "reflect.core.List<? super reflect.core.String>"},
            {types.list(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null)), "20 12 10 " + LIST + " 01 19 00",
                "reflect.core.List<?>"},
            {new TypeSig.PointerTo(new TypeSig.Primitive(SigCode.I8)), "20 17 03", "I8*"},
            {new TypeSig.Named(true, types.point), "20 11 " + POINT, "valuetype app.Point"},
            {new TypeSig.Intersection(list(object, types.named("Runnable"))), "20 1A 02 10 " + OBJECT + " 10 " + RUNNABLE,
                "(reflect.core.Object & reflect.core.Runnable)"},
            {new TypeSig.NullableRef(new TypeSig.ArrayOf(string)), "20 15 16 10 " + STRING, "reflect.core.String[]?"},
            {new TypeSig.ArrayOf(new TypeSig.NullableRef(string)), "20 16 15 10 " + STRING, "reflect.core.String?[]"},
        };
        for (int i = 0; i < fields.length; i++) {
            types.shapes.fields().add(new FieldDef(0, "f" + i, (TypeSig) fields[i][0]));
        }
        var refs = new MethodSig(0, 0, new TypeSig.ByRef(SigCode.BYREF_REF, new TypeSig.Primitive(SigCode.I64)),
                list(new TypeSig.ByRef(SigCode.BYREF_IN, INT), new TypeSig.ByRef(SigCode.BYREF_OUT, string)));
        var method = new MethodDef(Flags.METHOD_ABSTRACT, "m", refs);
        method.params().add(new ParamDef(0, "a"));
        method.params().add(new ParamDef(0, "b"));
        types.shapes.methods().add(method);
        TypeRef list = types.ref("List");
        types.module.memberRefs().add(new MemberRef(list, "get", new MethodSig(SigCode.METHOD_STATIC | SigCode.METHOD_GENERIC,
                1, new TypeSig.TypeVar(true, 0), list(new TypeSig.TypeVar(true, 0)))));
        types.module.memberRefs().add(new MemberRef(list, "all", new MethodSig(SigCode.METHOD_STATIC | SigCode.METHOD_VARIADIC,
                0, VOID, list(new TypeSig.ArrayOf(object)))));
        types.module.memberRefs().add(new MemberRef(list, "head", new FieldSig(new TypeSig.TypeVar(false, 5))));
        byte[] file = roundTrip(types.module);
        ModuleDef read = ModuleReader.read(file);
        TypeDef shapes = read.types().get(1);
        for (int i = 0; i < fields.length; i++) {
            assertEquals(fields[i][1], Leb128Test.hex(blobBytes(file, TableId.FIELD, i + 1, 2)));
            assertEquals(fields[i][2], describe(shapes.fields().get(i).type()));
        }
        assertEquals("21 00 02 18 00 0A 18 01 08 18 02 10 " + STRING, Leb128Test.hex(blobBytes(file, TableId.METHOD, 1, 2)));
        assertEquals("ref I64 (in I32, out reflect.core.String)", describe(shapes.methods().get(0).signature()));
        assertEquals("21 05 01 01 16 10 " + OBJECT, Leb128Test.hex(blobBytes(file, TableId.MEMBER_REF, 1, 2)));
        assertEquals("21 03 01 01 14 00 14 00", Leb128Test.hex(blobBytes(file, TableId.MEMBER_REF, 2, 2)));
        assertEquals("20 13 05", Leb128Test.hex(blobBytes(file, TableId.MEMBER_REF, 3, 2)));
        assertEquals("!!0 (!!0)", describe((MethodSig) read.memberRefs().get(1).signature()));
    }

    @Test
    public static void theWriterChecksTheStructuralRules() {
        field(VOID, "VOID stands outside a return type (BC-03)");
        field(new TypeSig.ArrayOf(VOID), "VOID stands outside a return type (BC-03)");
        field(new TypeSig.Primitive(0x0E), "code 0xE is not a primitive (BC-03)");
        field(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null), "WILDCARD stands outside a type argument (BC-03)");
        field(new TypeSig.ArrayOf(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, null)),
                "WILDCARD stands outside a type argument (BC-03)");
        var types = new Types();
        field(types, types.list(), "GENERICINST has no type argument (BC-03)");
        field(new TypeSig.TypeVar(false, 0), "VAR 0 names no type parameter of the type (BC-03)");
        field(new TypeSig.TypeVar(true, 0), "MVAR 0 names no type parameter of the method (BC-03)");
        field(new TypeSig.NullableRef(INT), NULLABLE);
        types = new Types();
        field(types, new TypeSig.NullableRef(new TypeSig.NullableRef(types.named("String"))), NULLABLE);
        types = new Types();
        field(types, new TypeSig.NullableRef(new TypeSig.Named(true, types.point)), NULLABLE);
        types = new Types();
        field(types, new TypeSig.NullableRef(new TypeSig.Intersection(list(types.named("Object"), types.named("Runnable")))),
                NULLABLE);
        field(new TypeSig.NullableRef(new TypeSig.PointerTo(INT)), NULLABLE);
        field(new TypeSig.ByRef(3, INT), "BYREF kind 0x3 is not defined (BC-20)");
        types = new Types();
        field(types, types.list(new TypeSig.Wildcard(3, null)), "WILDCARD kind 0x3 is not defined (BC-20)");
        types = new Types();
        field(types, types.list(new TypeSig.Wildcard(SigCode.WILDCARD_ANY, INT)),
                "a WILDCARD has a bound exactly when its kind is not ANY (BC-03)");
        types = new Types();
        field(types, types.list(new TypeSig.Wildcard(SigCode.WILDCARD_EXTENDS, null)),
                "a WILDCARD has a bound exactly when its kind is not ANY (BC-03)");
        types = new Types();
        field(types, new TypeSig.Intersection(list(types.named("Object"))), "INTERSECTION has at least two components (BC-03)");
        types = new Types();
        field(types, new TypeSig.Intersection(list(types.named("Object"), INT)),
                "the components of INTERSECTION are classes and interfaces (BC-03)");
        types = new Types();
        field(types, types.named("Int32"), "the primitive reflect.core.Int32 is its code, not its struct by name (BC-03)");
        field(nested(255), null);
        field(nested(256), "the signature nests types deeper than 255 (BC-21)");
        types = new Types();
        TypeSig thing = new TypeSig.Named(false, new TypeRef(types.module.requires().get(0), "reflect.core", "Thing"));
        types.shapes.fields().add(new FieldDef(0, "f", thing));
        unwritable(types.module, "a type in a signature is reflect.core.Thing, which the module does not list");
    }

    @Test
    public static void theWriterChecksMethodSignatures() {
        method(new MethodSig(0x08, 0, VOID, list()), "METHOD flags 0x8 are not defined (BC-20)");
        String generic = "a signature has type parameters exactly when it is GENERIC, and then at least one (BC-03)";
        method(new MethodSig(SigCode.METHOD_GENERIC, 0, VOID, list()), generic);
        method(new MethodSig(0, 1, VOID, list()), generic);
        String variadic = "the last parameter of a VARIADIC signature is an array (BC-03)";
        method(new MethodSig(SigCode.METHOD_VARIADIC, 0, VOID, list()), variadic);
        method(new MethodSig(SigCode.METHOD_VARIADIC, 0, VOID, list(new TypeSig.ArrayOf(INT), INT)), variadic);
        var types = new Types();
        types.module.memberRefs().add(new MemberRef(types.ref("List"), "get", new MethodSig(
                SigCode.METHOD_STATIC | SigCode.METHOD_GENERIC, 1, VOID, list(new TypeSig.TypeVar(true, 1)))));
        unwritable(types.module, "MemberRef reflect.core.List::get: MVAR 1 names no type parameter of the method (BC-03)");
        var core = new ModuleDef("reflect.core", 0, 0, 0);
        var int32 = new TypeDef("reflect.core", "Int32", Flags.KIND_STRUCT, Flags.PUBLIC, 0, null);
        int32.fields().add(new FieldDef(0, "value", new TypeSig.Named(true, int32)));
        core.types().add(int32);
        unwritable(core, "field reflect.core.Int32::value: the primitive reflect.core.Int32 is its code, not its struct"
                + " by name (BC-03)");
    }

    @Test
    public static void aTypeOfTheModuleHasTheCodeOfItsKind() throws Exception {
        int[][] kinds = {
            {Flags.KIND_CLASS, SigCode.CLASS}, {Flags.KIND_INTERFACE, SigCode.CLASS},
            {Flags.KIND_STRUCT, SigCode.VALUETYPE}, {Flags.KIND_REF_STRUCT, SigCode.VALUETYPE},
            {Flags.KIND_RECORD, SigCode.CLASS}, {Flags.KIND_RECORD_STRUCT, SigCode.VALUETYPE},
            {Flags.KIND_ENUM, SigCode.VALUETYPE}, {Flags.KIND_ANNOTATION, SigCode.CLASS},
        };
        for (int[] kind : kinds) {
            boolean value = kind[1] == SigCode.VALUETYPE;
            roundTrip(kindField(kind[0], value));
            unwritable(kindField(kind[0], !value), "field app.Shapes::f: app.K is a " + (value
                    ? "value type, so VALUETYPE" : "reference type, so CLASS") + " names it (BC-03)");
        }
        var types = new Types();
        field(types, new TypeSig.GenericInst(new TypeSig.Named(false, types.shapes), list(INT)),
                "GENERICINST instantiates app.Shapes, which has no type parameters (BC-03)");
        // TypeDef 1 is the class a.A: VALUETYPE a.A, and GENERICINST CLASS a.A <I32>.
        signature(0, "a.A is a reference type, so CLASS names it (BC-03)", 0x20, 0x11, 0x81, 0x80, 0x80, 0x28);
        signature(0, "GENERICINST instantiates a.A, which has no type parameters (BC-03)",
                0x20, 0x12, 0x10, 0x81, 0x80, 0x80, 0x28, 0x01, 0x08);
    }

    @Test
    public static void anIntersectionListsItsClassFirstAndItsInterfacesByName() throws Exception {
        String order = "the interfaces of an INTERSECTION follow by qualified name, each once (GEN-06)";
        var types = new Types();
        field(types, intersection(types.named("Object"), types.named("List"), types.named("Runnable")), null);
        // The first component of another module may be a class, so its order is not checked.
        types = new Types();
        field(types, intersection(types.named("Runnable"), types.named("Object")), null);
        types = new Types();
        field(types, intersection(types.named("Object"), types.named("Runnable"), types.named("List")), order);
        types = new Types();
        field(types, intersection(types.named("Object"), types.list(types.named("String")),
                types.list(types.named("Object"))), order);
        types = new Types();
        field(types, intersection(types.named("Runnable"), new TypeSig.Named(false, types.shapes)),
                "the class of an INTERSECTION is its first component (GEN-06)");
        types = new Types();
        var mover = new TypeDef("app", "Mover", Flags.KIND_INTERFACE, 0, 0, null);
        types.module.types().add(mover);
        field(types, intersection(new TypeSig.Named(false, mover), types.named("Runnable")), null);
        types = new Types();
        var walker = new TypeDef("zoo", "Walker", Flags.KIND_INTERFACE, 0, 0, null);
        types.module.types().add(walker);
        field(types, intersection(new TypeSig.Named(false, walker), types.named("Runnable")), order);
        // The reader: Object & List & Runnable, then the first bytes of the last two tokens swapped.
        types = new Types();
        types.shapes.fields().add(new FieldDef(0, "f", intersection(types.named("Object"), types.named("List"),
                types.named("Runnable"))));
        byte[] file = roundTrip(types.module);
        assertEquals("20 1A 03 10 " + OBJECT + " 10 " + LIST + " 10 " + RUNNABLE,
                Leb128Test.hex(blobBytes(file, TableId.FIELD, 1, 2)));
        int at = blobOffset(file, TableId.FIELD, 1, 2);
        rejected(patchByte(patchByte(file, at + 9, 0x84), at + 14, 0x82), at, order);
    }

    @Test
    public static void theReaderChecksMethodAndMemberRefSignatures() throws Exception {
        var types = new Types();
        types.shapes.methods().add(new MethodDef(Flags.METHOD_ABSTRACT, "m", new MethodSig(0, 0, VOID, list())));
        types.module.memberRefs().add(new MemberRef(types.ref("List"), "get", new MethodSig(
                SigCode.METHOD_STATIC | SigCode.METHOD_GENERIC, 1, VOID, list(new TypeSig.TypeVar(true, 0)))));
        byte[] file = roundTrip(types.module);
        // m is 21 00 00 01, and 0x08 is no METHOD flag.
        int method = blobOffset(file, TableId.METHOD, 1, 2);
        rejected(patchByte(file, method + 1, 0x08), method, "METHOD flags 0x8 are not defined (BC-20)");
        // get is 21 03 01 01 01 14 00; MVAR 1 names no type parameter of a method with one.
        int get = blobOffset(file, TableId.MEMBER_REF, 1, 2);
        rejected(patchByte(file, get + 6, 0x01), get, "MVAR 1 names no type parameter of the method (BC-03)");
    }

    @Test
    public static void theReaderRejectsMalformedSignaturesAtTheirOffset() throws Exception {
        signature(0, "VOID stands outside a return type (BC-03)", 0x20, 0x01);
        signature(1, "code 0xE is not a type (BC-03)", 0x20, 0x0E);
        signature(1, "code 0x1B is not a type (BC-03)", 0x20, 0x1B);
        signature(1, "code 0x20 is not a type (BC-03)", 0x20, 0x20, 0x08);
        signature(0, "a field signature starts with FIELD (BC-03)", 0x21, 0x00, 0x00, 0x01);
        signature(2, "token 0x7000001 does not name a TypeDef or TypeRef row (BC-03)", 0x20, 0x10, 0x81, 0x80, 0x80, 0x38);
        signature(2, "token 0x6000002 does not name a TypeDef or TypeRef row (BC-03)", 0x20, 0x10, 0x82, 0x80, 0x80, 0x30);
        signature(2, "the signature ends before its blob does (BC-03)", 0x20, 0x08, 0x08);
        signature(2, "needs 1 bytes, but 0 remain", 0x20, 0x16);
        signature(2, "GENERICINST instantiates a CLASS or VALUETYPE type (BC-03)", 0x20, 0x12, 0x08, 0x01, 0x08);
        signature(7, "a count of 5 runs past the end of the signature", 0x20, 0x12, 0x10, 0x81, 0x80, 0x80, 0x30, 0x05, 0x08);
        signature(9, "WILDCARD kind 0x3 is not defined (BC-20)", 0x20, 0x12, 0x10, 0x81, 0x80, 0x80, 0x30, 0x01, 0x19, 0x03);
        signature(0, "WILDCARD stands outside a type argument (BC-03)", 0x20, 0x19, 0x00);
        signature(0, NULLABLE, 0x20, 0x15, 0x08);
        signature(0, "VAR 0 names no type parameter of the type (BC-03)", 0x20, 0x13, 0x00);
        signature(0, "BYREF kind 0x3 is not defined (BC-20)", 0x20, 0x18, 0x03, 0x08);
        signature(2, "ULEB128 value is not minimal", 0x20, 0x13, 0x80, 0x00);
        // 255 arrays put the int at depth 256; 254 put it at 255, the limit.
        int[] deep = new int[257];
        deep[0] = 0x20;
        for (int i = 1; i <= 255; i++) {
            deep[i] = 0x16;
        }
        deep[256] = 0x08;
        signature(256, "the signature nests types deeper than 255 (BC-21)", deep);
        int[] deepest = new int[256];
        System.arraycopy(deep, 0, deepest, 0, 255);
        deepest[255] = 0x08;
        ModuleReader.read(fieldFile("String", deepest));
        int[] int32 = {0x20, 0x11, 0x81, 0x80, 0x80, 0x30};
        byte[] file = fieldFile("Int32", int32);
        rejected(file, payload(file, int32.length), "the primitive reflect.core.Int32 is its code, not its struct by name (BC-03)");
    }

    // A module with the TypeRefs of reflect.core, the struct app.Point and the class app.Shapes.
    private static final class Types {
        final ModuleDef module = new ModuleDef("app", 0, 0, 0);
        final TypeDef point = new TypeDef("app", "Point", Flags.KIND_STRUCT, Flags.PUBLIC, 0, null);
        final TypeDef shapes = new TypeDef("app", "Shapes", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);

        Types() {
            var core = new ModuleRef("reflect.core", 0);
            module.requires().add(core);
            for (String name : new String[] {"String", "Runnable", "Object", "List", "Int32"}) {
                module.typeRefs().add(new TypeRef(core, "reflect.core", name));
            }
            module.types().add(shapes);
            module.types().add(point);
        }

        TypeRef ref(String name) {
            for (TypeRef ref : module.typeRefs()) {
                if (ref.name().equals(name)) {
                    return ref;
                }
            }
            throw new AssertionError("no TypeRef " + name);
        }

        TypeSig named(String name) {
            return new TypeSig.Named(false, ref(name));
        }

        TypeSig list(TypeSig... arguments) {
            return new TypeSig.GenericInst(new TypeSig.Named(false, ref("List")), TestModules.list(arguments));
        }
    }

    private static TypeSig nested(int depth) {
        TypeSig type = INT;
        for (int i = 1; i < depth; i++) {
            type = new TypeSig.ArrayOf(type);
        }
        return type;
    }

    private static void field(TypeSig type, String problem) {
        field(new Types(), type, problem);
    }

    // Writes a field of the type; a null problem means the writer accepts it.
    private static void field(Types types, TypeSig type, String problem) {
        types.shapes.fields().add(new FieldDef(0, "f", type));
        if (problem == null) {
            try {
                roundTrip(types.module);
            } catch (ModuleFormatException e) {
                throw new AssertionError(e);
            }
            return;
        }
        unwritable(types.module, "field app.Shapes::f: " + problem);
    }

    private static TypeSig intersection(TypeSig... components) {
        return new TypeSig.Intersection(list(components));
    }

    // A module whose field app.Shapes::f has the type app.K of this kind, as CLASS or VALUETYPE.
    private static ModuleDef kindField(int kind, boolean valueType) {
        var types = new Types();
        var k = new TypeDef("app", "K", kind, 0, kind == Flags.KIND_ENUM ? SigCode.I32 : 0, null);
        types.module.types().add(k);
        types.shapes.fields().add(new FieldDef(0, "f", new TypeSig.Named(valueType, k)));
        return types.module;
    }

    private static void method(MethodSig signature, String problem) {
        var types = new Types();
        types.shapes.methods().add(new MethodDef(Flags.METHOD_ABSTRACT, "m", signature));
        unwritable(types.module, "method app.Shapes::m: " + problem);
    }

    // A module whose field a.A::f has this signature, and whose TypeRef 1 is reflect.core.<name>.
    private static byte[] fieldFile(String name, int... signature) throws ModuleFormatException {
        var idents = new HeapWriter(SectionId.IDENTS);
        int module = idents.ident("m");
        int core = idents.ident("reflect.core");
        int type = idents.ident("A");
        int namespace = idents.ident("a");
        int ref = idents.ident(name);
        int field = idents.ident("f");
        var blobs = new ByteSink();
        blobs.u8(0);
        blobs.uleb(signature.length);
        for (int b : signature) {
            blobs.u8(b);
        }
        byte[] tables = new TestModules.Tables()
                .rows(TableId.MODULE, module, 0, 0, 0, 0, 0)
                .rows(TableId.MODULE_REF, core, 0)
                .rows(TableId.TYPE_DEF, type, namespace, Flags.KIND_CLASS, 0, 0, 0, 0, 1, 1, 1)
                .rows(TableId.TYPE_REF, 0x02000001, core, ref)
                .rows(TableId.FIELD, 0, field, 1)
                .bytes();
        return TestModules.file(idents.toByteArray(), tables, new Section(SectionId.BLOBS, blobs.toByteArray()));
    }

    private static void signature(int at, String message, int... signature) throws ModuleFormatException {
        byte[] file = fieldFile("String", signature);
        rejected(file, payload(file, signature.length) + at, message);
    }

    // A copy of the file with one byte changed and the content hash computed again.
    private static byte[] patchByte(byte[] file, int offset, int value) throws Exception {
        byte[] copy = file.clone();
        copy[offset] = (byte) value;
        return TestModules.rehash(copy);
    }

    // BLOBS holds its empty entry, then the signature after its ULEB128 length.
    private static int payload(byte[] file, int length) throws ModuleFormatException {
        return sectionOffset(file, SectionId.BLOBS) + 1 + Leb128.sizeUnsigned(length);
    }

    static String describe(MethodSig signature) {
        var params = new StringBuilder();
        for (TypeSig param : signature.params()) {
            params.append(params.length() == 0 ? "" : ", ").append(describe(param));
        }
        return describe(signature.returnType()) + " (" + params + ")";
    }

    // The .rfla spelling of a type, with codes for primitives.
    static String describe(TypeSig type) {
        if (type instanceof TypeSig.Primitive primitive) {
            String[] names = {"", "VOID", "BOOLEAN", "I8", "U8", "I16", "U16", "CHAR", "I32", "U32", "I64", "U64", "F32", "F64"};
            return names[primitive.code()];
        }
        if (type instanceof TypeSig.Named named) {
            return (named.valueType() ? "valuetype " : "") + named.type().qualifiedName();
        }
        if (type instanceof TypeSig.GenericInst generic) {
            return describe(generic.type()) + "<" + join(generic.arguments(), ", ") + ">";
        }
        if (type instanceof TypeSig.TypeVar variable) {
            return (variable.method() ? "!!" : "!") + variable.index();
        }
        if (type instanceof TypeSig.NullableRef nullable) {
            return describe(nullable.type()) + "?";
        }
        if (type instanceof TypeSig.ArrayOf array) {
            return describe(array.element()) + "[]";
        }
        if (type instanceof TypeSig.PointerTo pointer) {
            return describe(pointer.element()) + "*";
        }
        if (type instanceof TypeSig.ByRef byRef) {
            return new String[] {"ref", "in", "out"}[byRef.kind()] + " " + describe(byRef.type());
        }
        if (type instanceof TypeSig.Wildcard wildcard) {
            TypeSig bound = wildcard.bound();
            return bound == null ? "?" : (wildcard.kind() == SigCode.WILDCARD_EXTENDS ? "? extends " : "? super ")
                    + describe(bound);
        }
        return "(" + join(((TypeSig.Intersection) type).components(), " & ") + ")";
    }

    private static String join(List<TypeSig> types, String separator) {
        var text = new StringBuilder();
        for (TypeSig type : types) {
            text.append(text.length() == 0 ? "" : separator).append(describe(type));
        }
        return text.toString();
    }
}
