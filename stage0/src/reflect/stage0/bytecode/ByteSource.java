package reflect.stage0.bytecode;

/**
 * Reads little-endian values from a range of a byte array (BC-10). Positions are indexes into the
 * whole array, so for a module file they are file offsets, and each error names one.
 */
public final class ByteSource {
    private final byte[] data;
    private final int end;
    private int position;

    public ByteSource(byte[] data) {
        this(data, 0, data.length);
    }

    public ByteSource(byte[] data, int start, int end) {
        if (start < 0 || start > end || end > data.length) {
            throw new IllegalArgumentException("Range " + start + " to " + end + " is outside the data");
        }
        this.data = data;
        this.end = end;
        this.position = start;
    }

    public int position() {
        return position;
    }

    public boolean atEnd() {
        return position == end;
    }

    public int remaining() {
        return end - position;
    }

    public int u8() throws ModuleFormatException {
        need(1);
        int value = data[position] & 0xFF;
        position++;
        return value;
    }

    public int u16() throws ModuleFormatException {
        need(2);
        return u8() | (u8() << 8);
    }

    public int u32() throws ModuleFormatException {
        need(4);
        return u16() | (u16() << 16);
    }

    public long u64() throws ModuleFormatException {
        need(8);
        return (u32() & 0xFFFFFFFFL) | ((long) u32() << 32);
    }

    public byte[] bytes(int count) throws ModuleFormatException {
        need(count);
        byte[] copy = new byte[count];
        for (int i = 0; i < count; i++) {
            copy[i] = data[position + i];
        }
        position += count;
        return copy;
    }

    /** Reads a ULEB128 value that is minimal and fits 32 bits (BC-24); values from 2^31 are negative. */
    public int uleb() throws ModuleFormatException {
        int start = position;
        long value = 0;
        int shift = 0;
        int b;
        do {
            if (shift == 35) {
                throw new ModuleFormatException(start, "ULEB128 value longer than 5 bytes");
            }
            b = leb128Byte(start, "ULEB128");
            value |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        if (value > 0xFFFFFFFFL) {
            throw new ModuleFormatException(start, "ULEB128 value does not fit 32 bits");
        }
        if (Leb128.sizeUnsigned((int) value) != position - start) {
            throw new ModuleFormatException(start, "ULEB128 value is not minimal");
        }
        return (int) value;
    }

    /** Reads an SLEB128 value that is minimal and fits 32 bits (BC-24). */
    public int sleb32() throws ModuleFormatException {
        int start = position;
        long value = sleb(start, 5);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ModuleFormatException(start, "SLEB128 value does not fit 32 bits");
        }
        minimal(start, value);
        return (int) value;
    }

    /** Reads an SLEB128 value that is minimal and fits 64 bits (BC-24). */
    public long sleb64() throws ModuleFormatException {
        int start = position;
        long value = sleb(start, 10);
        minimal(start, value);
        return value;
    }

    // Decodes at most maxBytes bytes; the tenth byte of a 64-bit value holds only bit 63 and its sign.
    private long sleb(int start, int maxBytes) throws ModuleFormatException {
        long value = 0;
        int shift = 0;
        int b;
        do {
            if (shift == 7 * maxBytes) {
                throw new ModuleFormatException(start, "SLEB128 value longer than " + maxBytes + " bytes");
            }
            b = leb128Byte(start, "SLEB128");
            if (shift == 63 && b != 0x00 && b != 0x7F) {
                throw new ModuleFormatException(start, "SLEB128 value does not fit 64 bits");
            }
            value |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        if (shift < 64 && (b & 0x40) != 0) {
            value |= -1L << shift;
        }
        return value;
    }

    private void minimal(int start, long value) throws ModuleFormatException {
        if (Leb128.sizeSigned(value) != position - start) {
            throw new ModuleFormatException(start, "SLEB128 value is not minimal");
        }
    }

    private int leb128Byte(int start, String kind) throws ModuleFormatException {
        if (position == end) {
            throw new ModuleFormatException(start, "truncated " + kind + " value");
        }
        int value = data[position] & 0xFF;
        position++;
        return value;
    }

    private void need(int count) throws ModuleFormatException {
        if (count < 0 || count > end - position) {
            throw new ModuleFormatException(position, "needs " + count + " bytes, but " + (end - position) + " remain");
        }
    }
}
