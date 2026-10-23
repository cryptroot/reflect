package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * The disassembler rfld (README §7.5): prints the canonical .rfla text of a module (BC-25), which rfla
 * assembles back to the same bytes (BC-08). The text follows the order of the binary, one directive
 * per line, with four spaces of indentation per level and LF line ends.
 */
public final class Disassembler {
    /** The quoted words of §8.1; a type name or a module name that is one of them takes @ (Q82). */
    static final String[] WORDS = {"transitive", "static", "to", "hex", "public", "protected", "private", "abstract",
        "final", "sealed", "unsealed", "interface", "struct", "ref", "record", "enum", "annotation", "extends",
        "implements", "const", "backing", "true", "false", "virtual", "native", "extern", "unsafe", "mutating",
        "precompile", "synthesized", "scoped", "this", "class", "i32", "i64", "f32", "f64", "null", "ptr", "uninit",
        "byref", "byref.readonly", "in", "out", "valuetype", "super", "void", "boolean", "byte", "ubyte", "short",
        "ushort", "char", "int", "uint", "long", "ulong", "float", "double", "inf", "bits", "e", "E"};
    // The words of the flags, one per bit from bit 0.
    private static final String[] REQUIRES_WORDS = {"transitive", "static"};
    private static final String[] TYPE_WORDS = {"public", "protected", "private", "abstract", "final", "sealed",
        "unsealed"};
    // The words of the TypeDef kinds, from CLASS, which has none.
    private static final String[] KIND_WORDS = {"", "interface ", "struct ", "ref struct ", "record ", "record struct ",
        "enum ", "annotation "};
    private static final String[] FIELD_WORDS = {"public", "protected", "private", "static", "final", "const", "backing"};
    // CONSTRUCTOR, bit 7, has no word: the name new or static gives it (§3.1).
    private static final String[] METHOD_WORDS = {"public", "protected", "private", "static", "virtual", "abstract",
        "final", "", "native", "extern", "unsafe", "mutating", "const", "precompile", "synthesized"};
    private static final String[] PARAM_WORDS = {"scoped", "this"};
    private static final String[] BYREF_WORDS = {"ref ", "in ", "out "};
    private static final String INDENT = "    ";
    private static final String HEX = "0123456789ABCDEF";

    private final ModuleDef module;
    private final StringBuilder text = new StringBuilder();
    // The top-level types by qualified name, which the name alone means (Q76).
    private final Map<String, TypeDef> types = new HashMap<>();
    private final Map<TypeRef, Boolean> namedTypes = new HashMap<>();
    private final Map<MemberRef, Boolean> namedMembers = new HashMap<>();
    private final Map<FieldDef, TypeDef> fieldOwners = new HashMap<>();
    private final Map<MethodDef, TypeDef> methodOwners = new HashMap<>();

    private Disassembler(ModuleDef module) {
        this.module = module;
    }

    /** The canonical text of the module file, as UTF-8 (BC-25). */
    public static byte[] disassemble(byte[] file) throws ModuleFormatException {
        return Utf8.encode(text(ModuleReader.read(file)));
    }

    /** The canonical text of a module that the reader gave, whose rows come in row order. */
    static String text(ModuleDef module) throws ModuleFormatException {
        var disassembler = new Disassembler(module);
        disassembler.collect();
        disassembler.print();
        return disassembler.text.toString();
    }

    // The TypeRef and MemberRef rows that a column, a signature or an instruction names; .typeref and
    // .memberref declare the others (Q80). A GENERIC MemberRef has no text before MethodSpec (v0.0.9).
    private void collect() throws ModuleFormatException {
        for (TypeDef type : module.types()) {
            if (type.enclosing() == null) {
                types.put(topName(type.namespace(), type.name()), type);
            }
            name(type.base());
            for (TypeHandle implemented : type.interfaces()) {
                name(implemented);
            }
            for (FieldDef field : type.fields()) {
                fieldOwners.put(field, type);
                name(field.type());
            }
            for (MethodDef method : type.methods()) {
                methodOwners.put(method, type);
                name(method.signature());
                MethodBody body = method.body();
                if (body != null) {
                    name(body);
                }
            }
        }
        for (TypeRef ref : module.typeRefs()) {
            if (ref.scope() instanceof TypeRef outer) {
                namedTypes.put(outer, true);
            }
        }
        for (MemberRef ref : module.memberRefs()) {
            name(ref.parent());
            if (ref.signature() instanceof FieldSig field) {
                name(field.type());
            } else {
                var method = (MethodSig) ref.signature();
                if ((method.flags() & SigCode.METHOD_GENERIC) != 0) {
                    throw new ModuleFormatException("the MemberRef " + ref.parent().qualifiedName() + "::" + ref.name()
                            + " is GENERIC, which a MethodRef cannot state before the MethodSpec table arrives in v0.0.9");
                }
                name(method);
            }
        }
    }

    private void name(MethodBody body) {
        for (TypeSig local : body.locals()) {
            name(local);
        }
        for (Instruction instruction : body.code()) {
            if (instruction instanceof Instruction.Type type) {
                name(type.type());
            } else if (instruction instanceof Instruction.Field field) {
                name(field.field());
            } else if (instruction instanceof Instruction.Method method) {
                name(method.method());
            } else if (instruction instanceof Instruction.Constrained constrained) {
                name(constrained.type());
                name(constrained.method());
            }
        }
        for (Region region : body.regions()) {
            name(region.type());
        }
        for (Frame frame : body.frames()) {
            for (Frame.VType slot : frame.slots()) {
                name(slot.type());
            }
            for (Frame.VType value : frame.stack()) {
                name(value.type());
            }
        }
    }

    private void name(@Nullable Handle handle) {
        if (handle instanceof TypeRef ref) {
            namedTypes.put(ref, true);
        } else if (handle instanceof MemberRef ref) {
            namedMembers.put(ref, true);
        }
    }

    private void name(MethodSig signature) {
        name(signature.returnType());
        for (TypeSig param : signature.params()) {
            name(param);
        }
    }

    private void name(@Nullable TypeSig type) {
        if (type instanceof TypeSig.Named named) {
            name(named.type());
        } else if (type instanceof TypeSig.GenericInst generic) {
            name(generic.type());
            for (TypeSig argument : generic.arguments()) {
                name(argument);
            }
        } else if (type instanceof TypeSig.NullableRef nullable) {
            name(nullable.type());
        } else if (type instanceof TypeSig.ArrayOf array) {
            name(array.element());
        } else if (type instanceof TypeSig.PointerTo pointer) {
            name(pointer.element());
        } else if (type instanceof TypeSig.ByRef byRef) {
            name(byRef.type());
        } else if (type instanceof TypeSig.Wildcard wildcard) {
            name(wildcard.bound());
        } else if (type instanceof TypeSig.Intersection intersection) {
            for (TypeSig component : intersection.components()) {
                name(component);
            }
        }
    }

    // The module directives, the classes, then the directives after them, each part in the order of
    // the binary: the header, the tables by ID, the sections by ID.
    private void print() {
        line(0, ".module " + escape(module.name()) + " " + unsigned(module.major()) + "." + unsigned(module.minor()) + "."
                + unsigned(module.patch()));
        if (module.unsafe()) {
            line(0, ".unsafe");
        }
        TypeDef main = module.main();
        if (main != null) {
            line(0, ".main " + qualified(main));
        }
        for (ModuleRef ref : module.requires()) {
            line(0, ".requires " + words(ref.flags(), REQUIRES_WORDS) + escape(ref.name()));
        }
        for (Export export : module.exports()) {
            line(0, (export.kind() == Flags.OPENS ? ".opens " : ".exports ") + export.namespace()
                    + (export.target().isEmpty() ? "" : " to " + escape(export.target())));
        }
        for (TypeDef type : module.types()) {
            if (type.enclosing() == null) {
                text.append('\n');
                type(type, 0);
            }
        }
        var trailing = new ArrayList<String>();
        for (TypeRef ref : module.typeRefs()) {
            if (!namedTypes.containsKey(ref)) {
                trailing.add(".typeref " + typeName(ref));
            }
        }
        for (MemberRef ref : module.memberRefs()) {
            if (!namedMembers.containsKey(ref)) {
                trailing.add(".memberref " + (ref.signature() instanceof FieldSig ? fieldRef(ref) : methodRef(ref)));
            }
        }
        for (Section section : module.ancillary()) {
            trailing.add(".section " + ModuleFormatException.hex(section.id() & 0xFFFFFFFFL) + " hex \""
                    + hex(section.content()) + "\"");
        }
        if (!trailing.isEmpty()) {
            text.append('\n');
            for (String directive : trailing) {
                line(0, directive);
            }
        }
    }

    // A .class with its kind, flags, backing type, base type and interfaces; its nested types follow its
    // members. A nested type names only itself (BC-08).
    private void type(TypeDef type, int depth) {
        var head = new StringBuilder(".class ").append(words(type.flags(), TYPE_WORDS)).append(KIND_WORDS[type.kind()])
                .append(type.enclosing() == null ? qualified(type) : escape(type.name()));
        if (type.kind() == Flags.KIND_ENUM) {
            head.append(" : ").append(Signatures.primitiveName(type.backing()));
        }
        TypeHandle base = type.base();
        if (base != null) {
            head.append(" extends ").append(typeName(base));
        }
        for (int i = 0; i < type.interfaces().size(); i++) {
            head.append(i == 0 ? " implements " : ", ").append(typeName(type.interfaces().get(i)));
        }
        line(depth, head.append(" {").toString());
        for (FieldDef field : type.fields()) {
            line(depth + 1, ".field " + words(field.flags(), FIELD_WORDS) + type(field.type()) + " " + field.name());
        }
        for (MethodDef method : type.methods()) {
            method(method, depth + 1);
        }
        for (TypeDef nested : module.types()) {
            if (nested.enclosing() == type) {
                type(nested, depth + 1);
            }
        }
        line(depth, "}");
    }

    // A .method with its flags, return type, name and parameters; ... after the type of the last
    // parameter of a variadic method (MEM-10).
    private void method(MethodDef method, int depth) {
        MethodSig signature = method.signature();
        var head = new StringBuilder(".method ").append(words(method.flags(), METHOD_WORDS))
                .append(type(signature.returnType())).append(' ').append(method.name()).append('(');
        List<TypeSig> types = signature.params();
        for (int i = 0; i < types.size(); i++) {
            ParamDef param = method.params().get(i);
            head.append(i == 0 ? "" : ", ").append(words(param.flags(), PARAM_WORDS)).append(type(types.get(i)))
                    .append(variadic(signature, i) ? "..." : "").append(param.name().isEmpty() ? "" : " " + param.name());
        }
        line(depth, head.append(") {").toString());
        line(depth, "}");
    }

    // FieldRef of §8.1 in its full form (Q69): the type of the field, then the type that holds it.
    private String fieldRef(FieldHandle field) {
        if (field instanceof FieldDef def) {
            return type(def.type()) + " " + typeName(fieldOwners.get(def)) + "::" + def.name();
        }
        var ref = (MemberRef) field;
        return type(((FieldSig) ref.signature()).type()) + " " + typeName(ref.parent()) + "::" + ref.name();
    }

    // MethodRef of §8.1 in its full form (Q69): static, the return type, the type that holds the method,
    // its name and its parameter types.
    private String methodRef(MethodHandle method) {
        TypeHandle holder;
        String name;
        MethodSig signature;
        if (method instanceof MethodDef def) {
            holder = methodOwners.get(def);
            name = def.name();
            signature = def.signature();
        } else {
            var ref = (MemberRef) method;
            holder = ref.parent();
            name = ref.name();
            signature = (MethodSig) ref.signature();
        }
        var text = new StringBuilder((signature.flags() & SigCode.METHOD_STATIC) != 0 ? "static " : "")
                .append(type(signature.returnType())).append(' ').append(typeName(holder)).append("::").append(name)
                .append('(');
        for (int i = 0; i < signature.params().size(); i++) {
            text.append(i == 0 ? "" : ", ").append(type(signature.params().get(i))).append(variadic(signature, i) ? "..." : "");
        }
        return text.append(')').toString();
    }

    private static boolean variadic(MethodSig signature, int param) {
        return (signature.flags() & SigCode.METHOD_VARIADIC) != 0 && param == signature.params().size() - 1;
    }

    // A type as a signature holds it (BC-03): a primitive by its struct (Q52), and valuetype before a value
    // type, the module's own too (Q69).
    private String type(TypeSig type) {
        if (type instanceof TypeSig.Primitive primitive) {
            return primitive.code() == SigCode.VOID ? "void" : Signatures.primitiveName(primitive.code());
        }
        if (type instanceof TypeSig.Named named) {
            return (named.valueType() ? "valuetype " : "") + typeName(named.type());
        }
        if (type instanceof TypeSig.GenericInst generic) {
            var text = new StringBuilder(type(generic.type()));
            for (int i = 0; i < generic.arguments().size(); i++) {
                text.append(i == 0 ? "<" : ", ").append(type(generic.arguments().get(i)));
            }
            return text.append('>').toString();
        }
        if (type instanceof TypeSig.TypeVar variable) {
            return (variable.method() ? "!!" : "!") + unsigned(variable.index());
        }
        if (type instanceof TypeSig.NullableRef nullable) {
            return type(nullable.type()) + "?";
        }
        if (type instanceof TypeSig.ArrayOf array) {
            return type(array.element()) + "[]";
        }
        if (type instanceof TypeSig.PointerTo pointer) {
            return type(pointer.element()) + "*";
        }
        if (type instanceof TypeSig.ByRef byRef) {
            return BYREF_WORDS[byRef.kind()] + type(byRef.type());
        }
        if (type instanceof TypeSig.Wildcard wildcard) {
            TypeSig bound = wildcard.bound();
            return bound == null ? "?" : (wildcard.kind() == SigCode.WILDCARD_EXTENDS ? "? extends " : "? super ")
                    + type(bound);
        }
        var text = new StringBuilder("(");
        List<TypeSig> components = ((TypeSig.Intersection) type).components();
        for (int i = 0; i < components.size(); i++) {
            text.append(i == 0 ? "" : " & ").append(type(components.get(i)));
        }
        return text.append(')').toString();
    }

    // A type as a token names it: by its name, with the module in brackets where the name alone means
    // another type or the primitive (Q76, Q81).
    private String typeName(TypeHandle type) {
        if (type instanceof TypeDef def) {
            boolean struct = def.enclosing() == null && Signatures.primitiveCode(def.namespace(), def.name()) != 0;
            return (struct ? "[" + escape(module.name()) + "]" : "") + qualified(def);
        }
        var ref = (TypeRef) type;
        TypeRef top = ref;
        while (top.scope() instanceof TypeRef outer) {
            top = outer;
        }
        var required = (ModuleRef) top.scope();
        @Nullable ModuleRef prefix = prefixModule(top.namespace());
        boolean plain = prefix != null && prefix.name().equals(required.name())
                && !types.containsKey(topName(top.namespace(), top.name()))
                && !(ref == top && Signatures.primitiveCode(ref.namespace(), ref.name()) != 0);
        return (plain ? "" : "[" + escape(required.name()) + "]") + qualified(ref);
    }

    // The spelling of GEN-12, with @ before the name of a top-level type that is a word (Q82).
    private static String qualified(TypeHandle type) {
        if (type instanceof TypeDef def) {
            TypeDef outer = def.enclosing();
            return outer != null ? qualified(outer) + "/" + def.name() : escape(topName(def.namespace(), def.name()));
        }
        var ref = (TypeRef) type;
        return ref.scope() instanceof TypeRef outer ? qualified(outer) + "/" + ref.name()
                : escape(topName(ref.namespace(), ref.name()));
    }

    private static String topName(String namespace, String name) {
        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    // The required module whose name is the namespace or its longest prefix at a dot (Q76).
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

    // The name, with @ before it when it is a word of the grammar (Q82).
    private static String escape(String name) {
        for (String word : WORDS) {
            if (word.equals(name)) {
                return "@" + name;
            }
        }
        return name;
    }

    // The words of the flags that are set, each followed by a space, in the order of their bits.
    private static String words(int flags, String[] words) {
        var text = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if ((flags & (1 << i)) != 0 && !words[i].isEmpty()) {
                text.append(words[i]).append(' ');
            }
        }
        return text.toString();
    }

    private static String unsigned(int value) {
        return String.valueOf(value & 0xFFFFFFFFL);
    }

    // Pairs of uppercase hexadecimal digits.
    private static String hex(byte[] bytes) {
        var text = new StringBuilder();
        for (byte b : bytes) {
            text.append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
        }
        return text.toString();
    }

    private void line(int depth, String content) {
        for (int i = 0; i < depth; i++) {
            text.append(INDENT);
        }
        text.append(content).append('\n');
    }
}
