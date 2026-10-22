package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * The assembler rfla (README §7.4): reads .rfla text (§8.1) and writes the module that the text
 * states, or reports the first defect with its code, line and column (Q79). The text gives the
 * signature that each row of another module holds, so rfla reads no other module (Q69). rfla checks
 * each rule of the format at the place in the text that breaks it, with the checks that the writer
 * uses; what the writer then rejects is a limit of the whole module, reported at .module.
 *
 * A type may name a type that the text defines later, and code a member that it defines later, so
 * rfla reads the text three times: first the module directives and the head of each .class, which
 * declare the types, then the members, then the bodies of the methods.
 */
public final class Assembler {
    private static final String[] REQUIRES_WORDS = {"transitive", "static"};
    private static final int[] REQUIRES_FLAGS = {Flags.REQUIRES_TRANSITIVE, Flags.REQUIRES_STATIC};
    private static final String[] TYPE_WORDS = {"public", "protected", "private", "abstract", "final", "sealed",
        "unsealed"};
    private static final int[] TYPE_FLAGS = {Flags.PUBLIC, Flags.PROTECTED, Flags.PRIVATE, Flags.TYPE_ABSTRACT,
        Flags.TYPE_FINAL, Flags.TYPE_SEALED, Flags.TYPE_UNSEALED};
    private static final String[] FIELD_WORDS = {"public", "protected", "private", "static", "final", "const",
        "backing"};
    private static final int[] FIELD_FLAGS = {Flags.PUBLIC, Flags.PROTECTED, Flags.PRIVATE, Flags.FIELD_STATIC,
        Flags.FIELD_FINAL, Flags.FIELD_CONST, Flags.FIELD_BACKING};
    private static final String[] METHOD_WORDS = {"public", "protected", "private", "static", "virtual", "abstract",
        "final", "native", "extern", "unsafe", "mutating", "const", "precompile", "synthesized"};
    private static final int[] METHOD_FLAGS = {Flags.PUBLIC, Flags.PROTECTED, Flags.PRIVATE, Flags.METHOD_STATIC,
        Flags.METHOD_VIRTUAL, Flags.METHOD_ABSTRACT, Flags.METHOD_FINAL, Flags.METHOD_NATIVE, Flags.METHOD_EXTERN,
        Flags.METHOD_UNSAFE, Flags.METHOD_MUTATING, Flags.METHOD_CONST, Flags.METHOD_PRECOMPILE,
        Flags.METHOD_SYNTHESIZED};
    private static final String[] PARAM_WORDS = {"scoped", "this"};
    private static final int[] PARAM_FLAGS = {Flags.PARAM_SCOPED, Flags.PARAM_THIS};

    private final Parser parser;
    private final ModuleDef module;
    private final Token moduleName;
    private final Map<String, ModuleRef> requires = new HashMap<>();
    private final Map<Export, Boolean> exports = new HashMap<>();
    private final Map<String, TypeDef> types = new HashMap<>();
    private final Map<String, TypeRef> typeRefs = new HashMap<>();
    private final Map<MemberKey, MemberRef> memberRefs = new HashMap<>();
    private final List<Declared> classes = new ArrayList<>();
    private final List<Body> bodies = new ArrayList<>();
    private final List<Kept> sections = new ArrayList<>();
    private @Nullable TypeName main;

    private Assembler(Parser parser, ModuleDef module, Token moduleName) {
        this.parser = parser;
        this.module = module;
        this.moduleName = moduleName;
    }

    /** The module that UTF-8 .rfla text states, as the writer gives it. */
    public static byte[] assemble(byte[] text) throws AssemblyException {
        var parser = new Parser(Lexer.tokens(text));
        if (!parser.atDirective(".module")) {
            throw parser.unexpected(".module");
        }
        parser.next();
        Token name = parser.qualifiedName("the name of the module");
        int[] version = parser.version();
        var assembler = new Assembler(parser, new ModuleDef(name.text(), version[0], version[1], version[2]), name);
        assembler.identifier(name, name.text());
        assembler.directives();
        assembler.resolveMain();
        assembler.members();
        assembler.bodies();
        return assembler.write();
    }

    // The directives after .module, in any order (§8.1).
    private void directives() throws AssemblyException {
        while (!parser.atKind(Token.END)) {
            if (parser.atDirective(".main")) {
                main();
            } else if (parser.atDirective(".requires")) {
                requires();
            } else if (parser.atDirective(".exports")) {
                exports(Flags.EXPORTS);
            } else if (parser.atDirective(".opens")) {
                exports(Flags.OPENS);
            } else if (parser.atDirective(".unsafe")) {
                unsafe();
            } else if (parser.atDirective(".section")) {
                section();
            } else if (parser.atDirective(".class")) {
                declare(null);
            } else {
                throw parser.unexpected("a directive of the module");
            }
        }
    }

    // The main class is a type of the module, which may come later in the text.
    private void main() throws AssemblyException {
        Token directive = parser.next();
        if (main != null) {
            throw Parser.error(Diagnostic.REPEATED, directive, "the module names one main class");
        }
        main = parser.typeName("the name of the main class");
    }

    private void resolveMain() throws AssemblyException {
        @Nullable TypeName name = main;
        if (name == null) {
            return;
        }
        @Nullable TypeDef type = types.get(name.qualified());
        if (type == null) {
            throw Parser.error(Diagnostic.NO_TYPE, name.token(), "the module defines no type " + name.qualified());
        }
        module.setMain(type);
    }

    private void requires() throws AssemblyException {
        parser.next();
        int flags = flags(REQUIRES_WORDS, REQUIRES_FLAGS);
        Token name = parser.qualifiedName("the name of a required module");
        identifier(name, name.text());
        if (requires.containsKey(name.text())) {
            throw Parser.error(Diagnostic.FORMAT, name, "two ModuleRef rows have the name " + name.text() + " (BC-24)");
        }
        var ref = new ModuleRef(name.text(), flags);
        requires.put(name.text(), ref);
        module.requires().add(ref);
    }

    // One Export row per target, or one for every module without targets (§3.1).
    private void exports(int kind) throws AssemblyException {
        parser.next();
        Token namespace = parser.qualifiedName("a namespace");
        identifier(namespace, namespace.text());
        if (!parser.accept("to")) {
            export(new Export(namespace.text(), kind, ""), namespace);
            return;
        }
        do {
            Token target = parser.qualifiedName("the name of a module");
            identifier(target, target.text());
            export(new Export(namespace.text(), kind, target.text()), target);
        } while (parser.accept(","));
    }

    private void export(Export export, Token at) throws AssemblyException {
        if (exports.containsKey(export)) {
            throw Parser.error(Diagnostic.FORMAT, at, "two Export rows have the key of " + ModuleWriter.describe(export)
                    + " (BC-24)");
        }
        exports.put(export, true);
        module.exports().add(export);
    }

    private void unsafe() throws AssemblyException {
        Token directive = parser.next();
        if (module.unsafe()) {
            throw Parser.error(Diagnostic.REPEATED, directive, "the module is unsafe once");
        }
        module.setUnsafe(true);
    }

    // An unknown ancillary section, or a stale DEBUG, kept as bytes (BC-20).
    private void section() throws AssemblyException {
        Token directive = parser.next();
        Token at = parser.peek();
        int id = parser.u32("a section ID");
        byte[] content = parser.hex();
        String name = "section " + ModuleFormatException.hex(id & 0xFFFFFFFFL);
        if (!SectionId.isAncillary(id)) {
            throw Parser.error(Diagnostic.FORMAT, at, name + " is not ancillary; .section keeps an ancillary section (BC-20)");
        }
        if (content.length == 0) {
            throw Parser.error(Diagnostic.FORMAT, at, name + " has no content (BC-24)");
        }
        for (Kept kept : sections) {
            if (kept.section().id() == id) {
                throw Parser.error(Diagnostic.FORMAT, at, "two sections have the ID " + ModuleFormatException.hex(id
                        & 0xFFFFFFFFL) + " (BC-02)");
            }
        }
        sections.add(new Kept(directive, new Section(id, content)));
    }

    // The head of a .class declares its type, and the types nested in it; the base type, the
    // interfaces and the members wait until every type is declared. A nested .class names only
    // itself (BC-08).
    private void declare(@Nullable TypeDef enclosing) throws AssemblyException {
        parser.next();
        int flags = flags(TYPE_WORDS, TYPE_FLAGS);
        int kind = kind();
        TypeName name = parser.typeName("the name of the type");
        if (!name.nested().isEmpty() || enclosing != null && name.token().text().indexOf('.') >= 0) {
            throw Parser.error(Diagnostic.SYNTAX, name.token(), enclosing == null ? "a nested type is declared inside"
                    + " the type that encloses it (BC-08)" : "a nested type names only itself (BC-08)");
        }
        if (parser.at("<")) {
            throw Parser.error(Diagnostic.LATER, parser.peek(), "the GenericParam table arrives in v0.0.9");
        }
        int backing = parser.accept(":") ? backing(parser.type("the backing type of the enum")) : 0;
        @Nullable TypeSyntax base = parser.accept("extends") ? parser.type("the base type") : null;
        var interfaces = new ArrayList<TypeSyntax>();
        if (parser.accept("implements")) {
            do {
                interfaces.add(parser.type("an interface"));
            } while (parser.accept(","));
        }
        parser.expect("{");
        String namespace = enclosing == null ? name.namespace() : "";
        var type = new TypeDef(namespace, name.name(), kind, flags, backing, enclosing);
        identifier(name.token(), namespace);
        identifier(name.token(), name.name());
        check(name.token(), ModuleWriter.typeProblem(type));
        if (types.containsKey(type.qualifiedName())) {
            throw Parser.error(Diagnostic.FORMAT, name.token(), "two TypeDef rows have the key of "
                    + type.qualifiedName() + " (BC-24)");
        }
        types.put(type.qualifiedName(), type);
        module.types().add(type);
        var declared = new Declared(type, base, interfaces, new ArrayList<>());
        classes.add(declared);
        while (!parser.at("}")) {
            if (parser.atDirective(".field") || parser.atDirective(".method")) {
                declared.members().add(parser.position());
                skipMember();
            } else if (parser.atDirective(".class")) {
                declare(type);
            } else {
                throw parser.unexpected(".field, .method, .class or }");
            }
        }
        parser.next();
    }

    private int kind() throws AssemblyException {
        if (parser.accept("interface")) {
            return Flags.KIND_INTERFACE;
        }
        if (parser.accept("struct")) {
            return Flags.KIND_STRUCT;
        }
        if (parser.accept("ref")) {
            parser.expect("struct");
            return Flags.KIND_REF_STRUCT;
        }
        if (parser.accept("record")) {
            return parser.accept("struct") ? Flags.KIND_RECORD_STRUCT : Flags.KIND_RECORD;
        }
        if (parser.accept("enum")) {
            return Flags.KIND_ENUM;
        }
        return parser.accept("annotation") ? Flags.KIND_ANNOTATION : Flags.KIND_CLASS;
    }

    // An integral primitive, by keyword or by its struct (DAT-05).
    private int backing(TypeSyntax syntax) throws AssemblyException {
        int code = syntax instanceof TypeSyntax.Primitive primitive ? primitive.code()
                : syntax instanceof TypeSyntax.Named named ? primitiveStruct(named) : 0;
        if (!ModuleWriter.integral(code)) {
            throw Parser.error(Diagnostic.FORMAT, syntax.token(), "the backing type of an enum is one of I8, U8, I16, U16,"
                    + " I32, U32, I64 and U64 (DAT-05)");
        }
        return code;
    }

    // Skips a member, which the second reading reads; it stops where the next member could start,
    // after the body of a method.
    private void skipMember() {
        boolean method = parser.atDirective(".method");
        parser.next();
        while (!parser.atKind(Token.END) && !parser.atKind(Token.DIRECTIVE) && !parser.at("{") && !parser.at("}")) {
            parser.next();
        }
        if (method && parser.accept("{")) {
            while (!parser.atKind(Token.END) && !parser.accept("}")) {
                parser.next();
            }
        }
    }

    // The second reading: base types, interfaces and members, once every type is declared.
    private void members() throws AssemblyException {
        for (Declared declared : classes) {
            TypeDef type = declared.type();
            @Nullable TypeSyntax base = declared.base();
            if (base != null) {
                type.setBase(tokenType(base));
            }
            for (TypeSyntax syntax : declared.interfaces()) {
                TypeHandle implemented = tokenType(syntax);
                check(syntax.token(), ModuleWriter.interfaceProblem(type, implemented));
                for (TypeHandle earlier : type.interfaces()) {
                    if (earlier == implemented) {
                        throw Parser.error(Diagnostic.FORMAT, syntax.token(), "type " + type.qualifiedName()
                                + " implements " + implemented.qualifiedName() + " twice (BC-24)");
                    }
                }
                type.interfaces().add(implemented);
            }
            for (int position : declared.members()) {
                parser.seek(position);
                if (parser.atDirective(".method")) {
                    method(type);
                } else {
                    field(type);
                    if (!parser.atKind(Token.DIRECTIVE) && !parser.at("}")) {
                        throw parser.unexpected(".field, .method, .class or }");
                    }
                }
            }
        }
    }

    private void field(TypeDef type) throws AssemblyException {
        parser.next();
        int flags = flags(FIELD_WORDS, FIELD_FLAGS);
        TypeSig fieldType = definition(parser.type("the type of the field"), Signatures.OTHER);
        Token name = parser.identifier("the name of the field");
        identifier(name, name.text());
        if (parser.at("=")) {
            throw Parser.error(Diagnostic.LATER, parser.peek(), "the Constant table arrives in v0.0.6");
        }
        var field = new FieldDef(flags, name.text(), fieldType);
        check(name, ModuleWriter.fieldProblem(field, "field " + type.qualifiedName() + "::" + name.text(), module.name()));
        type.fields().add(field);
    }

    // A method named new or static is a constructor or a static initializer (§3.1), and ... after
    // the type of its last parameter makes it variadic (MEM-10).
    private void method(TypeDef type) throws AssemblyException {
        parser.next();
        int flags = flags(METHOD_WORDS, METHOD_FLAGS);
        TypeSig returnType = definition(parser.returnType("the return type"), Signatures.RETURN);
        Token name = parser.identifier("the name of the method");
        identifier(name, name.text());
        if (name.text().equals("new") || name.text().equals("static")) {
            flags |= Flags.METHOD_CONSTRUCTOR;
        }
        if (parser.at("<")) {
            throw Parser.error(Diagnostic.LATER, parser.peek(), "the GenericParam table arrives in v0.0.9");
        }
        parser.expect("(");
        int signatureFlags = (flags & Flags.METHOD_STATIC) != 0 ? SigCode.METHOD_STATIC : 0;
        var types = new ArrayList<TypeSig>();
        var params = new ArrayList<ParamDef>();
        @Nullable Token variadic = null;
        if (!parser.at(")")) {
            do {
                if (variadic != null) {
                    throw Parser.error(Diagnostic.FORMAT, variadic, "only the last parameter is variadic (BC-03)");
                }
                int paramFlags = flags(PARAM_WORDS, PARAM_FLAGS);
                types.add(definition(parser.type("the type of a parameter"), Signatures.OTHER));
                if (parser.at("...")) {
                    variadic = parser.next();
                    signatureFlags |= SigCode.METHOD_VARIADIC;
                }
                params.add(new ParamDef(paramFlags, optionalName("the name of a parameter")));
            } while (parser.accept(","));
        }
        parser.expect(")");
        parser.expect("{");
        var method = new MethodDef(flags, name.text(), new MethodSig(signatureFlags, 0, returnType, types));
        for (ParamDef param : params) {
            method.params().add(param);
        }
        method.setBody(Code.hasBody(flags) ? new MethodBody(0) : null);
        String owner = "method " + type.qualifiedName() + "::" + name.text();
        check(name, ModuleWriter.methodProblem(method, owner, module.name()));
        type.methods().add(method);
        bodies.add(new Body(method, owner, parser.position()));
    }

    // The third reading: the bodies, once every member is declared, so code may name any of them.
    private void bodies() throws AssemblyException {
        for (Body pending : bodies) {
            parser.seek(pending.position());
            body(pending.method(), pending.owner());
        }
    }

    // The items of a body up to its }; a method without a body has none. Labels and the names of
    // arguments and locals resolve once the body is read, since a label or .locals may follow a use.
    private void body(MethodDef method, String owner) throws AssemblyException {
        var text = new Text(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        boolean locals = false;
        @Nullable Token maxStack = null;
        while (!parser.at("}")) {
            Token at = parser.peek();
            MethodBody body = method.body();
            if (parser.atDirective(".override")) {
                throw Parser.error(Diagnostic.LATER, at, "the Override table arrives in v0.0.6");
            } else if (body == null) {
                // The writer's message, at the first item of a body that the method cannot have.
                method.setBody(new MethodBody(0));
                check(at, ModuleWriter.methodProblem(method, owner, module.name()));
            } else if (parser.atDirective(".locals")) {
                if (locals) {
                    throw Parser.error(Diagnostic.REPEATED, at, "the body has one .locals");
                }
                locals = true;
                locals(body);
            } else if (parser.atDirective(".maxstack")) {
                if (maxStack != null) {
                    throw Parser.error(Diagnostic.REPEATED, at, "the body has one .maxstack");
                }
                parser.next();
                maxStack = parser.peek();
                body.setMaxStack(parser.u32("a stack depth"));
                check(maxStack, Code.stackProblem(body.maxStack()));
            } else if (parser.atDirective(".try")) {
                region(body, text);
            } else if (parser.atKind(Token.WORD) && parser.peekSecond().is(":")) {
                Label label = label(text, parser.identifier("a label").text());
                parser.next();
                if (label.body() != null) {
                    throw Parser.error(Diagnostic.REPEATED, at, "the label " + at.text() + " marks two places");
                }
                label.mark(body, body.code().size());
            } else if (parser.atKind(Token.WORD)) {
                instruction(body, text);
            } else {
                throw parser.unexpected("an instruction, a label, a directive of a body or }");
            }
        }
        parser.next();
        MethodBody body = method.body();
        if (body != null) {
            resolve(method, body, text);
            if (maxStack == null) {
                body.setMaxStack(depth(method.signature(), body, text.mnemonics()));
            }
        }
    }

    // Region of §8.1: a try and a handler by their labels, which the body marks before or after it.
    private void region(MethodBody body, Text text) throws AssemblyException {
        Token directive = parser.next();
        Label tryStart = target(text);
        Label tryEnd = target(text);
        int kind = Flags.REGION_FINALLY;
        @Nullable TypeHandle type = null;
        if (parser.atDirective(".catch")) {
            parser.next();
            kind = Flags.REGION_CATCH;
            type = tokenType(parser.type("the type that the handler catches"));
        } else if (parser.atDirective(".finally")) {
            parser.next();
        } else {
            throw parser.unexpected(".catch or .finally");
        }
        Label handlerStart = target(text);
        var region = new Region(kind, tryStart, tryEnd, handlerStart, target(text), type);
        for (Region earlier : body.regions()) {
            if (earlier.equals(region)) {
                throw Parser.error(Diagnostic.FORMAT, directive, "two exception regions are equal (BC-18)");
            }
        }
        body.regions().add(region);
        text.regions().add(directive);
    }

    // Marks the labels and names of the body, then checks each instruction at its mnemonic with the
    // rules of the writer.
    private void resolve(MethodDef method, MethodBody body, Text text) throws AssemblyException {
        for (Token use : text.uses()) {
            if (label(text, use.text()).body() == null) {
                throw Parser.error(Diagnostic.NO_LABEL, use, "the body marks no label " + use.text());
            }
        }
        int arguments = Code.arguments(method.signature());
        List<Instruction> code = body.code();
        for (int i = 0; i < code.size(); i++) {
            @Nullable Token name = text.names().get(i);
            if (name != null) {
                int opcode = code.get(i).opcode();
                code.set(i, new Instruction.Index(opcode, variable(method, body, opcode, name)));
            }
            String problem = Code.problem(code.get(i), arguments, body.locals().size());
            check(text.mnemonics().get(i), problem != null ? problem : Code.targetProblem(code.get(i), body));
        }
        for (int i = 0; i < body.regions().size(); i++) {
            check(text.regions().get(i), Regions.problem(body.regions().get(i), body));
        }
        Regions.Crossing crossing = Regions.crossing(body.regions());
        if (crossing != null) {
            check(text.regions().get(crossing.region()), Regions.crossingProblem(crossing));
        }
    }

    // The number of the argument or local that has the name: a parameter, which follows this unless the
    // method is static, or a local that .locals names. Exactly one may have it.
    private static int variable(MethodDef method, MethodBody body, int opcode, Token name) throws AssemblyException {
        boolean argument = Code.namesArgument(opcode);
        int count = 0;
        int index = -1;
        if (argument) {
            int first = Code.arguments(method.signature()) - method.params().size();
            for (int i = 0; i < method.params().size(); i++) {
                if (method.params().get(i).name().equals(name.text())) {
                    count++;
                    index = first + i;
                }
            }
        } else {
            for (LocalName local : body.localNames()) {
                if (local.name().equals(name.text())) {
                    count++;
                    index = local.index();
                }
            }
        }
        if (count != 1) {
            throw Parser.error(Diagnostic.NO_VARIABLE, name, (argument ? "the method has " : "the body has ")
                    + (count == 0 ? "no " : "more than one ") + (argument ? "argument" : "local") + " named " + name.text());
        }
        return index;
    }

    // The maximum stack depth along every path from the first instruction, at depth 0, and from each
    // handler, at 1 for a catch, which holds its exception, and 0 for a finally, through the Stack column
    // of §5.1 (Q77). Two paths into one instruction bring one depth, and code that no path reaches does
    // not count.
    private static int depth(MethodSig signature, MethodBody body, List<Token> mnemonics) throws AssemblyException {
        List<Instruction> code = body.code();
        int[] depths = new int[code.size()];
        for (int i = 1; i < depths.length; i++) {
            depths[i] = -1;
        }
        int[] work = new int[code.size()];
        // work[0] is the first instruction, at depth 0.
        int pending = code.isEmpty() ? 0 : 1;
        int max = 0;
        for (Region region : body.regions()) {
            int start = region.handlerStart().index();
            int depth = region.kind() == Flags.REGION_CATCH ? 1 : 0;
            if (depths[start] < 0) {
                depths[start] = depth;
                work[pending++] = start;
                max = Math.max(max, depth);
            }
            agree(depths[start], depth, code.get(start), mnemonics.get(start));
        }
        while (pending > 0) {
            int i = work[--pending];
            Instruction instruction = code.get(i);
            long pops = pops(instruction, signature, depths[i]);
            if (pops > depths[i]) {
                throw Parser.error(Diagnostic.STACK, mnemonics.get(i), Opcode.mnemonic(instruction.opcode())
                        + " needs a stack depth of " + pops + ", but it is " + depths[i] + " (Q77)");
            }
            int after = depths[i] - (int) pops + pushes(instruction);
            check(mnemonics.get(i), Code.stackProblem(after));
            max = Math.max(max, after);
            for (int next : successors(instruction, i, code.size())) {
                if (depths[next] < 0) {
                    depths[next] = after;
                    work[pending++] = next;
                }
                agree(depths[next], after, code.get(next), mnemonics.get(next));
            }
        }
        return max;
    }

    // Two paths into one instruction bring one depth (Q77).
    private static void agree(int depth, int other, Instruction instruction, Token at) throws AssemblyException {
        if (depth != other) {
            throw Parser.error(Diagnostic.STACK, at, "two paths reach " + Opcode.mnemonic(instruction.opcode())
                    + " with stack depths " + depth + " and " + other + " (Q77)");
        }
    }

    // The instructions that may follow: the targets of a branch or a switch, then the next instruction
    // unless this one does not go on.
    private static List<Integer> successors(Instruction instruction, int i, int size) {
        var next = new ArrayList<Integer>();
        if (instruction instanceof Instruction.Branch branch) {
            next.add(branch.target().index());
        } else if (instruction instanceof Instruction.Switch choice) {
            for (Label target : choice.targets()) {
                next.add(target.index());
            }
        }
        int opcode = instruction.opcode();
        boolean stops = opcode == Opcode.BR || opcode == Opcode.LEAVE || opcode == Opcode.RET || opcode == Opcode.THROW
                || opcode == Opcode.RETHROW || opcode == Opcode.ENDFINALLY;
        if (!stops && i + 1 < size) {
            next.add(i + 1);
        }
        return next;
    }

    // The values that the instruction pops: a number of §5.1, or what the method that it names, the method
    // that holds it, the stack or its second operand gives.
    private static long pops(Instruction instruction, MethodSig holder, int depth) {
        int pops = Opcode.pops(instruction.opcode());
        if (pops >= 0) {
            return pops;
        }
        if (pops == Opcode.STACK_ALL) {
            return depth;
        }
        if (pops == Opcode.STACK_RETURN) {
            return returns(holder) ? 1 : 0;
        }
        if (pops == Opcode.STACK_COUNT) {
            return ((Instruction.Indexes) instruction).second() & 0xFFFFFFFFL;
        }
        MethodSig called = called(instruction);
        int handle = pops == Opcode.STACK_CLASS_ARGS || pops == Opcode.STACK_CLASS_PARAMS ? 1 : 0;
        return handle + (pops == Opcode.STACK_ARGS || pops == Opcode.STACK_CLASS_ARGS ? Code.arguments(called)
                : called.params().size());
    }

    private static int pushes(Instruction instruction) {
        int pushes = Opcode.pushes(instruction.opcode());
        return pushes >= 0 ? pushes : returns(called(instruction)) ? 1 : 0;
    }

    // The signature of the method that a call or newobj names.
    private static MethodSig called(Instruction instruction) {
        MethodHandle method = instruction instanceof Instruction.Constrained constrained ? constrained.method()
                : ((Instruction.Method) instruction).method();
        return method instanceof MethodDef def ? def.signature() : (MethodSig) ((MemberRef) method).signature();
    }

    private static boolean returns(MethodSig signature) {
        return !(signature.returnType() instanceof TypeSig.Primitive primitive && primitive.code() == SigCode.VOID);
    }

    // Instruction of §8.1: a mnemonic, then the operands of its operand kind (§5.1).
    private void instruction(MethodBody body, Text text) throws AssemblyException {
        Token mnemonic = parser.next();
        int opcode = Opcode.code(mnemonic.text());
        if (opcode < 0) {
            throw Parser.error(Diagnostic.NO_INSTRUCTION, mnemonic, "no instruction is named " + mnemonic.text());
        }
        int kind = Opcode.operands(opcode);
        @Nullable Token name = null;
        Instruction instruction;
        if (kind == Opcode.OPERAND_NONE) {
            instruction = new Instruction.Plain(opcode);
        } else if (kind == Opcode.OPERAND_I4) {
            instruction = new Instruction.Value32(opcode, parser.i32("a 32-bit integer"));
        } else if (kind == Opcode.OPERAND_I8) {
            instruction = new Instruction.Value64(opcode, parser.i64("a 64-bit integer"));
        } else if (kind == Opcode.OPERAND_R4) {
            instruction = new Instruction.Value32(opcode, parser.r4("a float"));
        } else if (kind == Opcode.OPERAND_R8) {
            instruction = new Instruction.Value64(opcode, parser.r8("a double"));
        } else if (kind == Opcode.OPERAND_STR) {
            instruction = new Instruction.Literal(opcode, parser.string("a string"));
        } else if (kind == Opcode.OPERAND_N) {
            name = parser.atKind(Token.WORD) ? parser.identifier("an argument or a local") : null;
            instruction = new Instruction.Index(opcode, name == null ? parser.u32("an argument or a local") : 0);
        } else if (kind == Opcode.OPERAND_N_N) {
            int number = parser.u32("a call number");
            instruction = new Instruction.Indexes(opcode, number, parser.u32("an argument count"));
        } else if (kind == Opcode.OPERAND_TOK) {
            int names = Code.tokenKind(opcode);
            instruction = names == Code.FIELD ? new Instruction.Field(opcode, fieldRef())
                    : names == Code.METHOD ? new Instruction.Method(opcode, methodRef())
                    : new Instruction.Type(opcode, tokenType(parser.type("a type")));
        } else if (kind == Opcode.OPERAND_TOK_TOK) {
            TypeHandle type = tokenType(parser.type("a type"));
            instruction = new Instruction.Constrained(opcode, type, methodRef());
        } else if (kind == Opcode.OPERAND_OFF) {
            instruction = new Instruction.Branch(opcode, target(text));
        } else {
            parser.expect("(");
            var targets = new ArrayList<Label>();
            if (!parser.at(")")) {
                do {
                    targets.add(target(text));
                } while (parser.accept(","));
            }
            parser.expect(")");
            instruction = new Instruction.Switch(opcode, targets);
        }
        body.code().add(instruction);
        text.mnemonics().add(mnemonic);
        text.names().add(name);
    }

    // The label that a branch, a switch or a region names; the body marks it before or after the use.
    private Label target(Text text) throws AssemblyException {
        Token name = parser.identifier("a label");
        text.uses().add(name);
        return label(text, name.text());
    }

    private static Label label(Text text, String name) {
        @Nullable Label label = text.labels().get(name);
        if (label == null) {
            label = new Label();
            text.labels().put(name, label);
        }
        return label;
    }

    // FieldRef of §8.1: the type of the field, which the short form leaves out for a field of the module
    // (Q69), the type that holds the field, and its name.
    private FieldHandle fieldRef() throws AssemblyException {
        TypeSyntax first = parser.type("a field");
        @Nullable TypeSig type = null;
        TypeSyntax holder = first;
        if (!parser.at("::")) {
            type = reference(first, Signatures.OTHER);
            holder = parser.type("the type that holds the field");
        }
        TypeHandle parent = tokenType(holder);
        parser.expect("::");
        Token name = parser.identifier("the name of a field");
        if (parent instanceof TypeDef def) {
            for (FieldDef field : def.fields()) {
                if (field.name().equals(name.text()) && (type == null || type.equals(field.type()))) {
                    return field;
                }
            }
            throw Parser.error(Diagnostic.NO_MEMBER, name, "the module defines no field " + def.qualifiedName() + "::"
                    + name.text() + (type == null ? "" : " of this type"));
        }
        if (type == null) {
            throw Parser.error(Diagnostic.SYNTAX, first.token(), "a field of another module states its type (Q69)");
        }
        identifier(name, name.text());
        return memberRef(parent, name.text(), new FieldSig(type));
    }

    // MethodRef of §8.1: static and the return type, which the short form leaves out for a method of the
    // module (Q69), the type that holds the method, its name and its parameter types.
    private MethodHandle methodRef() throws AssemblyException {
        boolean isStatic = parser.accept("static");
        TypeSyntax first = parser.returnType("a method");
        @Nullable TypeSig returnType = null;
        TypeSyntax holder = first;
        if (isStatic || !parser.at("::")) {
            returnType = reference(first, Signatures.RETURN);
            holder = parser.type("the type that holds the method");
        }
        TypeHandle parent = tokenType(holder);
        parser.expect("::");
        Token name = parser.identifier("the name of a method");
        if (parser.at("<")) {
            throw Parser.error(Diagnostic.LATER, parser.peek(), "the MethodSpec table arrives in v0.0.9");
        }
        parser.expect("(");
        int flags = isStatic ? SigCode.METHOD_STATIC : 0;
        var params = new ArrayList<TypeSig>();
        if (!parser.at(")")) {
            do {
                params.add(reference(parser.type("the type of a parameter"), Signatures.OTHER));
            } while (parser.accept(","));
            if (parser.accept("...")) {
                flags |= SigCode.METHOD_VARIADIC;
            }
        }
        parser.expect(")");
        if (parent instanceof TypeDef def) {
            @Nullable MethodSig signature = returnType == null ? null : new MethodSig(flags, 0, returnType, params);
            for (MethodDef method : def.methods()) {
                boolean same = signature == null ? method.signature().params().equals(params)
                        : method.signature().equals(signature);
                if (method.name().equals(name.text()) && same) {
                    return method;
                }
            }
            throw Parser.error(Diagnostic.NO_MEMBER, name, "the module defines no method " + def.qualifiedName() + "::"
                    + name.text() + (signature == null ? " with these parameter types" : " with this signature"));
        }
        if (returnType == null) {
            throw Parser.error(Diagnostic.SYNTAX, first.token(), "a method of another module states its return type (Q69)");
        }
        var signature = new MethodSig(flags, 0, returnType, params);
        check(name, Signatures.problem(signature, ModuleWriter.REFERENCE, module.name()));
        identifier(name, name.text());
        return memberRef(parent, name.text(), signature);
    }

    // One MemberRef row per parent, name and signature (BC-24).
    private MemberRef memberRef(TypeHandle parent, String name, MemberSig signature) {
        var key = new MemberKey(parent, name, signature);
        @Nullable MemberRef ref = memberRefs.get(key);
        if (ref == null) {
            ref = new MemberRef(parent, name, signature);
            memberRefs.put(key, ref);
            module.memberRefs().add(ref);
        }
        return ref;
    }

    // The types of the locals, and the names that DEBUG holds (§6.1).
    private void locals(MethodBody body) throws AssemblyException {
        Token directive = parser.next();
        parser.expect("(");
        if (!parser.at(")")) {
            do {
                TypeSig type = definition(parser.type("the type of a local"), Signatures.OTHER);
                String name = optionalName("the name of a local");
                if (!name.isEmpty()) {
                    body.localNames().add(new LocalName(body.locals().size(), name));
                }
                body.locals().add(type);
            } while (parser.accept(","));
        }
        parser.expect(")");
        if (!body.locals().isEmpty()) {
            check(directive, Signatures.localsProblem(body.locals(), ModuleWriter.DEFINITION, module.name()));
        }
    }

    // The Ident that names a parameter or a local, or the empty name when none follows.
    private String optionalName(String what) throws AssemblyException {
        if (!parser.atKind(Token.WORD)) {
            return "";
        }
        Token name = parser.identifier(what);
        identifier(name, name.text());
        return name.text();
    }

    // The words of a list of flags, in any order, each once.
    private int flags(String[] words, int[] values) throws AssemblyException {
        int flags = 0;
        while (true) {
            int flag = 0;
            for (int i = 0; i < words.length; i++) {
                if (parser.at(words[i])) {
                    flag = values[i];
                }
            }
            if (flag == 0) {
                return flags;
            }
            Token word = parser.next();
            if ((flags & flag) != 0) {
                throw Parser.error(Diagnostic.REPEATED, word, word.text() + " appears twice");
            }
            flags |= flag;
        }
    }

    // The type of a definition in this position of a signature, checked at its token (BC-03).
    private TypeSig definition(TypeSyntax syntax, int position) throws AssemblyException {
        TypeSig type = resolve(syntax);
        check(syntax.token(), Signatures.problem(type, position, ModuleWriter.DEFINITION, module.name()));
        return type;
    }

    // The type in this position of a signature that a reference states, checked at its token (BC-03).
    private TypeSig reference(TypeSyntax syntax, int position) throws AssemblyException {
        TypeSig type = resolve(syntax);
        check(syntax.token(), Signatures.problem(type, position, ModuleWriter.REFERENCE, module.name()));
        return type;
    }

    // The signature type that the syntax states: a primitive is its code (Q52), a type of the module
    // its TypeDef, and any other type a TypeRef (Q76).
    private TypeSig resolve(TypeSyntax syntax) throws AssemblyException {
        if (syntax instanceof TypeSyntax.Primitive primitive) {
            return new TypeSig.Primitive(primitive.code());
        }
        if (syntax instanceof TypeSyntax.Named named) {
            return named(named);
        }
        if (syntax instanceof TypeSyntax.TypeVar variable) {
            return new TypeSig.TypeVar(variable.method(), variable.index());
        }
        if (syntax instanceof TypeSyntax.Intersection intersection) {
            var components = new ArrayList<TypeSig>();
            for (TypeSyntax component : intersection.components()) {
                components.add(resolve(component));
            }
            return new TypeSig.Intersection(components);
        }
        if (syntax instanceof TypeSyntax.Suffixed suffixed) {
            TypeSig inner = resolve(suffixed.type());
            return suffixed.code() == SigCode.NULLABLE ? new TypeSig.NullableRef(inner)
                    : suffixed.code() == SigCode.ARRAY ? new TypeSig.ArrayOf(inner) : new TypeSig.PointerTo(inner);
        }
        if (syntax instanceof TypeSyntax.ByRef byRef) {
            return new TypeSig.ByRef(byRef.kind(), resolve(byRef.type()));
        }
        var wildcard = (TypeSyntax.Wildcard) syntax;
        @Nullable TypeSyntax bound = wildcard.bound();
        return new TypeSig.Wildcard(wildcard.kind(), bound == null ? null : resolve(bound));
    }

    private TypeSig named(TypeSyntax.Named named) throws AssemblyException {
        int primitive = primitiveStruct(named);
        if (primitive != 0) {
            if (!named.arguments().isEmpty()) {
                throw Parser.error(Diagnostic.FORMAT, named.token(), "a primitive takes no type arguments (BC-03)");
            }
            return new TypeSig.Primitive(primitive);
        }
        TypeHandle handle = handle(named);
        var type = new TypeSig.Named(named.valueType() || valueKind(handle), handle);
        if (named.arguments().isEmpty()) {
            return type;
        }
        var arguments = new ArrayList<TypeSig>();
        for (TypeSyntax argument : named.arguments()) {
            arguments.add(resolve(argument));
        }
        return new TypeSig.GenericInst(type, arguments);
    }

    // The TypeDef or TypeRef row of a type in a token, as the base type and an interface are; any
    // other type needs a TypeSpec row.
    private TypeHandle tokenType(TypeSyntax syntax) throws AssemblyException {
        if (syntax instanceof TypeSyntax.Named named && named.arguments().isEmpty() && primitiveStruct(named) == 0) {
            TypeHandle handle = handle(named);
            check(syntax.token(), Signatures.problem(new TypeSig.Named(named.valueType() || valueKind(handle), handle),
                    Signatures.OTHER, ModuleWriter.DEFINITION, module.name()));
            return handle;
        }
        throw Parser.error(Diagnostic.LATER, syntax.token(), "the type needs a TypeSpec row, which arrives in v0.0.6");
    }

    // A name of the module is its TypeDef. Any other name is a TypeRef of the module in brackets, or of
    // the required module whose name is its namespace or the longest prefix of it at a dot (Q76).
    private TypeHandle handle(TypeSyntax.Named named) throws AssemblyException {
        TypeName name = named.name();
        @Nullable Token scope = named.scope();
        @Nullable TypeDef type = scope == null ? types.get(name.token().text()) : null;
        if (type != null) {
            for (String nested : name.nested()) {
                @Nullable TypeDef inner = types.get(type.qualifiedName() + "/" + nested);
                if (inner == null) {
                    throw Parser.error(Diagnostic.NO_TYPE, name.token(), "the module defines no type "
                            + type.qualifiedName() + "/" + nested);
                }
                type = inner;
            }
            return type;
        }
        @Nullable ModuleRef required = scope == null ? prefixModule(name.namespace()) : requires.get(scope.text());
        if (required == null) {
            if (scope != null) {
                throw Parser.error(Diagnostic.NOT_REQUIRED, scope, "the module does not require " + scope.text());
            }
            throw Parser.error(Diagnostic.NO_MODULE, name.token(), "no required module holds the "
                    + (name.namespace().isEmpty() ? "global namespace" : "namespace " + name.namespace()) + " of "
                    + name.qualified() + "; name its module in brackets (Q76)");
        }
        identifier(name.token(), name.namespace());
        identifier(name.token(), name.name());
        String key = "[" + required.name() + "]" + name.token().text();
        TypeRef ref = typeRef(required, name.namespace(), name.name(), key);
        for (String nested : name.nested()) {
            identifier(name.token(), nested);
            key = key + "/" + nested;
            ref = typeRef(ref, "", nested, key);
        }
        return ref;
    }

    private @Nullable ModuleRef prefixModule(String namespace) {
        @Nullable ModuleRef best = null;
        for (ModuleRef ref : module.requires()) {
            String candidate = ref.name();
            boolean prefix = namespace.equals(candidate) || namespace.startsWith(candidate + ".");
            if (prefix && (best == null || candidate.length() > best.name().length())) {
                best = ref;
            }
        }
        return best;
    }

    // One TypeRef row per scope, namespace and name, which the key spells.
    private TypeRef typeRef(TypeScope scope, String namespace, String name, String key) {
        @Nullable TypeRef ref = typeRefs.get(key);
        if (ref == null) {
            ref = new TypeRef(scope, namespace, name);
            typeRefs.put(key, ref);
            module.typeRefs().add(ref);
        }
        return ref;
    }

    // The code of a primitive that the text names by its struct, such as reflect.core.Int32 (Q52), or 0.
    private static int primitiveStruct(TypeSyntax.Named named) {
        TypeName name = named.name();
        return named.scope() == null && name.nested().isEmpty() ? Signatures.primitiveCode(name.namespace(), name.name()) : 0;
    }

    private static boolean valueKind(TypeHandle handle) {
        return handle instanceof TypeDef def && Signatures.valueKind(def.kind());
    }

    // The ancillary sections leave the contentHash as it is (BC-22), so the module written without
    // them tells whether a kept DEBUG is stale (BC-20).
    private byte[] write() throws AssemblyException {
        byte[] file = written();
        if (sections.isEmpty()) {
            return file;
        }
        ModuleFile read;
        try {
            read = ModuleFile.read(file);
        } catch (ModuleFormatException e) {
            throw content(moduleName, e.getMessage());
        }
        for (Kept kept : sections) {
            if (kept.section().id() == SectionId.DEBUG) {
                if (read.find(SectionId.DEBUG) >= 0) {
                    throw Parser.error(Diagnostic.FORMAT, kept.token(), "the lines and local names of the module give"
                            + " its DEBUG, so it keeps no other (BC-02)");
                }
                if (!Debug.stale(kept.section().content(), read.contentHash())) {
                    throw Parser.error(Diagnostic.FORMAT, kept.token(), "the kept DEBUG starts with the contentHash of"
                            + " the module, so it is not stale (BC-20)");
                }
            }
            module.ancillary().add(kept.section());
        }
        return written();
    }

    private byte[] written() throws AssemblyException {
        try {
            return ModuleWriter.write(module);
        } catch (ModuleFormatException e) {
            throw content(moduleName, e.getMessage());
        }
    }

    // An identifier that IDENTS holds, which BC-21 limits.
    private void identifier(Token at, String text) throws AssemblyException {
        int length;
        try {
            length = Utf8.encode(text).length;
        } catch (ModuleFormatException e) {
            throw content(at, e.getMessage());
        }
        if (length > Limits.MAX_IDENTIFIER_BYTES) {
            throw Parser.error(Diagnostic.LIMIT, at, "an identifier of " + length + " UTF-8 bytes exceeds the limit of "
                    + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
        }
    }

    private static void check(Token at, @Nullable String problem) throws AssemblyException {
        if (problem != null) {
            throw content(at, problem);
        }
    }

    // A rule of the format, or a limit of BC-21, which the message names.
    private static AssemblyException content(Token at, String problem) {
        return Parser.error(problem.endsWith("(BC-21)") ? Diagnostic.LIMIT : Diagnostic.FORMAT, at, problem);
    }

    // A type that the first reading declared: its base type and interfaces as the text gives them,
    // and the position of each member, which the second reading reads.
    private record Declared(TypeDef type, @Nullable TypeSyntax base, List<TypeSyntax> interfaces, List<Integer> members) {
    }

    // A section of a .section directive.
    private record Kept(Token token, Section section) {
    }

    // A body that the third reading reads: its method, the owner that messages name, and where it starts.
    private record Body(MethodDef method, String owner, int position) {
    }

    // A body as the text gives it: its labels by name, each use of a label, per instruction its mnemonic
    // and the name that gives its argument or local, if any, and the .try of each region.
    private record Text(Map<String, Label> labels, List<Token> uses, List<Token> mnemonics,
            List<@Nullable Token> names, List<Token> regions) {
    }

    private record MemberKey(TypeHandle parent, String name, MemberSig signature) {
    }
}
