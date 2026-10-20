package reflect.stage0.bytecode;

/** The table IDs of BC-11 and the layouts of bytecode.md §3.1. */
public final class TableId {
    public static final int MODULE = 0x01;
    public static final int MODULE_REF = 0x02;
    public static final int EXPORT = 0x03;
    public static final int SERVICE = 0x04;
    public static final int TYPE_DEF = 0x05;
    public static final int TYPE_REF = 0x06;
    public static final int TYPE_SPEC = 0x07;
    public static final int FIELD = 0x08;
    public static final int METHOD = 0x09;
    public static final int PARAM = 0x0A;
    public static final int GENERIC_PARAM = 0x0B;
    public static final int GENERIC_CONSTRAINT = 0x0C;
    public static final int INTERFACE_IMPL = 0x0D;
    public static final int OVERRIDE = 0x0E;
    public static final int MEMBER_REF = 0x0F;
    public static final int METHOD_SPEC = 0x10;
    public static final int PROPERTY = 0x11;
    public static final int EVENT = 0x12;
    public static final int ACCESSOR = 0x13;
    public static final int ANNOTATION = 0x14;
    public static final int CONSTANT = 0x15;
    public static final int PROVENANCE = 0x16;
    public static final int TUPLE_NAMES = 0x17;

    private TableId() {
    }

    /** The number of u32 columns of a table, or 0 when this format has no layout for it. */
    public static int columns(int table) {
        return switch (table) {
            case MODULE -> 5;
            case MODULE_REF -> 2;
            case EXPORT -> 3;
            case TYPE_DEF -> 10;
            case TYPE_REF -> 3;
            case TYPE_SPEC -> 1;
            case FIELD -> 3;
            case METHOD -> 6;
            case PARAM -> 2;
            case GENERIC_PARAM -> 4;
            case GENERIC_CONSTRAINT -> 2;
            case INTERFACE_IMPL -> 2;
            case OVERRIDE -> 3;
            case MEMBER_REF -> 3;
            case METHOD_SPEC -> 2;
            case CONSTANT -> 2;
            default -> 0;
        };
    }

    public static int rowSize(int table) {
        return 4 * columns(table);
    }
}
