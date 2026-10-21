package reflect.stage0.bytecode;

/** A Field row: a field of the type that lists it, with its flags, name and type, its FIELD signature (BC-11). */
public final class FieldDef {
    private final int flags;
    private final String name;
    private final TypeSig type;

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
}
