package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/** A type in a signature (BC-03): one record per code or pair of codes. */
public sealed interface TypeSig permits TypeSig.Primitive, TypeSig.Named, TypeSig.GenericInst, TypeSig.TypeVar,
        TypeSig.NullableRef, TypeSig.ArrayOf, TypeSig.PointerTo, TypeSig.ByRef, TypeSig.Wildcard, TypeSig.Intersection {

    /** VOID, or a primitive from BOOLEAN to F64; a primitive is never VALUETYPE of its struct. */
    record Primitive(int code) implements TypeSig {
    }

    /** CLASS, a reference type, or VALUETYPE, a value type, by its TypeDef or TypeRef row. */
    record Named(boolean valueType, TypeHandle type) implements TypeSig {
    }

    /** GENERICINST: a named generic type and at least one type argument. */
    record GenericInst(Named type, List<TypeSig> arguments) implements TypeSig {
    }

    /** VAR, a type parameter of the type, or MVAR, one of the method (method is true). */
    record TypeVar(boolean method, int index) implements TypeSig {
    }

    /** NULLABLE: T? of a reference type; a nullable value type instantiates the nullable wrapper. */
    record NullableRef(TypeSig type) implements TypeSig {
    }

    /** ARRAY: a one-dimensional array of the element type. */
    record ArrayOf(TypeSig element) implements TypeSig {
    }

    /** PTR: a pointer to the element type. */
    record PointerTo(TypeSig element) implements TypeSig {
    }

    /** BYREF: ref, in or out, by the BYREF kind. */
    record ByRef(int kind, TypeSig type) implements TypeSig {
    }

    /** WILDCARD, only as a type argument: ? with no bound, or ? extends and ? super with one. */
    record Wildcard(int kind, @Nullable TypeSig bound) implements TypeSig {
    }

    /** INTERSECTION: the class first, then the interfaces by qualified name (GEN-06). */
    record Intersection(List<TypeSig> components) implements TypeSig {
    }
}
