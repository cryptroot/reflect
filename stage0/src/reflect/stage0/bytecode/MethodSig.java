package reflect.stage0.bytecode;

import reflect.stage0.lib.List;

/**
 * A METHOD signature (BC-03): the METHOD flags, the number of type parameters, which is at least
 * 1 exactly when the flags hold GENERIC, the return type and the parameter types.
 */
public record MethodSig(int flags, int typeParams, TypeSig returnType, List<TypeSig> params) implements MemberSig {
}
