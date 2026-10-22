package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * A Type of §8.1 as the text gives it, before its names resolve, so a type can name a type that
 * the text defines later. Each form keeps the token where it starts, where rfla reports its defects.
 */
sealed interface TypeSyntax permits TypeSyntax.Primitive, TypeSyntax.Named, TypeSyntax.TypeVar,
        TypeSyntax.Intersection, TypeSyntax.Suffixed, TypeSyntax.ByRef, TypeSyntax.Wildcard {
    Token token();

    /** A primitive keyword, or void as a return type. */
    record Primitive(Token token, int code) implements TypeSyntax {
    }

    /** A type by name: valuetype, the module in brackets (Q76), the name and the type arguments. */
    record Named(Token token, boolean valueType, @Nullable Token scope, TypeName name, List<TypeSyntax> arguments)
            implements TypeSyntax {
    }

    /** !n, a type parameter of the type, or !!n, one of the method. */
    record TypeVar(Token token, boolean method, int index) implements TypeSyntax {
    }

    record Intersection(Token token, List<TypeSyntax> components) implements TypeSyntax {
    }

    /** A suffix, by its signature code: NULLABLE for ?, ARRAY for [] and PTR for *. */
    record Suffixed(Token token, TypeSyntax type, int code) implements TypeSyntax {
    }

    /** ref, in or out, by the BYREF kind. */
    record ByRef(Token token, int kind, TypeSyntax type) implements TypeSyntax {
    }

    /** A wildcard type argument by its WILDCARD kind, with a bound unless the kind is ANY. */
    record Wildcard(Token token, int kind, @Nullable TypeSyntax bound) implements TypeSyntax {
    }
}
