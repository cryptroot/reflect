package reflect.stage0.bytecode;

/**
 * The codes of the diagnostics of rfla, as bytecode.md §8.2 lists them (Q79): RFA00xx for the
 * characters of the text, RFA01xx for its syntax, RFA02xx for names and RFA03xx for content.
 */
public final class Diagnostic {
    public static final int CHARACTER = 1;
    public static final int UNCLOSED = 2;
    public static final int ESCAPE = 3;
    public static final int ENCODING = 4;

    private Diagnostic() {
    }

    /** The code as text: RFA and four digits. */
    public static String name(int code) {
        String digits = String.valueOf(code);
        while (digits.length() < 4) {
            digits = "0" + digits;
        }
        return "RFA" + digits;
    }
}
