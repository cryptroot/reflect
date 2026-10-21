package reflect.stage0.bytecode;

import reflect.stage0.lib.Nullable;

/** Bytes that compare by value, so equal entries share one heap entry (BC-24). */
public final class Blob {
    private final byte[] bytes;

    public Blob(byte[] bytes) {
        this.bytes = copy(bytes);
    }

    public int length() {
        return bytes.length;
    }

    public byte[] toByteArray() {
        return copy(bytes);
    }

    /** Compares as unsigned bytes, then by length: the order of keys in BC-11. */
    public static int compare(byte[] left, byte[] right) {
        int common = left.length < right.length ? left.length : right.length;
        for (int i = 0; i < common; i++) {
            int a = left[i] & 0xFF;
            int b = right[i] & 0xFF;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return left.length == right.length ? 0 : left.length < right.length ? -1 : 1;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Blob blob && compare(bytes, blob.bytes) == 0;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (byte b : bytes) {
            hash = 31 * hash + b;
        }
        return hash;
    }

    private static byte[] copy(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
