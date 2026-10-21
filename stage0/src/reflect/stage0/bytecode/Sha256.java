package reflect.stage0.bytecode;

/** SHA-256 (FIPS 180-4) in R0-shaped Java, for the content hash (BC-22) and DEBUG (Q47). */
public final class Sha256 {
    private static final int[] ROUND_CONSTANTS = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    };

    public static final int SIZE = 32;

    private final int[] state = {
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };
    private final byte[] block = new byte[64];
    private final int[] schedule = new int[64];
    private int blockSize;
    private long length;
    private boolean finished;

    public Sha256() {
    }

    public static byte[] hash(byte[] data) {
        var sha = new Sha256();
        sha.update(data, 0, data.length);
        return sha.digest();
    }

    public void update(byte[] data) {
        update(data, 0, data.length);
    }

    public void update(byte[] data, int offset, int count) {
        if (finished) {
            throw new IllegalStateException("The digest is already computed");
        }
        for (int i = offset; i < offset + count; i++) {
            append(data[i]);
        }
        length += count;
    }

    /** Pads the message, and returns its hash; the object takes no more input. */
    public byte[] digest() {
        if (finished) {
            throw new IllegalStateException("The digest is already computed");
        }
        finished = true;
        long bits = length * 8;
        append((byte) 0x80);
        while (blockSize != 56) {
            append((byte) 0);
        }
        for (int shift = 56; shift >= 0; shift -= 8) {
            append((byte) (bits >>> shift));
        }
        byte[] hash = new byte[SIZE];
        for (int i = 0; i < 8; i++) {
            hash[4 * i] = (byte) (state[i] >>> 24);
            hash[4 * i + 1] = (byte) (state[i] >>> 16);
            hash[4 * i + 2] = (byte) (state[i] >>> 8);
            hash[4 * i + 3] = (byte) state[i];
        }
        return hash;
    }

    private void append(byte value) {
        block[blockSize] = value;
        blockSize++;
        if (blockSize == 64) {
            compress();
            blockSize = 0;
        }
    }

    private void compress() {
        for (int t = 0; t < 16; t++) {
            schedule[t] = (block[4 * t] & 0xFF) << 24 | (block[4 * t + 1] & 0xFF) << 16
                    | (block[4 * t + 2] & 0xFF) << 8 | (block[4 * t + 3] & 0xFF);
        }
        for (int t = 16; t < 64; t++) {
            int w15 = schedule[t - 15];
            int w2 = schedule[t - 2];
            int sigma0 = rotate(w15, 7) ^ rotate(w15, 18) ^ (w15 >>> 3);
            int sigma1 = rotate(w2, 17) ^ rotate(w2, 19) ^ (w2 >>> 10);
            schedule[t] = sigma1 + schedule[t - 7] + sigma0 + schedule[t - 16];
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];
        for (int t = 0; t < 64; t++) {
            int sum1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
            int choice = (e & f) ^ (~e & g);
            int t1 = h + sum1 + choice + ROUND_CONSTANTS[t] + schedule[t];
            int sum0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
            int majority = (a & b) ^ (a & c) ^ (b & c);
            int t2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }

    private static int rotate(int value, int count) {
        return (value >>> count) | (value << (32 - count));
    }
}
