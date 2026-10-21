package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * Encodes and decodes the signatures of BC-03, every type code included (Q74), and states their
 * structural rules once, for the writer and the reader: VOID only as a return type, WILDCARD only
 * as a type argument, NULLABLE only around a type with a nullable form, GENERICINST and
 * INTERSECTION with enough operands, an array as the variadic parameter, defined kinds and flags,
 * VAR and MVAR within the type parameters in scope, no primitive by its struct name, and the
 * nesting depth of BC-21.
 */
final class Signatures {
    /** The positions of a type: VOID stands only in RETURN, WILDCARD only in ARGUMENT. */
    static final int RETURN = 0;
    static final int ARGUMENT = 1;
    static final int OTHER = 2;

    private static final String CORE = "reflect.core";
    private static final String[] PRIMITIVE_STRUCTS = {"Boolean", "Int8", "UInt8", "Int16", "UInt16", "Char", "Int32",
        "UInt32", "Int64", "UInt64", "Float32", "Float64"};
    private static final int METHOD_FLAGS = SigCode.METHOD_STATIC | SigCode.METHOD_GENERIC | SigCode.METHOD_VARIADIC;

    /** The token of a type, for the writer. */
    @FunctionalInterface
    interface Tokens {
        int token(TypeHandle type) throws ModuleFormatException;
    }

    /** The type of a token, or null when the token names no TypeDef or TypeRef row, for the reader. */
    @FunctionalInterface
    interface Handles {
        @Nullable TypeHandle handle(int token);
    }

    /** The type parameters that VAR and MVAR may name; typeParams is -1 when they are not known. */
    record Scope(int typeParams, int methodParams) {
    }

    private Signatures() {
    }

    /** The first rule that the signature breaks, or null. */
    static @Nullable String problem(MethodSig signature, Scope scope, String module) {
        int flags = signature.flags();
        if ((flags & ~METHOD_FLAGS) != 0) {
            return "METHOD flags " + hex(flags & ~METHOD_FLAGS) + " are not defined (BC-20)";
        }
        if ((flags & SigCode.METHOD_GENERIC) != 0 ? signature.typeParams() < 1 : signature.typeParams() != 0) {
            return "a signature has type parameters exactly when it is GENERIC, and then at least one (BC-03)";
        }
        List<TypeSig> params = signature.params();
        if ((flags & SigCode.METHOD_VARIADIC) != 0
                && (params.isEmpty() || !(params.get(params.size() - 1) instanceof TypeSig.ArrayOf))) {
            return "the last parameter of a VARIADIC signature is an array (BC-03)";
        }
        if (params.size() > Limits.MAX_PARAMS) {
            return "the signature has " + params.size() + " parameters, more than the limit of " + Limits.MAX_PARAMS
                    + " (BC-21)";
        }
        var inner = new Scope(scope.typeParams(), signature.typeParams());
        String problem = problem(signature.returnType(), RETURN, inner, module);
        for (TypeSig param : params) {
            if (problem == null) {
                problem = problem(param, OTHER, inner, module);
            }
        }
        return problem;
    }

    /** The first rule that the type, in this position, breaks, or null. */
    static @Nullable String problem(TypeSig type, int position, Scope scope, String module) {
        return problem(type, position, scope, module, 1);
    }

    /** The first rule that the types of a LOCALS signature break, or null. */
    static @Nullable String localsProblem(List<TypeSig> types, Scope scope, String module) {
        if (types.isEmpty()) {
            return "a LOCALS signature lists at least one type; a body without locals has locals 0 (BC-17)";
        }
        if (types.size() > Limits.MAX_LOCALS) {
            return "the body has " + types.size() + " locals, more than the limit of " + Limits.MAX_LOCALS + " (BC-21)";
        }
        String problem = null;
        for (TypeSig type : types) {
            if (problem == null) {
                problem = problem(type, OTHER, scope, module);
            }
        }
        return problem;
    }

    private static @Nullable String problem(TypeSig type, int position, Scope scope, String module, int depth) {
        if (depth > Limits.MAX_SIGNATURE_DEPTH) {
            return "the signature nests types deeper than " + Limits.MAX_SIGNATURE_DEPTH + " (BC-21)";
        }
        if (type instanceof TypeSig.Primitive primitive) {
            int code = primitive.code();
            if (code == SigCode.VOID) {
                return position == RETURN ? null : "VOID stands outside a return type (BC-03)";
            }
            return code >= SigCode.BOOLEAN && code <= SigCode.F64 ? null : "code " + hex(code) + " is not a primitive (BC-03)";
        }
        if (type instanceof TypeSig.Named named) {
            return named(named, module);
        }
        if (type instanceof TypeSig.GenericInst generic) {
            String problem = named(generic.type(), module);
            // GenericParam rows come with v0.0.9; until then no TypeDef has a type parameter.
            if (problem == null && generic.type().type() instanceof TypeDef def) {
                problem = "GENERICINST instantiates " + def.qualifiedName() + ", which has no type parameters (BC-03)";
            }
            if (problem == null && generic.arguments().isEmpty()) {
                problem = "GENERICINST has no type argument (BC-03)";
            }
            for (TypeSig argument : generic.arguments()) {
                if (problem == null) {
                    problem = problem(argument, ARGUMENT, scope, module, depth + 1);
                }
            }
            return problem;
        }
        if (type instanceof TypeSig.TypeVar variable) {
            int index = variable.index();
            if (variable.method()) {
                return index >= 0 && index < scope.methodParams() ? null
                        : "MVAR " + (index & 0xFFFFFFFFL) + " names no type parameter of the method (BC-03)";
            }
            return index >= 0 && (scope.typeParams() < 0 || index < scope.typeParams()) ? null
                    : "VAR " + (index & 0xFFFFFFFFL) + " names no type parameter of the type (BC-03)";
        }
        if (type instanceof TypeSig.NullableRef nullable) {
            if (!nullableForm(nullable.type())) {
                return "NULLABLE wraps CLASS, a GENERICINST of CLASS, ARRAY, VAR or MVAR (BC-03)";
            }
            return problem(nullable.type(), OTHER, scope, module, depth + 1);
        }
        if (type instanceof TypeSig.ArrayOf array) {
            return problem(array.element(), OTHER, scope, module, depth + 1);
        }
        if (type instanceof TypeSig.PointerTo pointer) {
            return problem(pointer.element(), OTHER, scope, module, depth + 1);
        }
        if (type instanceof TypeSig.ByRef byRef) {
            if (byRef.kind() < SigCode.BYREF_REF || byRef.kind() > SigCode.BYREF_OUT) {
                return "BYREF kind " + hex(byRef.kind()) + " is not defined (BC-20)";
            }
            return problem(byRef.type(), OTHER, scope, module, depth + 1);
        }
        if (type instanceof TypeSig.Wildcard wildcard) {
            if (position != ARGUMENT) {
                return "WILDCARD stands outside a type argument (BC-03)";
            }
            if (wildcard.kind() < SigCode.WILDCARD_ANY || wildcard.kind() > SigCode.WILDCARD_SUPER) {
                return "WILDCARD kind " + hex(wildcard.kind()) + " is not defined (BC-20)";
            }
            TypeSig bound = wildcard.bound();
            if ((wildcard.kind() == SigCode.WILDCARD_ANY) != (bound == null)) {
                return "a WILDCARD has a bound exactly when its kind is not ANY (BC-03)";
            }
            return bound == null ? null : problem(bound, OTHER, scope, module, depth + 1);
        }
        List<TypeSig> components = ((TypeSig.Intersection) type).components();
        if (components.size() < 2) {
            return "INTERSECTION has at least two components (BC-03)";
        }
        for (int i = 0; i < components.size(); i++) {
            TypeSig component = components.get(i);
            boolean named = component instanceof TypeSig.Named n && !n.valueType()
                    || component instanceof TypeSig.GenericInst g && !g.type().valueType();
            if (!named) {
                return "the components of INTERSECTION are classes and interfaces (BC-03)";
            }
            String problem = problem(component, OTHER, scope, module, depth + 1);
            if (problem != null) {
                return problem;
            }
            if (i > 0 && ofKind(component, Flags.KIND_CLASS, Flags.KIND_RECORD)) {
                return "the class of an INTERSECTION is its first component (GEN-06)";
            }
        }
        // The kind of a type of another module is unknown, so the first component is ordered with
        // the second only when it is an interface of this module.
        for (int i = 1; i < components.size(); i++) {
            boolean ordered = i > 1 || ofKind(components.get(0), Flags.KIND_INTERFACE, Flags.KIND_ANNOTATION);
            String previous = componentType(components.get(i - 1)).qualifiedName();
            if (ordered && Utf8.compare(previous, componentType(components.get(i)).qualifiedName()) >= 0) {
                return "the interfaces of an INTERSECTION follow by qualified name, each once (GEN-06)";
            }
        }
        return null;
    }

    // Whether a component of INTERSECTION is a type of this module of one of the two kinds.
    private static boolean ofKind(TypeSig component, int kind, int other) {
        return componentType(component) instanceof TypeDef def && (def.kind() == kind || def.kind() == other);
    }

    // The named type of a component of INTERSECTION, without its type arguments.
    private static TypeHandle componentType(TypeSig component) {
        return component instanceof TypeSig.GenericInst generic ? generic.type().type()
                : ((TypeSig.Named) component).type();
    }

    // A type that NULLABLE may wrap: a named reference type, a type parameter or an array. Value
    // types, pointers, references, wildcards and intersections have no nullable form (TYP-01).
    private static boolean nullableForm(TypeSig type) {
        return type instanceof TypeSig.Named named && !named.valueType()
                || type instanceof TypeSig.GenericInst generic && !generic.type().valueType()
                || type instanceof TypeSig.TypeVar || type instanceof TypeSig.ArrayOf;
    }

    // A type by name: never a primitive's struct, and CLASS or VALUETYPE by the kind of a type that
    // the module defines. The kind of a type of another module is what its code states (Q69).
    private static @Nullable String named(TypeSig.Named named, String module) {
        String problem = primitiveStruct(named, module);
        if (problem == null && named.type() instanceof TypeDef def && named.valueType() != valueKind(def.kind())) {
            problem = def.qualifiedName() + (named.valueType() ? " is a reference type, so CLASS names it (BC-03)"
                    : " is a value type, so VALUETYPE names it (BC-03)");
        }
        return problem;
    }

    // Structs, ref structs, record structs and enums are value types; classes, interfaces, records
    // and annotation types are reference types (TYP-01).
    private static boolean valueKind(int kind) {
        return kind == Flags.KIND_STRUCT || kind == Flags.KIND_REF_STRUCT || kind == Flags.KIND_RECORD_STRUCT
                || kind == Flags.KIND_ENUM;
    }

    // A primitive is its code, never CLASS or VALUETYPE of reflect.core.Int32 and the like (BC-03).
    private static @Nullable String primitiveStruct(TypeSig.Named named, String module) {
        TypeHandle type = named.type();
        boolean core;
        String namespace;
        String name;
        if (type instanceof TypeRef ref) {
            core = ref.scope() instanceof ModuleRef required && required.name().equals(CORE);
            namespace = ref.namespace();
            name = ref.name();
        } else {
            var def = (TypeDef) type;
            core = module.equals(CORE) && def.enclosing() == null;
            namespace = def.namespace();
            name = def.name();
        }
        if (core && namespace.equals(CORE)) {
            for (String struct : PRIMITIVE_STRUCTS) {
                if (name.equals(struct)) {
                    return "the primitive " + CORE + "." + name + " is its code, not its struct by name (BC-03)";
                }
            }
        }
        return null;
    }

    static byte[] field(TypeSig type, Tokens tokens) throws ModuleFormatException {
        var out = new ByteSink();
        out.u8(SigCode.FIELD);
        encode(type, out, tokens);
        return out.toByteArray();
    }

    static byte[] method(MethodSig signature, Tokens tokens) throws ModuleFormatException {
        var out = new ByteSink();
        out.u8(SigCode.METHOD);
        out.u8(signature.flags());
        if ((signature.flags() & SigCode.METHOD_GENERIC) != 0) {
            out.uleb(signature.typeParams());
        }
        out.uleb(signature.params().size());
        encode(signature.returnType(), out, tokens);
        for (TypeSig param : signature.params()) {
            encode(param, out, tokens);
        }
        return out.toByteArray();
    }

    static byte[] locals(List<TypeSig> types, Tokens tokens) throws ModuleFormatException {
        var out = new ByteSink();
        out.u8(SigCode.LOCALS);
        out.uleb(types.size());
        for (TypeSig type : types) {
            encode(type, out, tokens);
        }
        return out.toByteArray();
    }

    static byte[] member(MemberSig signature, Tokens tokens) throws ModuleFormatException {
        if (signature instanceof FieldSig field) {
            return field(field.type(), tokens);
        }
        return method((MethodSig) signature, tokens);
    }

    private static void encode(TypeSig type, ByteSink out, Tokens tokens) throws ModuleFormatException {
        if (type instanceof TypeSig.Primitive primitive) {
            out.u8(primitive.code());
        } else if (type instanceof TypeSig.Named named) {
            out.u8(named.valueType() ? SigCode.VALUETYPE : SigCode.CLASS);
            out.uleb(tokens.token(named.type()));
        } else if (type instanceof TypeSig.GenericInst generic) {
            out.u8(SigCode.GENERICINST);
            encode(generic.type(), out, tokens);
            out.uleb(generic.arguments().size());
            for (TypeSig argument : generic.arguments()) {
                encode(argument, out, tokens);
            }
        } else if (type instanceof TypeSig.TypeVar variable) {
            out.u8(variable.method() ? SigCode.MVAR : SigCode.VAR);
            out.uleb(variable.index());
        } else if (type instanceof TypeSig.NullableRef nullable) {
            out.u8(SigCode.NULLABLE);
            encode(nullable.type(), out, tokens);
        } else if (type instanceof TypeSig.ArrayOf array) {
            out.u8(SigCode.ARRAY);
            encode(array.element(), out, tokens);
        } else if (type instanceof TypeSig.PointerTo pointer) {
            out.u8(SigCode.PTR);
            encode(pointer.element(), out, tokens);
        } else if (type instanceof TypeSig.ByRef byRef) {
            out.u8(SigCode.BYREF);
            out.u8(byRef.kind());
            encode(byRef.type(), out, tokens);
        } else if (type instanceof TypeSig.Wildcard wildcard) {
            out.u8(SigCode.WILDCARD);
            out.u8(wildcard.kind());
            TypeSig bound = wildcard.bound();
            if (bound != null) {
                encode(bound, out, tokens);
            }
        } else {
            var intersection = (TypeSig.Intersection) type;
            out.u8(SigCode.INTERSECTION);
            out.uleb(intersection.components().size());
            for (TypeSig component : intersection.components()) {
                encode(component, out, tokens);
            }
        }
    }

    /** Decodes a FIELD signature that fills the source. */
    static TypeSig field(ByteSource in, Handles handles) throws ModuleFormatException {
        kind(in, SigCode.FIELD, "a field signature starts with FIELD (BC-03)");
        TypeSig type = type(in, handles, 1);
        end(in);
        return type;
    }

    /** Decodes a METHOD signature that fills the source. */
    static MethodSig method(ByteSource in, Handles handles) throws ModuleFormatException {
        kind(in, SigCode.METHOD, "a method signature starts with METHOD (BC-03)");
        return methodAfterKind(in, handles);
    }

    /** Decodes a LOCALS signature that fills the source. */
    static List<TypeSig> locals(ByteSource in, Handles handles) throws ModuleFormatException {
        kind(in, SigCode.LOCALS, "a locals signature starts with LOCALS (BC-03)");
        int count = count(in);
        var types = new ArrayList<TypeSig>();
        for (int i = 0; i < count; i++) {
            types.add(type(in, handles, 1));
        }
        end(in);
        return types;
    }

    /** Decodes the FIELD or METHOD signature of a MemberRef row. */
    static MemberSig member(ByteSource in, Handles handles) throws ModuleFormatException {
        int at = in.position();
        int kind = in.u8();
        if (kind == SigCode.FIELD) {
            TypeSig type = type(in, handles, 1);
            end(in);
            return new FieldSig(type);
        }
        if (kind != SigCode.METHOD) {
            throw new ModuleFormatException(at, "a MemberRef signature starts with FIELD or METHOD (BC-03)");
        }
        return methodAfterKind(in, handles);
    }

    private static MethodSig methodAfterKind(ByteSource in, Handles handles) throws ModuleFormatException {
        int flags = in.u8();
        int typeParams = (flags & SigCode.METHOD_GENERIC) != 0 ? in.uleb() : 0;
        int count = count(in);
        TypeSig returnType = type(in, handles, 1);
        var params = new ArrayList<TypeSig>();
        for (int i = 0; i < count; i++) {
            params.add(type(in, handles, 1));
        }
        end(in);
        return new MethodSig(flags, typeParams, returnType, params);
    }

    private static TypeSig type(ByteSource in, Handles handles, int depth) throws ModuleFormatException {
        int at = in.position();
        if (depth > Limits.MAX_SIGNATURE_DEPTH) {
            throw new ModuleFormatException(at, "the signature nests types deeper than " + Limits.MAX_SIGNATURE_DEPTH
                    + " (BC-21)");
        }
        int code = in.u8();
        if (code >= SigCode.VOID && code <= SigCode.F64) {
            return new TypeSig.Primitive(code);
        }
        if (code == SigCode.CLASS || code == SigCode.VALUETYPE) {
            return new TypeSig.Named(code == SigCode.VALUETYPE, handle(in, handles));
        }
        if (code == SigCode.GENERICINST) {
            int inner = in.position();
            int kind = in.u8();
            if (kind != SigCode.CLASS && kind != SigCode.VALUETYPE) {
                throw new ModuleFormatException(inner, "GENERICINST instantiates a CLASS or VALUETYPE type (BC-03)");
            }
            var generic = new TypeSig.Named(kind == SigCode.VALUETYPE, handle(in, handles));
            int count = count(in);
            var arguments = new ArrayList<TypeSig>();
            for (int i = 0; i < count; i++) {
                arguments.add(type(in, handles, depth + 1));
            }
            return new TypeSig.GenericInst(generic, arguments);
        }
        if (code == SigCode.VAR || code == SigCode.MVAR) {
            return new TypeSig.TypeVar(code == SigCode.MVAR, in.uleb());
        }
        if (code == SigCode.NULLABLE) {
            return new TypeSig.NullableRef(type(in, handles, depth + 1));
        }
        if (code == SigCode.ARRAY) {
            return new TypeSig.ArrayOf(type(in, handles, depth + 1));
        }
        if (code == SigCode.PTR) {
            return new TypeSig.PointerTo(type(in, handles, depth + 1));
        }
        if (code == SigCode.BYREF) {
            int kind = in.u8();
            return new TypeSig.ByRef(kind, type(in, handles, depth + 1));
        }
        if (code == SigCode.WILDCARD) {
            int kindAt = in.position();
            int kind = in.u8();
            if (kind < SigCode.WILDCARD_ANY || kind > SigCode.WILDCARD_SUPER) {
                throw new ModuleFormatException(kindAt, "WILDCARD kind " + hex(kind) + " is not defined (BC-20)");
            }
            return new TypeSig.Wildcard(kind, kind == SigCode.WILDCARD_ANY ? null : type(in, handles, depth + 1));
        }
        if (code == SigCode.INTERSECTION) {
            int count = count(in);
            var components = new ArrayList<TypeSig>();
            for (int i = 0; i < count; i++) {
                components.add(type(in, handles, depth + 1));
            }
            return new TypeSig.Intersection(components);
        }
        throw new ModuleFormatException(at, "code " + hex(code) + " is not a type (BC-03)");
    }

    private static TypeHandle handle(ByteSource in, Handles handles) throws ModuleFormatException {
        int at = in.position();
        int token = in.uleb();
        TypeHandle type = handles.handle(token);
        if (type == null) {
            throw new ModuleFormatException(at, "token " + hex(token) + " does not name a TypeDef or TypeRef row (BC-03)");
        }
        return type;
    }

    // Each item of a count takes at least one byte, so a larger count cannot fit.
    private static int count(ByteSource in) throws ModuleFormatException {
        int at = in.position();
        int count = in.uleb();
        if (count < 0 || count > in.remaining()) {
            throw new ModuleFormatException(at, "a count of " + (count & 0xFFFFFFFFL) + " runs past the end of the signature");
        }
        return count;
    }

    private static void kind(ByteSource in, int kind, String message) throws ModuleFormatException {
        int at = in.position();
        if (in.u8() != kind) {
            throw new ModuleFormatException(at, message);
        }
    }

    private static void end(ByteSource in) throws ModuleFormatException {
        if (!in.atEnd()) {
            throw new ModuleFormatException(in.position(), "the signature ends before its blob does (BC-03)");
        }
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }
}
