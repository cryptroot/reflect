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
    public static final int SYNTAX = 101;
    public static final int RANGE = 103;
    public static final int REPEATED = 104;
    public static final int NO_TYPE = 201;
    public static final int NO_MODULE = 202;
    public static final int NOT_REQUIRED = 203;
    public static final int NO_INSTRUCTION = 204;
    public static final int NO_MEMBER = 205;
    public static final int NO_LABEL = 206;
    public static final int NO_VARIABLE = 207;
    public static final int NO_SOURCE = 208;
    public static final int FORMAT = 301;
    public static final int LIMIT = 302;
    public static final int LATER = 303;
    public static final int STACK = 304;

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
