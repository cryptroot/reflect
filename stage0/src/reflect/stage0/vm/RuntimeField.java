package reflect.stage0.vm;

import reflect.stage0.bytecode.FieldDef;
import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeSig;

/**
 * A field that VM0 has linked (VM-16): the run-time type that declares it, its row, and its slot in
 * the objects of that type and of its subclasses, or in the static storage of that type.
 */
final class RuntimeField {
    private final RuntimeType owner;
    private final FieldDef definition;
    private final int slot;

    RuntimeField(RuntimeType owner, FieldDef definition, int slot) {
        this.owner = owner;
        this.definition = definition;
        this.slot = slot;
    }

    RuntimeType owner() {
        return owner;
    }

    FieldDef definition() {
        return definition;
    }

    boolean isStatic() {
        return (definition.flags() & Flags.FIELD_STATIC) != 0;
    }

    boolean isFinal() {
        return (definition.flags() & Flags.FIELD_FINAL) != 0;
    }

    /** The slot of the field: in the fields of an Instance, or for a static field in the statics of its owner. */
    int slot() {
        return slot;
    }

    /**
     * The value that the field holds after a store of this value (BC-04, Q104): a field of type
     * boolean, byte, ubyte, short, ushort or char keeps the low 8 or 16 bits of the i32, which a load
     * gives back sign-extended for byte and short and zero-extended for the others.
     */
    Object stored(Object value) {
        if (definition.type() instanceof TypeSig.Primitive primitive) {
            return switch (primitive.code()) {
                case SigCode.I8 -> (int) (byte) (int) (Integer) value;
                case SigCode.BOOLEAN, SigCode.U8 -> (Integer) value & 0xFF;
                case SigCode.I16 -> (int) (short) (int) (Integer) value;
                case SigCode.U16, SigCode.CHAR -> (Integer) value & 0xFFFF;
                default -> value;
            };
        }
        return value;
    }

    /** The name that messages give: the type of the field, then its owner and name after "::". */
    String name() {
        return Spelling.field(owner.name(), definition.name(), definition.type(), owner.module().name(), false);
    }
}
