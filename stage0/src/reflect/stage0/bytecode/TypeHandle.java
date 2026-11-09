package reflect.stage0.bytecode;

/**
 * What a type token names: a TypeDef, TypeRef or TypeSpec row (BC-04, BC-11). A named type in a
 * signature is a TypeDef or TypeRef row (BC-03).
 */
public sealed interface TypeHandle extends Handle permits TypeDef, TypeRef, TypeSpec {
    /** The name that GEN-12 spells: dotted namespace and name, with "/" before each nested type; a TypeSpec spells its type. */
    String qualifiedName();
}
