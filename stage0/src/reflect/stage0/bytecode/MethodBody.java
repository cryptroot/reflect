package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;

/**
 * The body of a method (BC-17): its maximum stack depth, the types of its locals, which form its
 * LOCALS signature, its instructions in code order, and its exception regions (BC-18).
 */
public final class MethodBody {
    private final int maxStack;
    private final List<TypeSig> locals = new ArrayList<>();
    private final List<Instruction> code = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();

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

    /** The exception regions, in any order; the writer sorts them (BC-18). */
    public List<Region> regions() {
        return regions;
    }

    /** Marks the label before the next instruction that the code gains, or at its end. */
    public void mark(Label label) {
        label.mark(this, code.size());
    }
}
