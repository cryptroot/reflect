package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * A stack map frame of a body (BC-19): at a branch target or handler start, the verification types
 * of the arguments and locals, then of the stack from its bottom. Stage0 stores frames as given
 * (Q50).
 */
public record Frame(Label at, List<Frame.VType> slots, List<Frame.VType> stack) {
    /** A verification type: its tag, and the type that REF, UNINIT, BYREF, BYREF_READONLY, STRUCT and CLASS name. */
    public record VType(int tag, @Nullable TypeHandle type) {
    }
}
