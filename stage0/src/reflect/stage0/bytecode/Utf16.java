package reflect.stage0.bytecode;

/**
 * UTF-16LE code units for LITERALS (BC-02), in R0-shaped Java (Q47). Each unit is two bytes, and
 * an unpaired surrogate stays as it is, so every string has exactly one encoding.
 */
public final class Utf16 {
    private Utf16() {
    }

    public static byte[] encode(String text) {
        var sink = new ByteSink();
        for (int i = 0; i < text.length(); i++) {
            sink.u16(text.charAt(i));
        }
        return sink.toByteArray();
    }

    /** Decodes the next units code units, two bytes each, of the source. */
    public static String decode(ByteSource source, int units) throws ModuleFormatException {
        if (units < 0 || units > source.remaining() / 2) {
            throw new ModuleFormatException(source.position(), "UTF-16LE text of " + units
                    + " code units runs past the end of its data");
        }
        var text = new StringBuilder();
        for (int i = 0; i < units; i++) {
            text.append((char) source.u16());
        }
        return text.toString();
    }
}
