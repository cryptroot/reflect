package reflect.stage0.bytecode;

/** The resolution scope of a TypeRef: the required module, or the TypeRef of the enclosing type (§3.1). */
public sealed interface TypeScope permits ModuleRef, TypeRef {
}
