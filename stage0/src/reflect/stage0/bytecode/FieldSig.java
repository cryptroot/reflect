package reflect.stage0.bytecode;

/** A FIELD signature: the type of a field (BC-03). */
public record FieldSig(TypeSig type) implements MemberSig {
}
