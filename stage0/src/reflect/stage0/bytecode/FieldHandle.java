package reflect.stage0.bytecode;

/** What the token of a field instruction names: a Field row, or a MemberRef with a FIELD signature (BC-04). */
public sealed interface FieldHandle extends Handle permits FieldDef, MemberRef {
}
