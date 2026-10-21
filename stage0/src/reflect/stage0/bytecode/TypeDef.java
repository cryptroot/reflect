package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/**
 * A TypeDef row: a type that the module defines (BC-11). A nested type has an enclosing type and
 * the empty namespace; it takes its namespace from its outermost enclosing type (§3.1).
 */
public final class TypeDef implements TypeHandle {
    private final String namespace;
    private final String name;
    private final int kind;
    private final int flags;
    private final int backing;
    private final @Nullable TypeDef enclosing;
    private @Nullable TypeHandle base;

    /** backing is the signature code of an enum's backing type, and 0 for other kinds. */
    public TypeDef(String namespace, String name, int kind, int flags, int backing, @Nullable TypeDef enclosing) {
        this.namespace = namespace;
        this.name = name;
        this.kind = kind;
        this.flags = flags;
        this.backing = backing;
        this.enclosing = enclosing;
    }

    public String namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    public int kind() {
        return kind;
    }

    public int flags() {
        return flags;
    }

    public int backing() {
        return backing;
    }

    public @Nullable TypeDef enclosing() {
        return enclosing;
    }

    public @Nullable TypeHandle base() {
        return base;
    }

    public void setBase(@Nullable TypeHandle base) {
        this.base = base;
    }

    @Override
    public String qualifiedName() {
        TypeDef outer = enclosing;
        if (outer != null) {
            return outer.qualifiedName() + "/" + name;
        }
        return namespace.isEmpty() ? name : namespace + "." + name;
    }
}
