package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/**
 * A position in the code of one body: the instruction that follows the place where the label is
 * marked, or the end of the code. Branches name their targets by label, and the writer turns
 * labels into offsets (BC-17).
 */
public final class Label {
    private @Nullable MethodBody body;
    private int index = -1;

    /** The body that marks the label, or null until it is marked. */
    public @Nullable MethodBody body() {
        return body;
    }

    /** The index of the instruction that follows the label, or -1 until it is marked. */
    public int index() {
        return index;
    }

    void mark(MethodBody body, int index) {
        if (this.body != null) {
            throw new IllegalStateException("The label is marked already");
        }
        this.body = body;
        this.index = index;
    }
}
