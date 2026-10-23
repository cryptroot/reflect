package reflect.stage0.vm;

/**
 * An exception that VM0 raises, by the name of its Reflect type. Until v0.0.8 makes exceptions
 * real, a fault stops VM0: it prints the type and the message as Throwable.toString does, and
 * exits with 1 (README §7.6, Q89).
 */
final class Fault extends RuntimeException {
    static final String TYPE_LOAD_ERROR = "reflect.core.TypeLoadError";

    private final String type;

    Fault(String type, String message) {
        super(message);
        this.type = type;
    }

    /** The qualified name of the exception's type (GEN-12). */
    String type() {
        return type;
    }
}
