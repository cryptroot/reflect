package reflect.stage0.bytecode;

/** What the token of a call or newobj names: a Method row, or a MemberRef with a METHOD signature (BC-04). */
public sealed interface MethodHandle extends Handle permits MethodDef, MemberRef {
}
