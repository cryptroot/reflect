package reflect.stage0.bytecode;

/**
 * A TypeRef row: a type of another module (BC-11). Its scope is the ModuleRef of that module, or
 * the TypeRef of its enclosing type, and then its namespace is empty (§3.1).
 */
public final class TypeRef implements TypeHandle, TypeScope {
    private final TypeScope scope;
    private final String namespace;
    private final String name;

    public TypeRef(TypeScope scope, String namespace, String name) {
        this.scope = scope;
        this.namespace = namespace;
        this.name = name;
    }

    public TypeScope scope() {
        return scope;
    }

    public String namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    @Override
    public String qualifiedName() {
        if (scope instanceof TypeRef outer) {
            return outer.qualifiedName() + "/" + name;
        }
        return namespace.isEmpty() ? name : namespace + "." + name;
    }
}
