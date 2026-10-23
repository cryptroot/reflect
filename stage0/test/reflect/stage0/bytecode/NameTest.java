package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.patchByte;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.section;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class NameTest {
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);
    private static final String IDENT = "not an identifier (BC-11)";
    private static final String QNAME = "not a dotted name of identifiers (BC-11)";

    @Test
    public static void namesHaveTheFormsOfTheLexer() {
        // U+10000 is a letter as a surrogate pair; U+E000 is for private use, and U+200B is ignorable.
        assertEquals("a:II _1:II $:II value$field:II \u00E9t\u00E9:II \uD800\uDC00:II a.b:-Q a.b.c:-Q :-- 1a:-- a b:-- "
                + "a..b:-- .a:-- a.:-- \uE000:-- a\u200Bb:-- \uD800:-- -inf:--", forms("a", "_1", "$", "value$field",
                "\u00E9t\u00E9", "\uD800\uDC00", "a.b", "a.b.c", "", "1a", "a b", "a..b", ".a", "a.", "\uE000", "a\u200Bb",
                "\uD800", "-inf"));
    }

    @Test
    public static void theWriterRejectsNamesOfOtherForms() {
        unwritable(new ModuleDef("a b", 0, 0, 0), "the module name is \"a b\", " + QNAME);
        ModuleDef module = new ModuleDef("m", 0, 0, 0);
        module.requires().add(new ModuleRef("a..b", 0));
        unwritable(module, "the ModuleRef name is \"a..b\", " + QNAME);
        module = new ModuleDef("m", 0, 0, 0);
        module.exports().add(new Export("1a", Flags.EXPORTS, ""));
        unwritable(module, "the Export namespace is \"1a\", " + QNAME);
        module = new ModuleDef("m", 0, 0, 0);
        module.exports().add(new Export("a", Flags.EXPORTS, "b."));
        unwritable(module, "the Export target is \"b.\", " + QNAME);
        unwritable(withType(new TypeDef("a", "", Flags.KIND_CLASS, 0, 0, null)), "the TypeDef name is \"\", " + IDENT);
        unwritable(withType(new TypeDef("a", "B.C", Flags.KIND_CLASS, 0, 0, null)), "the TypeDef name is \"B.C\", " + IDENT);
        unwritable(withType(new TypeDef("a b", "C", Flags.KIND_CLASS, 0, 0, null)),
                "the TypeDef namespace is \"a b\", " + QNAME);
        var core = new ModuleRef("c", 0);
        module = new ModuleDef("m", 0, 0, 0);
        module.requires().add(core);
        module.typeRefs().add(new TypeRef(core, "c", "\uE000"));
        unwritable(module, "the TypeRef name is \"\uE000\", " + IDENT);
        module = new ModuleDef("m", 0, 0, 0);
        module.requires().add(core);
        module.typeRefs().add(new TypeRef(core, "c-d", "E"));
        unwritable(module, "the TypeRef namespace is \"c-d\", " + QNAME);
        TypeDef type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        type.fields().add(new FieldDef(0, "f g", INT));
        unwritable(withType(type), "the Field name is \"f g\", " + IDENT);
        type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        type.methods().add(new MethodDef(Flags.METHOD_ABSTRACT, "m()", new MethodSig(0, 0, VOID, list())));
        unwritable(withType(type), "the Method name is \"m()\", " + IDENT);
        type = new TypeDef("a", "A", Flags.KIND_CLASS, 0, 0, null);
        var method = new MethodDef(Flags.METHOD_ABSTRACT, "m", new MethodSig(0, 0, VOID, list(INT)));
        method.params().add(new ParamDef(0, "p.q"));
        type.methods().add(method);
        unwritable(withType(type), "the Param name is \"p.q\", " + IDENT);
        unwritable(referencing("-x", "l"), "the MemberRef name is \"-x\", " + IDENT);
        unwritable(referencing("x", "l.m"), "method aq.Aq::hq: local name 0: the name of local 0 is \"l.m\", " + IDENT);
    }

    @Test
    public static void emptyNamesStandForTheGlobalNamespaceNoNameAndEveryModule() throws Exception {
        TypeDef type = new TypeDef("", "G", Flags.KIND_CLASS, 0, 0, null);
        var method = new MethodDef(Flags.METHOD_ABSTRACT, "m", new MethodSig(0, 0, VOID, list(INT)));
        method.params().add(new ParamDef(0, ""));
        type.methods().add(method);
        ModuleDef module = withType(type);
        module.exports().add(new Export("a", Flags.EXPORTS, ""));
        ModuleDef read = ModuleReader.read(roundTrip(module));
        assertEquals("G  a ", read.types().get(0).qualifiedName() + " " + read.types().get(0).methods().get(0).params()
                .get(0).name() + " " + read.exports().get(0).namespace() + " " + read.exports().get(0).target());
    }

    @Test
    public static void theReaderRejectsNamesOfOtherForms() throws Exception {
        byte[] file = roundTrip(referencing("xq", "lq"));
        // Each name ends in q, which becomes a dot: a dotted name ends in an empty segment, and a simple
        // name holds a dot.
        reject(file, TableId.MODULE, 1, 0, "module name", "m.", QNAME);
        reject(file, TableId.MODULE_REF, 1, 0, "ModuleRef name", "c.", QNAME);
        reject(file, TableId.EXPORT, 1, 0, "Export namespace", "e.", QNAME);
        reject(file, TableId.EXPORT, 1, 2, "Export target", "t.", QNAME);
        reject(file, TableId.TYPE_DEF, 1, 0, "TypeDef name", "A.", IDENT);
        reject(file, TableId.TYPE_DEF, 1, 1, "TypeDef namespace", "a.", QNAME);
        reject(file, TableId.TYPE_REF, 1, 1, "TypeRef namespace", "n.", QNAME);
        reject(file, TableId.TYPE_REF, 1, 2, "TypeRef name", "R.", IDENT);
        reject(file, TableId.FIELD, 1, 1, "Field name", "f.", IDENT);
        reject(file, TableId.METHOD, 1, 1, "Method name", "h.", IDENT);
        reject(file, TableId.PARAM, 1, 1, "Param name", "p.", IDENT);
        reject(file, TableId.MEMBER_REF, 1, 1, "MemberRef name", "x.", IDENT);
        // DEBUG ends with the name of local 0: its index, its length, then l and q.
        byte[] debug = section(file, SectionId.DEBUG);
        int end = sectionOffset(file, SectionId.DEBUG) + debug.length;
        rejected(patchByte(file, end - 1, '.'), end - 4, "the name of local 0 is \"l.\", " + IDENT);
    }

    // The forms of each name: I when it is an identifier or - when not, then Q when it is a dotted name.
    private static String forms(String... names) {
        var forms = new StringBuilder();
        for (String name : names) {
            forms.append(forms.length() == 0 ? "" : " ").append(name).append(':')
                    .append(Lexer.isIdentifier(name) ? 'I' : '-').append(Lexer.isQualifiedName(name) ? (Lexer.isIdentifier(name)
                    ? 'I' : 'Q') : '-');
        }
        return forms.toString();
    }

    // The name in the column ends in q; the copy of the file with a dot there is rejected at the column.
    private static void reject(byte[] file, int table, int row, int column, String what, String name, String form)
            throws Exception {
        int entry = sectionOffset(file, SectionId.IDENTS) + column(file, table, row, column);
        rejected(patchByte(file, entry + name.length(), '.'), columnOffset(file, table, row, column),
                "the " + what + " is \"" + name + "\", " + form);
    }

    private static ModuleDef withType(TypeDef type) {
        var module = new ModuleDef("m", 0, 0, 0);
        module.types().add(type);
        return module;
    }

    // Module mq requires cq, exports eq to tq, and defines aq.Aq with the field fq and the method hq(int pq),
    // whose local lq holds the static field nq.Rq::x of module cq; the TypeRef nq.Rq is its base. Each name
    // has an IDENTS entry of its own.
    private static ModuleDef referencing(String member, String local) {
        var module = new ModuleDef("mq", 0, 0, 0);
        var required = new ModuleRef("cq", 0);
        module.requires().add(required);
        module.exports().add(new Export("eq", Flags.EXPORTS, "tq"));
        var ref = new TypeRef(required, "nq", "Rq");
        module.typeRefs().add(ref);
        var field = new MemberRef(ref, member, new FieldSig(INT));
        module.memberRefs().add(field);
        TypeDef type = new TypeDef("aq", "Aq", Flags.KIND_CLASS, 0, 0, null);
        type.setBase(ref);
        type.fields().add(new FieldDef(0, "fq", INT));
        var method = new MethodDef(Flags.METHOD_STATIC, "hq", new MethodSig(SigCode.METHOD_STATIC, 0, VOID, list(INT)));
        method.params().add(new ParamDef(0, "pq"));
        var body = new MethodBody(1);
        body.locals().add(INT);
        body.localNames().add(new LocalName(0, local));
        body.code().add(new Instruction.Field(Opcode.LDSFLD, field));
        body.code().add(new Instruction.Index(Opcode.STLOC, 0));
        body.code().add(new Instruction.Plain(Opcode.RET));
        method.setBody(body);
        type.methods().add(method);
        module.types().add(type);
        return module;
    }
}
