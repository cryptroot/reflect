package reflect.stage0.bytecode;

/**
 * A TypeSpec row: a type that a token names when no TypeDef or TypeRef row is that type, such as a
 * primitive, a nullable type or an array (BC-11, Q99). Its blob is a bare type (BC-03).
 */
public final class TypeSpec implements TypeHandle {
    private final TypeSig type;

    public TypeSpec(TypeSig type) {
        this.type = type;
    }

    public TypeSig type() {
        return type;
    }

    @Override
    public String qualifiedName() {
        return Signatures.name(type);
    }
}
