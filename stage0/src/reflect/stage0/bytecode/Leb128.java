package reflect.stage0.bytecode;

/**
 * The sizes of minimal LEB128 encodings (BC-10, BC-24). ByteSink writes the encodings, and
 * ByteSource reads them back and rejects the ones that are not minimal or do not fit.
 */
public final class Leb128 {
    private Leb128() {
    }

    /** The number of bytes of a u32 value in ULEB128. */
    public static int sizeUnsigned(int value) {
        long rest = value & 0xFFFFFFFFL;
        int size = 1;
        while (rest >= 0x80) {
            rest >>>= 7;
            size++;
        }
        return size;
    }

    /** The number of bytes of a value in SLEB128. */
    public static int sizeSigned(long value) {
        long rest = value;
        int size = 1;
        while (rest < -0x40 || rest >= 0x40) {
            rest >>= 7;
            size++;
        }
        return size;
    }
}
