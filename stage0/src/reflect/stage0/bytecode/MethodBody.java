package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;

/**
 * The body of a method (BC-17): its maximum stack depth, the types of its locals, which form its
 * LOCALS signature, its instructions in code order, its exception regions (BC-18) and its stack map
 * frames (BC-19); and its line entries and the names of its locals, which DEBUG holds (§6.1).
 */
public final class MethodBody {
    private int maxStack;
    private final List<TypeSig> locals = new ArrayList<>();
    private final List<Instruction> code = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<LocalName> localNames = new ArrayList<>();

    public MethodBody(int maxStack) {
        this.maxStack = maxStack;
    }

    public int maxStack() {
        return maxStack;
    }

    /** Sets the maximum stack depth, which rfla knows only once it has read the instructions. */
    public void setMaxStack(int maxStack) {
        this.maxStack = maxStack;
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

    /** The stack map frames, in any order; the writer sorts them by offset (BC-19). */
    public List<Frame> frames() {
        return frames;
    }

    /** The line entries, in any order; the writer sorts them by offset (BC-24). */
    public List<Line> lines() {
        return lines;
    }

    /** The names of the locals that have one, in any order; the writer sorts them by index (BC-24). */
    public List<LocalName> localNames() {
        return localNames;
    }

    /** Marks the label before the next instruction that the code gains, or at its end. */
    public void mark(Label label) {
        label.mark(this, code.size());
    }
}
