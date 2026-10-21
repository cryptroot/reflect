package reflect.stage0.bytecode;

/** A ModuleRef row: a module that this one requires, with the ModuleRef flags (BC-11). */
public final class ModuleRef {
    private final String name;
    private final int flags;

    public ModuleRef(String name, int flags) {
        this.name = name;
        this.flags = flags;
    }

    public String name() {
        return name;
    }

    public int flags() {
        return flags;
    }
}
