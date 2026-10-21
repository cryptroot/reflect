package reflect.stage0.bytecode;

/**
 * A module that breaks a rule of the format, or content that the writer cannot encode within the
 * limits of BC-21. A defect in a file names its offset there.
 */
public final class ModuleFormatException extends Exception {
    private final int offset;

    public ModuleFormatException(int offset, String message) {
        super(offset < 0 ? message : "offset " + hex(offset) + ": " + message);
        this.offset = offset;
    }

    public ModuleFormatException(String message) {
        this(-1, message);
    }

    /** The file offset of the defect, or -1 when the defect is not in a file. */
    public int offset() {
        return offset;
    }

    static String hex(long value) {
        String digits = "0123456789ABCDEF";
        var text = new StringBuilder();
        long rest = value;
        do {
            text.insert(0, digits.charAt((int) (rest & 0xF)));
            rest >>>= 4;
        } while (rest != 0);
        return "0x" + text;
    }
}
