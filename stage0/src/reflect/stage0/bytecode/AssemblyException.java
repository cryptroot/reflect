package reflect.stage0.bytecode;

/**
 * A defect in .rfla text, which rfla reports instead of a module (Q79): a code of bytecode.md
 * §8.2, and the line and column where the defect starts, which count code points from 1.
 */
public final class AssemblyException extends Exception {
    private final int code;
    private final int line;
    private final int column;

    public AssemblyException(int code, int line, int column, String message) {
        super(line + ":" + column + ": " + Diagnostic.name(code) + ": " + message);
        this.code = code;
        this.line = line;
        this.column = column;
    }

    public int code() {
        return code;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
