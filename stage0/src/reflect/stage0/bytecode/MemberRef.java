package reflect.stage0.bytecode;

/**
 * A MemberRef row: a field or method of a type of another module, named by its parent type, its
 * name and the signature that its row holds, so a reader needs no other module (BC-11, Q69).
 */
public final class MemberRef {
    private final TypeHandle parent;
    private final String name;
    private final MemberSig signature;

    /** The parent is a TypeRef; a TypeDef's members are Field and Method rows. */
    public MemberRef(TypeHandle parent, String name, MemberSig signature) {
        this.parent = parent;
        this.name = name;
        this.signature = signature;
    }

    public TypeHandle parent() {
        return parent;
    }

    public String name() {
        return name;
    }

    public MemberSig signature() {
        return signature;
    }
}
