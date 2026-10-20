package reflect.stage0.bytecode;

/** The header of a module file and its directory, as BC-02 gives them. */
public final class Header {
    // "RFLC" read as a little-endian u32.
    public static final int MAGIC = 0x434C4652;
    public static final int FORMAT_MAJOR = 0;
    public static final int FORMAT_MINOR = 1;

    public static final int FLAG_UNSAFE = 0x01;
    public static final int FLAG_DELTA = 0x02;

    public static final int MAGIC_OFFSET = 0;
    public static final int FORMAT_MAJOR_OFFSET = 4;
    public static final int FORMAT_MINOR_OFFSET = 6;
    public static final int FLAGS_OFFSET = 8;
    public static final int RESERVED_OFFSET = 12;
    public static final int SECTION_COUNT_OFFSET = 28;
    public static final int CONTENT_HASH_OFFSET = 32;
    public static final int SIZE = 64;

    public static final int DIRECTORY_ENTRY_SIZE = 12;
    public static final int ALIGNMENT = 8;

    private Header() {
    }
}
