package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;

/**
 * The body of a method (BC-17): its maximum stack depth, the types of its locals, which form its
 * LOCALS signature, and its instructions in code order.
 */
public final class MethodBody {
    private final int maxStack;
    private final List<TypeSig> locals = new ArrayList<>();
    private final List<Instruction> code = new ArrayList<>();

    public MethodBody(int maxStack) {
        this.maxStack = maxStack;
    }

    public int maxStack() {
        return maxStack;
    }

    /** The types of the locals, numbered from 0; a body without locals has none. */
    public List<TypeSig> locals() {
        return locals;
    }

    /** The instructions, in code order. */
    public List<Instruction> code() {
        return code;
    }
}
