package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * A Method row: a method of the type that lists it, with its flags, name, METHOD signature and
 * Param rows (BC-11), and its body unless it is ABSTRACT, NATIVE or EXTERN. A constructor is named
 * new, and a static initializer static (§3.1).
 */
public final class MethodDef implements MethodHandle {
    private final int flags;
    private final String name;
    private final MethodSig signature;
    private final List<ParamDef> params = new ArrayList<>();
    private @Nullable MethodBody body;

    public MethodDef(int flags, String name, MethodSig signature) {
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

    public MethodSig signature() {
        return signature;
    }

    /** The Param rows, one per parameter of the signature, in order. */
    public List<ParamDef> params() {
        return params;
    }

    public @Nullable MethodBody body() {
        return body;
    }

    public void setBody(@Nullable MethodBody body) {
        this.body = body;
    }
}
