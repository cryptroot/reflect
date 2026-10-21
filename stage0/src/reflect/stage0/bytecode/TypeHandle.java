package reflect.stage0.bytecode;

/** What a type token or a named type in a signature names: a TypeDef or a TypeRef row (BC-03, BC-11). */
public sealed interface TypeHandle extends Handle permits TypeDef, TypeRef {
    /** The name that GEN-12 spells: dotted namespace and name, with "/" before each nested type. */
    String qualifiedName();
}
