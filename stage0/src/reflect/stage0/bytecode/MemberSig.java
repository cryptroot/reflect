package reflect.stage0.bytecode;

/** The signature of a member, as a MemberRef row holds it: FIELD or METHOD (BC-03, Q69). */
public sealed interface MemberSig permits FieldSig, MethodSig {
}
