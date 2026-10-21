package reflect.stage0.bytecode;

/** A growing array of bytes, written little-endian (BC-10). */
public final class ByteSink {
    private byte[] data = new byte[64];
    private int size;

    public ByteSink() {
    }

    public int size() {
        return size;
    }

    public void u8(int value) {
        if (size == data.length) {
            byte[] larger = new byte[2 * data.length];
            for (int i = 0; i < size; i++) {
                larger[i] = data[i];
            }
            data = larger;
        }
        data[size] = (byte) value;
        size++;
    }

    public void u16(int value) {
        u8(value);
        u8(value >>> 8);
    }

    public void u32(int value) {
        u16(value);
        u16(value >>> 16);
    }

    public void u64(long value) {
        u32((int) value);
        u32((int) (value >>> 32));
    }

    public void bytes(byte[] source) {
        for (byte b : source) {
            u8(b);
        }
    }

    public void zeros(int count) {
        for (int i = 0; i < count; i++) {
            u8(0);
        }
    }

    /** Writes a u32 value in minimal ULEB128. */
    public void uleb(int value) {
        long rest = value & 0xFFFFFFFFL;
        while (rest >= 0x80) {
            u8((int) (rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        u8((int) rest);
    }

    /** Writes a value in minimal SLEB128. */
    public void sleb(long value) {
        long rest = value;
        while (rest < -0x40 || rest >= 0x40) {
            u8((int) (rest & 0x7F) | 0x80);
            rest >>= 7;
        }
        u8((int) (rest & 0x7F));
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[size];
        for (int i = 0; i < size; i++) {
            copy[i] = data[i];
        }
        return copy;
    }
}
