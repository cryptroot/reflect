package reflect.stage0.vm;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import reflect.stage0.lib.Nullable;

/**
 * The natives of VM0 (UNS-03, LIB-03, VM-08): Java methods in a fixed table, keyed by the text of
 * their signatures (Spelling). Each entry names the module that may declare it, so the trusted
 * modules are those that the table names.
 */
final class Natives {
    /** A native: the arguments, this first unless it is static, give the result, or null for void. */
    @FunctionalInterface
    interface Native {
        @Nullable Object call(Vm vm, Object[] arguments);
    }

    private record Entry(String module, Native code) {
    }

    // The text of a number is Java's (NUM-05, Q87).
    private static final Map<String, Entry> TABLE = Map.of(
            "static reflect.core.String reflect.core.String::valueOf(reflect.core.Object?)",
            new Entry("reflect.core", Natives::valueOf),
            "static reflect.core.String reflect.core.String::valueOf(int)",
            new Entry("reflect.core", (vm, arguments) -> Integer.toString((Integer) arguments[0])),
            "static reflect.core.String reflect.core.String::concat(reflect.core.String, reflect.core.String)",
            new Entry("reflect.core", (vm, arguments) -> ((String) arguments[0]).concat((String) arguments[1])),
            "static void reflect.io.Console::write(int, reflect.core.String)",
            new Entry("reflect.io", Natives::write));

    private Natives() {
    }

    /** Whether a module may declare natives: only the trusted modules that the table names (LIB-03). */
    static boolean trusted(String module) {
        for (Entry entry : TABLE.values()) {
            if (entry.module().equals(module)) {
                return true;
            }
        }
        return false;
    }

    /** The native of a module with this signature, or null when VM0 has none. */
    static @Nullable Native find(String module, String signature) {
        Entry entry = TABLE.get(signature);
        return entry == null || !entry.module().equals(module) ? null : entry.code();
    }

    // String.valueOf(Object?): "null", or the string itself. Other objects arrive in v0.0.6.
    private static Object valueOf(Vm vm, Object[] arguments) {
        Object value = arguments[0];
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        throw new Stop("VM0 turns no object but a string into text yet");
    }

    // Console.write(int, String) writes the UTF-8 bytes of the text to standard output (1) or standard
    // error (2), as rfld does, since the charset of the container's terminal is ASCII (sketchpad D.5).
    private static @Nullable Object write(Vm vm, Object[] arguments) {
        int fd = (Integer) arguments[0];
        if (fd != 1 && fd != 2) {
            throw new Fault("reflect.io.IOException", "VM0 writes to file descriptors 1 and 2 only, not " + fd);
        }
        vm.write(fd, utf8((String) arguments[1]));
        return null;
    }

    // UTF-8 has no form for an unpaired surrogate, which a string may hold (NUM-05); U+FFFD replaces it,
    // as the Unicode standard recommends for a conversion.
    private static byte[] utf8(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF,
                    (byte) 0xBD});
        try {
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(text));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("the encoder replaces every defect", e);
        }
    }
}
