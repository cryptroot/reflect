package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * A module in memory (README §7.3): the tables as lists of rows, with references between rows as
 * objects and names as strings. The writer sorts and numbers the rows and builds the heaps.
 */
public final class ModuleDef {
    private final String name;
    private final int major;
    private final int minor;
    private final int patch;
    private boolean unsafe;
    private @Nullable TypeDef main;
    private final List<ModuleRef> requires = new ArrayList<>();
    private final List<TypeDef> types = new ArrayList<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();
    private final List<MemberRef> memberRefs = new ArrayList<>();
    private final List<Section> ancillary = new ArrayList<>();

    /** The version components are u32 values (BLD-01). */
    public ModuleDef(String name, int major, int minor, int patch) {
        this.name = name;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public String name() {
        return name;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** The header flag UNSAFE: the module declares unsafe (UNS-02). */
    public boolean unsafe() {
        return unsafe;
    }

    public void setUnsafe(boolean unsafe) {
        this.unsafe = unsafe;
    }

    /** The descriptor's main class (VM-12), or null. */
    public @Nullable TypeDef main() {
        return main;
    }

    public void setMain(@Nullable TypeDef main) {
        this.main = main;
    }

    /** The ModuleRef rows, in any order. */
    public List<ModuleRef> requires() {
        return requires;
    }

    /** The TypeDef rows, nested types included, in any order. */
    public List<TypeDef> types() {
        return types;
    }

    /** The TypeRef rows, in any order. */
    public List<TypeRef> typeRefs() {
        return typeRefs;
    }

    /** The MemberRef rows, in any order. */
    public List<MemberRef> memberRefs() {
        return memberRefs;
    }

    /**
     * Ancillary sections kept as bytes and written back unchanged: those that the reader does not
     * know, and a stale DEBUG (BC-20). A current DEBUG comes from the bodies instead.
     */
    public List<Section> ancillary() {
        return ancillary;
    }
}
