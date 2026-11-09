package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/** A Field row: a field of the type that lists it, with its flags, name and type, its FIELD signature (BC-11). */
public final class FieldDef implements FieldHandle {
    private final int flags;
    private final String name;
    private final TypeSig type;
    private @Nullable Constant constant;

    public FieldDef(int flags, String name, TypeSig type) {
        this.flags = flags;
        this.name = name;
        this.type = type;
    }

    public int flags() {
        return flags;
    }

    public String name() {
        return name;
    }

    public TypeSig type() {
        return type;
    }

    /** The value of the field's Constant row, which a const field has (Q98), or null. */
    public @Nullable Constant constant() {
        return constant;
    }

    public void setConstant(@Nullable Constant constant) {
        this.constant = constant;
    }
}
