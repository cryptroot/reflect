package reflect.stage0.bytecode;

/** A Param row: the flags and the name of a parameter; the empty name means none (BC-11). */
public final class ParamDef {
    private final int flags;
    private final String name;

    public ParamDef(int flags, String name) {
        this.flags = flags;
        this.name = name;
    }

    public int flags() {
        return flags;
    }

    public String name() {
        return name;
    }
}
