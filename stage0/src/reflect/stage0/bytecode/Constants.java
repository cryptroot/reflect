package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/**
 * Encodes, decodes and checks the values of Constant rows (BC-11, Q98), and states their rules once, for the
 * writer and the reader: a field has a Constant row exactly when it is const, and the row holds a value that
 * fits the field's type, in the encoding of §3.1. Until later stages that type is a primitive or
 * reflect.core.String; an enum waits for v0.0.7, since a reader does not know the backing type of an enum of
 * another module.
 */
final class Constants {
    private static final String CORE = "reflect.core";

    private Constants() {
    }

    /** The first rule that the field breaks with its Constant, or null; the owner names the field. */
    static @Nullable String problem(FieldDef field, String owner, String module) {
        @Nullable Constant value = field.constant();
        if (value == null) {
            return (field.flags() & Flags.FIELD_CONST) != 0 ? owner + " is const, so it has a Constant row (Q98)" : null;
        }
        String problem = rowProblem(field, owner, module);
        if (problem != null) {
            return problem;
        }
        problem = valueProblem(field.type(), value, module);
        return problem == null ? null : owner + ": " + problem;
    }

    /** The first rule that a Constant row of the field breaks whatever its value, or null. */
    static @Nullable String rowProblem(FieldDef field, String owner, String module) {
        if ((field.flags() & Flags.FIELD_CONST) == 0) {
            return owner + " has a Constant row, but is not const (Q98)";
        }
        String problem = typeProblem(field.type(), module);
        return problem == null ? null : owner + ": " + problem;
    }

    /** The first rule that a value of a field of the type breaks, or null. */
    static @Nullable String valueProblem(TypeSig type, Constant value, String module) {
        String problem = typeProblem(type, module);
        if (problem != null) {
            return problem;
        }
        boolean text = string(type, module);
        if (text != value instanceof Constant.Text) {
            return "the Constant of a " + Signatures.name(type) + " is " + (text ? "a string" : "a number") + " (Q98)";
        }
        return text || fits(((TypeSig.Primitive) type).code(), ((Constant.Bits) value).bits()) ? null
                : "the Constant does not fit " + Signatures.name(type) + " (Q98)";
    }

    /** The blob of the value of a field of the type, which breaks no rule: a string holds the offset of its literal. */
    static byte[] encode(TypeSig type, Constant value, HeapWriter literals) {
        var out = new ByteSink();
        if (value instanceof Constant.Text text) {
            out.uleb(literals.literal(text.text()));
            return out.toByteArray();
        }
        long bits = ((Constant.Bits) value).bits();
        // A boolean, 0 or 1, is one byte in ULEB128, as §3.1 gives it.
        switch (((TypeSig.Primitive) type).code()) {
            case SigCode.I8, SigCode.I16, SigCode.I32, SigCode.I64 -> out.sleb(bits);
            case SigCode.F32 -> out.u32((int) bits);
            case SigCode.F64 -> out.u64(bits);
            default -> out.uleb64(bits);
        }
        return out.toByteArray();
    }

    /** Decodes the value of a field of the type from its blob, which it fills; a string reads its literal. */
    static Constant decode(ByteSource in, TypeSig type, HeapReader literals, String module) throws ModuleFormatException {
        int at = in.position();
        String problem = typeProblem(type, module);
        if (problem != null) {
            throw new ModuleFormatException(at, problem);
        }
        Constant value;
        if (string(type, module)) {
            value = new Constant.Text(literals.literal(in.uleb(), at));
        } else {
            value = new Constant.Bits(switch (((TypeSig.Primitive) type).code()) {
                case SigCode.I8, SigCode.I16, SigCode.I32, SigCode.I64 -> in.sleb64();
                case SigCode.F32 -> in.u32() & 0xFFFFFFFFL;
                case SigCode.F64 -> in.u64();
                default -> in.uleb64();
            });
        }
        if (!in.atEnd()) {
            throw new ModuleFormatException(in.position(), "the Constant ends before its blob does (Q98)");
        }
        return value;
    }

    // Why no Constant holds a value of the type yet, or null: until later stages a Constant holds a primitive or
    // a reflect.core.String (Q98).
    private static @Nullable String typeProblem(TypeSig type, String module) {
        boolean primitive = type instanceof TypeSig.Primitive p && p.code() >= SigCode.BOOLEAN && p.code() <= SigCode.F64;
        return primitive || string(type, module) ? null : "a Constant holds a primitive or a reflect.core.String until"
                + " later stages, not " + Signatures.name(type) + " (Q98)";
    }

    // Whether the type is the class reflect.core.String of the module reflect.core, by name.
    private static boolean string(TypeSig type, String module) {
        if (!(type instanceof TypeSig.Named named) || named.valueType()) {
            return false;
        }
        if (named.type() instanceof TypeRef ref) {
            return ref.scope() instanceof ModuleRef required && required.name().equals(CORE)
                    && ref.namespace().equals(CORE) && ref.name().equals("String");
        }
        // A nested type has namespace 0 (§3.1), so this namespace leaves it out.
        return named.type() instanceof TypeDef def && module.equals(CORE) && def.namespace().equals(CORE)
                && def.name().equals("String");
    }

    // Whether the value fits the primitive: a boolean is 0 or 1, an integer and a char lie in the range of their
    // type, and a float has 32 bits (§3.1).
    private static boolean fits(int code, long bits) {
        return switch (code) {
            case SigCode.BOOLEAN -> bits == 0 || bits == 1;
            case SigCode.I8 -> bits == (byte) bits;
            case SigCode.I16 -> bits == (short) bits;
            case SigCode.I32 -> bits == (int) bits;
            case SigCode.U8 -> (bits & ~0xFFL) == 0;
            case SigCode.U16, SigCode.CHAR -> (bits & ~0xFFFFL) == 0;
            case SigCode.U32, SigCode.F32 -> (bits & ~0xFFFFFFFFL) == 0;
            default -> true;
        };
    }
}
