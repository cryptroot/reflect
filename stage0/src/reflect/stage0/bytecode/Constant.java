package reflect.stage0.bytecode;

/**
 * The value of a Constant row, which a const field holds (BC-11, Q98): a number of the field's primitive type,
 * or a reflect.core.String.
 */
public sealed interface Constant permits Constant.Bits, Constant.Text {
    /**
     * A boolean as 0 or 1, an integer or a char as its value, which a signed type sign-extends to 64 bits, and a
     * float or a double as its IEEE 754 bits.
     */
    record Bits(long bits) implements Constant {
    }

    /** A string, as its UTF-16 code units. */
    record Text(String text) implements Constant {
    }
}
