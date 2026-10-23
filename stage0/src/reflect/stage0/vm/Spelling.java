package reflect.stage0.vm;

import java.util.ArrayList;
import java.util.List;

import reflect.stage0.bytecode.MethodSig;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.bytecode.TypeRef;
import reflect.stage0.bytecode.TypeSig;

/**
 * The text of a signature, in the full form of a .rfla MethodRef with primitive keywords (Q52,
 * Q69). It keys the natives, and messages show it. With scopes, each named type also states its
 * module in brackets, so two modules that define one name give two texts: VM0 compares a MemberRef
 * with the members of another module by that text.
 */
final class Spelling {
    private static final String[] PRIMITIVES = {"", "void", "boolean", "byte", "ubyte", "short", "ushort", "char",
        "int", "uint", "long", "ulong", "float", "double"};

    private Spelling() {
    }

    /** A method of a type: static, the return type, the type, the name and the parameter types. */
    static String method(String owner, String name, MethodSig signature, String module, boolean scoped) {
        var text = new StringBuilder((signature.flags() & SigCode.METHOD_STATIC) != 0 ? "static " : "")
                .append(type(signature.returnType(), module, scoped)).append(' ').append(owner).append("::").append(name);
        if ((signature.flags() & SigCode.METHOD_GENERIC) != 0) {
            text.append('<').append(signature.typeParams()).append('>');
        }
        var params = new ArrayList<String>();
        for (TypeSig param : signature.params()) {
            params.add(type(param, module, scoped));
        }
        text.append('(').append(String.join(", ", params));
        if ((signature.flags() & SigCode.METHOD_VARIADIC) != 0) {
            text.append("...");
        }
        return text.append(')').toString();
    }

    /** A type of a signature that the module holds. */
    static String type(TypeSig type, String module, boolean scoped) {
        return switch (type) {
            case TypeSig.Primitive primitive -> PRIMITIVES[primitive.code()];
            case TypeSig.Named named -> (named.valueType() ? "valuetype " : "")
                    + (scoped ? "[" + module(named.type(), module) + "]" : "") + named.type().qualifiedName();
            case TypeSig.GenericInst instance -> type(instance.type(), module, scoped) + "<"
                    + String.join(", ", types(instance.arguments(), module, scoped)) + ">";
            case TypeSig.TypeVar variable -> (variable.method() ? "!!" : "!") + variable.index();
            case TypeSig.NullableRef nullable -> type(nullable.type(), module, scoped) + "?";
            case TypeSig.ArrayOf array -> type(array.element(), module, scoped) + "[]";
            case TypeSig.PointerTo pointer -> type(pointer.element(), module, scoped) + "*";
            case TypeSig.ByRef reference -> (reference.kind() == SigCode.BYREF_IN ? "in "
                    : reference.kind() == SigCode.BYREF_OUT ? "out " : "ref ") + type(reference.type(), module, scoped);
            case TypeSig.Wildcard wildcard -> wildcard.bound() == null ? "?"
                    : (wildcard.kind() == SigCode.WILDCARD_SUPER ? "? super " : "? extends ")
                    + type(wildcard.bound(), module, scoped);
            case TypeSig.Intersection intersection -> "(" + String.join(" & ",
                    types(intersection.components(), module, scoped)) + ")";
        };
    }

    /** The module that defines a type that a signature of the module names. */
    static String module(TypeHandle type, String module) {
        if (type instanceof TypeDef) {
            return module;
        }
        TypeRef ref = (TypeRef) type;
        while (ref.scope() instanceof TypeRef outer) {
            ref = outer;
        }
        return ((ModuleRef) ref.scope()).name();
    }

    private static List<String> types(reflect.stage0.lib.List<TypeSig> types, String module, boolean scoped) {
        var texts = new ArrayList<String>();
        for (TypeSig type : types) {
            texts.add(type(type, module, scoped));
        }
        return texts;
    }
}
