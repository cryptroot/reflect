package reflect.stage0.bytecode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;

public final class Sha256Test {
    @Test
    public static void knownAnswers() throws NoSuchAlgorithmException {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.hash(new byte[0])));
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.hash(abc)));
        assertEquals(hex(reference(new byte[0])), hex(Sha256.hash(new byte[0])));
        assertEquals(hex(reference(abc)), hex(Sha256.hash(abc)));
    }

    @Test
    public static void oneMebibyteOfSeededRandomBytes() throws NoSuchAlgorithmException {
        byte[] data = new byte[1 << 20];
        new Random(0x5EEDL).nextBytes(data);
        assertEquals(hex(reference(data)), hex(Sha256.hash(data)));
    }

    @Test
    public static void lengthsAroundThePaddingBoundaries() throws NoSuchAlgorithmException {
        var random = new Random(1);
        for (int length : new int[] {1, 55, 56, 57, 63, 64, 65, 119, 120, 128, 1000}) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            assertEquals(hex(reference(data)), hex(Sha256.hash(data)));
        }
    }

    @Test
    public static void updatesInPartsGiveTheHashOfTheWhole() throws NoSuchAlgorithmException {
        byte[] data = new byte[3000];
        new Random(2).nextBytes(data);
        var sha = new Sha256();
        int offset = 0;
        for (int part = 1; offset < data.length; part = part * 3 % 97 + 1) {
            int count = Math.min(part, data.length - offset);
            sha.update(data, offset, count);
            offset += count;
        }
        assertEquals(hex(reference(data)), hex(sha.digest()));
        assertThrows(IllegalStateException.class, sha::digest);
        assertThrows(IllegalStateException.class, () -> sha.update(data));
    }

    private static byte[] reference(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format("%02x", b & 0xFF));
        }
        return text.toString();
    }
}
