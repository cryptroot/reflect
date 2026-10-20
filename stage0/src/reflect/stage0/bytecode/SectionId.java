package reflect.stage0.bytecode;

/** The section IDs of BC-02; bit 31 marks an ancillary section (BC-20). */
public final class SectionId {
    public static final int ANCILLARY = 0x80000000;

    public static final int IDENTS = 0x00000001;
    public static final int LITERALS = 0x00000002;
    public static final int BLOBS = 0x00000003;
    public static final int TABLES = 0x00000004;
    public static final int CODE = 0x00000005;
    public static final int DELTA = 0x00000006;
    public static final int DEBUG = 0x80000001;

    private SectionId() {
    }

    public static boolean isAncillary(int id) {
        return (id & ANCILLARY) != 0;
    }
}
