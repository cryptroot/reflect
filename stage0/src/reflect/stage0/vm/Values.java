package reflect.stage0.vm;

import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeSig;
import reflect.stage0.lib.Nullable;

/** The zero values that locals and fields hold before their first store (BC-17, Q91). */
final class Values {
    private Values() {
    }

    /**
     * Whether the type is a value type other than a primitive, whose zero value VM0 does not have
     * before enums and structs arrive (v0.0.7).
     */
    static boolean valueType(TypeSig type) {
        return type instanceof TypeSig.Named named && named.valueType()
                || type instanceof TypeSig.GenericInst instance && instance.type().valueType();
    }

    /** The zero value of a type that is not a value type: 0, 0.0 or null. */
    static @Nullable Object zero(TypeSig type) {
        if (type instanceof TypeSig.Primitive primitive) {
            return switch (primitive.code()) {
                case SigCode.I64, SigCode.U64 -> 0L;
                case SigCode.F32 -> 0.0f;
                case SigCode.F64 -> 0.0;
                default -> 0;
            };
        }
        return null;
    }
}
