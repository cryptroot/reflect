package reflect.stage0.vm;

/**
 * A Reflect array (BOOT-04, VM-02): its run-time array type, and its elements in a Java array of their
 * storage kind (BC-04, Q106): byte[] for boolean, byte and ubyte, short[] for short, ushort and char,
 * int[], long[], float[] and double[] for the other primitives, and Object[] for references. Two arrays
 * are equal only when they are the same Java object, as ceq.ref compares them.
 */
final class ArrayInstance {
    private final RuntimeType type;
    private final Object elements;

    ArrayInstance(RuntimeType type, Object elements) {
        this.type = type;
        this.elements = elements;
    }

    RuntimeType type() {
        return type;
    }

    /** The Java array of the elements, which ldelem and stelem read and write in place. */
    Object elements() {
        return elements;
    }

    int length() {
        return switch (elements) {
            case byte[] bytes -> bytes.length;
            case short[] shorts -> shorts.length;
            case int[] ints -> ints.length;
            case long[] longs -> longs.length;
            case float[] floats -> floats.length;
            case double[] doubles -> doubles.length;
            default -> ((Object[]) elements).length;
        };
    }
}
