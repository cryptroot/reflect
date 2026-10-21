package reflect.stage0.bytecode;

/** A row that a token in a signature or an instruction names: a type, a field or a method (BC-04, BC-11). */
public sealed interface Handle permits TypeHandle, FieldHandle, MethodHandle {
}
