package reflect.stage0.bytecode;

/** The verification types of stack map frames (BC-19). */
public final class VerificationType {
    public static final int I32 = 0x01;
    public static final int I64 = 0x02;
    public static final int F32 = 0x03;
    public static final int F64 = 0x04;
    public static final int NULL = 0x05;
    public static final int REF = 0x06;
    public static final int UNINIT = 0x07;
    public static final int BYREF = 0x08;
    public static final int BYREF_READONLY = 0x09;
    public static final int PTR = 0x0A;
    public static final int STRUCT = 0x0B;
    public static final int CLASS = 0x0C;

    private VerificationType() {
    }

    /** Whether a token of the type follows the tag. */
    public static boolean hasToken(int tag) {
        return switch (tag) {
            case REF, UNINIT, BYREF, BYREF_READONLY, STRUCT, CLASS -> true;
            default -> false;
        };
    }
}
