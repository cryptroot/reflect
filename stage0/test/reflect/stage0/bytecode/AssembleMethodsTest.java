package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleMethodsTest {
    private static final String C = ".module m 0.0.0\n.class a.A {\n.method ";
    private static final String END = " {\n}\n}";

    @Test
    public static void methodsGiveTheRowsAndLocalsThatTheyState() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".requires reflect.core\n"
                + ".class public abstract demo.Shape {\n"
                + "    .method protected void new(int sides) {\n"
                + "    }\n"
                + "    .field private int sides\n"
                + "    .method public abstract double area() {\n"
                + "    }\n"
                + "    .method private static void static() {\n"
                + "        .locals (int count, reflect.core.String, demo.Shape? last)\n"
                + "    }\n"
                + "    .class public Builder {\n"
                + "        .method public static demo.Shape/Builder make() {\n"
                + "            .locals ()\n"
                + "        }\n"
                + "    }\n"
                + "    .method public static native reflect.core.String format(scoped this demo.Shape shape, ref int,\n"
                + "            reflect.core.Object?[]... args) {\n"
                + "    }\n"
                + "    .method public final virtual unsafe mutating const precompile synthesized extern int[] all(int,\n"
                + "            out long result) {\n"
                + "    }\n"
                + "}\n";
        var expected = new ModuleDef("demo", 1, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var string = new TypeRef(core, "reflect.core", "String");
        var object = new TypeRef(core, "reflect.core", "Object");
        var shape = new TypeDef("demo", "Shape", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_ABSTRACT, 0, null);
        var builder = new TypeDef("", "Builder", Flags.KIND_CLASS, Flags.PUBLIC, 0, shape);
        TypeSig voidType = primitive(SigCode.VOID);
        TypeSig shapeType = new TypeSig.Named(false, shape);
        MethodDef constructor = method(shape, Flags.PROTECTED | Flags.METHOD_CONSTRUCTOR, "new", 0, voidType,
                primitive(SigCode.I32));
        constructor.params().add(new ParamDef(0, "sides"));
        constructor.setBody(new MethodBody(0));
        shape.fields().add(new FieldDef(Flags.PRIVATE, "sides", primitive(SigCode.I32)));
        method(shape, Flags.PUBLIC | Flags.METHOD_ABSTRACT, "area", 0, primitive(SigCode.F64));
        var locals = new MethodBody(0);
        locals.locals().add(primitive(SigCode.I32));
        locals.locals().add(new TypeSig.Named(false, string));
        locals.locals().add(new TypeSig.NullableRef(shapeType));
        locals.localNames().add(new LocalName(0, "count"));
        locals.localNames().add(new LocalName(2, "last"));
        method(shape, Flags.PRIVATE | Flags.METHOD_STATIC | Flags.METHOD_CONSTRUCTOR, "static", SigCode.METHOD_STATIC,
                voidType).setBody(locals);
        method(builder, Flags.PUBLIC | Flags.METHOD_STATIC, "make", SigCode.METHOD_STATIC,
                new TypeSig.Named(false, builder)).setBody(new MethodBody(0));
        MethodDef format = method(shape, Flags.PUBLIC | Flags.METHOD_STATIC | Flags.METHOD_NATIVE, "format",
                SigCode.METHOD_STATIC | SigCode.METHOD_VARIADIC, new TypeSig.Named(false, string), shapeType,
                new TypeSig.ByRef(SigCode.BYREF_REF, primitive(SigCode.I32)),
                new TypeSig.ArrayOf(new TypeSig.NullableRef(new TypeSig.Named(false, object))));
        format.params().add(new ParamDef(Flags.PARAM_SCOPED | Flags.PARAM_THIS, "shape"));
        format.params().add(new ParamDef(0, ""));
        format.params().add(new ParamDef(0, "args"));
        int every = Flags.PUBLIC | Flags.METHOD_FINAL | Flags.METHOD_VIRTUAL | Flags.METHOD_UNSAFE | Flags.METHOD_MUTATING
                | Flags.METHOD_CONST | Flags.METHOD_PRECOMPILE | Flags.METHOD_SYNTHESIZED | Flags.METHOD_EXTERN;
        MethodDef all = method(shape, every, "all", 0, new TypeSig.ArrayOf(primitive(SigCode.I32)),
                primitive(SigCode.I32), new TypeSig.ByRef(SigCode.BYREF_OUT, primitive(SigCode.I64)));
        all.params().add(new ParamDef(0, ""));
        all.params().add(new ParamDef(0, "result"));
        expected.requires().add(core);
        expected.typeRefs().add(string);
        expected.typeRefs().add(object);
        expected.types().add(shape);
        expected.types().add(builder);
        assertEquals(Leb128Test.hex(ModuleWriter.write(expected)), Leb128Test.hex(assemble(text)));
    }

    @Test
    public static void eachMethodAndItsLocalsHaveTheirDiagnostics() {
        unassemblable(C + "public public void m()" + END, "3:16: RFA0104: public appears twice");
        unassemblable(C + "public private void m()" + END,
                "3:29: RFA0301: method a.A::m has more than one visibility flag (BC-11)");
        String constructor = "a constructor is named new and a static initializer static, so this one is ";
        unassemblable(C + "static void new()" + END, "3:21: RFA0301: method a.A::new: " + constructor + "static (BC-11)");
        unassemblable(C + "void static()" + END, "3:14: RFA0301: method a.A::static: " + constructor + "new (BC-11)");
        unassemblable(C + "void 1()" + END, "3:14: RFA0101: expected the name of the method, found 1");
        unassemblable(C + "void m<T>()" + END, "3:15: RFA0303: the GenericParam table arrives in v0.0.9");
        unassemblable(C + "int m" + END, "3:15: RFA0101: expected (, found {");
        unassemblable(C + "void m()\n}", "4:1: RFA0101: expected {, found }");
        unassemblable(C + "!!0 m()" + END, "3:9: RFA0301: MVAR 0 names no type parameter of the method (BC-03)");
        unassemblable(C + "void m(!0 a)" + END, "3:16: RFA0301: VAR 0 names no type parameter of the type (BC-03)");
        unassemblable(C + "void m(void a)" + END, "3:16: RFA0101: void stands only as a return type (BC-03)");
        unassemblable(C + "void m(scoped scoped int a)" + END, "3:23: RFA0104: scoped appears twice");
        unassemblable(C + "void m(int a.b)" + END, "3:20: RFA0101: expected the name of a parameter, found a.b");
        unassemblable(C + "void m(int a b)" + END, "3:22: RFA0101: expected ), found b");
        unassemblable(C + "void m(int[]... a, int b)" + END, "3:21: RFA0301: only the last parameter is variadic (BC-03)");
        unassemblable(C + "void m(int... a)" + END,
                "3:14: RFA0301: method a.A::m: the last parameter of a VARIADIC signature is an array (BC-03)");
        String body = "; a method has one exactly when it is not ABSTRACT, NATIVE or EXTERN (BC-11)";
        unassemblable(C + "abstract void m() {\n.locals ()\n}\n}", "4:1: RFA0301: method a.A::m has a body" + body);
        unassemblable(C + "native void m() {\n.override a.A::n()\n}\n}",
                "4:1: RFA0303: the Override table arrives in v0.0.6");
        unassemblable(C + "void m() {\n.field int x\n}\n}",
                "4:1: RFA0101: expected an instruction, a label, .locals, .override or }, found .field");
        unassemblable(C + "void m() {\n.locals ()\n.locals (int)\n}\n}", "5:1: RFA0104: the body has one .locals");
        unassemblable(C + "void m() {\n.locals int\n}\n}", "4:9: RFA0101: expected (, found int");
        unassemblable(C + "void m() {\n.locals (int x.y)\n}\n}", "4:14: RFA0101: expected the name of a local, found x.y");
        unassemblable(C + "void m() {\n.locals (!!1)\n}\n}",
                "4:10: RFA0301: MVAR 1 names no type parameter of the method (BC-03)");
    }

    @Test
    public static void parametersAndLocalsHaveTheLimitsOfBc21() throws Exception {
        String most = "int, ".repeat(Limits.MAX_PARAMS - 1) + "int";
        ModuleReader.read(assemble(C + "void m(" + most + ")" + END));
        unassemblable(C + "void m(int, " + most + ")" + END,
                "3:14: RFA0302: method a.A::m has 65536 parameters, more than the limit of 65535 (BC-21)");
        ModuleReader.read(assemble(C + "void m() {\n.locals (" + most + ")\n}\n}"));
        unassemblable(C + "void m() {\n.locals (int, " + most + ")\n}\n}",
                "4:1: RFA0302: the body has 65536 locals, more than the limit of 65535 (BC-21)");
        String longest = "b".repeat(Limits.MAX_IDENTIFIER_BYTES + 1);
        String limit = "RFA0302: an identifier of 65536 UTF-8 bytes exceeds the limit of 65535 (BC-21)";
        unassemblable(C + "void " + longest + "()" + END, "3:14: " + limit);
        unassemblable(C + "void m(int " + longest + ")" + END, "3:20: " + limit);
        unassemblable(C + "void m() {\n.locals (int " + longest + ")\n}\n}", "4:14: " + limit);
    }

    private static MethodDef method(TypeDef type, int flags, String name, int signature, TypeSig returnType,
            TypeSig... params) {
        var method = new MethodDef(flags, name, new MethodSig(signature, 0, returnType, list(params)));
        type.methods().add(method);
        return method;
    }

    private static TypeSig primitive(int code) {
        return new TypeSig.Primitive(code);
    }
}
