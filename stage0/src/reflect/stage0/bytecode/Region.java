package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/**
 * An exception region of a body (BC-18): CATCH or FINALLY, a try and a handler, each from the
 * instruction at its start label to its end label, and for a catch the type that it catches.
 */
public record Region(int kind, Label tryStart, Label tryEnd, Label handlerStart, Label handlerEnd,
        @Nullable TypeHandle type) {
}
