package reflect.stage0.vm;

/**
 * Runtime.exit ends the run with its argument as the exit code (README §6.2). The exception unwinds
 * the Java frames of the interpreter to the start of the run.
 */
final class Exit extends RuntimeException {
    private final int code;

    Exit(int code) {
        // A jump, not an error: no message and no stack trace.
        super(null, null, false, false);
        this.code = code;
    }

    int code() {
        return code;
    }
}
