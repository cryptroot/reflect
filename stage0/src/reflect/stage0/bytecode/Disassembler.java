package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
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
    private static final String INDENT = "    ";
    private static final String HEX = "0123456789ABCDEF";

    private final ModuleDef module;
    private final StringBuilder text = new StringBuilder();
    // The top-level types by qualified name, which the name alone means (Q76).
    private final Map<String, TypeDef> types = new HashMap<>();
    private final Map<TypeRef, Boolean> namedTypes = new HashMap<>();
    private final Map<MemberRef, Boolean> namedMembers = new HashMap<>();

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
    // .memberref declare the others (Q80).
    private void collect() {
        for (TypeDef type : module.types()) {
            if (type.enclosing() == null) {
                types.put(topName(type.namespace(), type.name()), type);
            }
            name(type.base());
            for (TypeHandle implemented : type.interfaces()) {
                name(implemented);
            }
            for (FieldDef field : type.fields()) {
                name(field.type());
            }
            for (MethodDef method : type.methods()) {
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
                name((MethodSig) ref.signature());
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
        for (TypeDef nested : module.types()) {
            if (nested.enclosing() == type) {
                type(nested, depth + 1);
            }
        }
        line(depth, "}");
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
