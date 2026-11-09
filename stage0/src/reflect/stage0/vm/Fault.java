package reflect.stage0.vm;

/**
 * An exception that VM0 raises, by the name of its Reflect type. Until v0.0.8 makes exceptions
 * real, a fault stops VM0: it prints the type and the message as Throwable.toString does, and
 * exits with 1 (README §7.6, Q89).
 */
final class Fault extends RuntimeException {
    static final String ABSTRACT_MEMBER_ERROR = "reflect.core.AbstractMemberError";
    static final String ARITHMETIC_EXCEPTION = "reflect.core.ArithmeticException";
    static final String CLASS_CAST_EXCEPTION = "reflect.core.ClassCastException";
    static final String EXCEPTION_IN_INITIALIZER_ERROR = "reflect.core.ExceptionInInitializerError";
    static final String NULL_POINTER_EXCEPTION = "reflect.core.NullPointerException";
    static final String STACK_OVERFLOW_ERROR = "reflect.core.StackOverflowError";
    static final String TYPE_LOAD_ERROR = "reflect.core.TypeLoadError";
    // The faults whose types are errors, not exceptions (EXC-05). StackOverflowError is the JVM's own.
    private static final String[] ERRORS = {ABSTRACT_MEMBER_ERROR, EXCEPTION_IN_INITIALIZER_ERROR, TYPE_LOAD_ERROR};

    private final String type;

    Fault(String type, String message) {
        super(message);
        this.type = type;
    }

    /** The qualified name of the exception's type (GEN-12). */
    String type() {
        return type;
    }

    /** Whether the type is an error of EXC-05, which a static initializer passes on unchanged (Q102). */
    boolean isError() {
        for (String error : ERRORS) {
            if (error.equals(type)) {
                return true;
            }
        }
        return false;
    }
}
