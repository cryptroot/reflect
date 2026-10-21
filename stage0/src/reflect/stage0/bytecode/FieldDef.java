package reflect.stage0.bytecode;

/** A Field row: a field of the type that lists it, with its flags, name and FIELD signature (BC-11). */
public final class FieldDef {
    private final int flags;
    private final String name;
    private final Blob signature;

    public FieldDef(int flags, String name, Blob signature) {
        this.flags = flags;
        this.name = name;
        this.signature = signature;
    }

    public int flags() {
        return flags;
    }

    public String name() {
        return name;
    }

    public Blob signature() {
        return signature;
    }
}
