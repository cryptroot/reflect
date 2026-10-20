package reflect.stage0.bytecode;

/** The limits of the format (BC-21), which the writer, rfla and every reader enforce. */
public final class Limits {
    public static final int MAX_ROWS = 16_777_215;
    public static final long MAX_SIZE = 4_294_967_295L;
    public static final int MAX_CODE_SIZE = 16_777_215;
    public static final int MAX_LOCALS = 65_535;
    public static final int MAX_STACK = 65_535;
    public static final int MAX_PARAMS = 65_535;
    public static final int MAX_REGIONS = 65_535;
    public static final int MAX_SIGNATURE_DEPTH = 255;
    public static final int MAX_IDENTIFIER_BYTES = 65_535;

    private Limits() {
    }
}
