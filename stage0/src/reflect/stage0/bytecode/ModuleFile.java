package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;

/**
 * The header, the section directory and the sections of a module file (BC-02, BC-20, BC-22), with
 * the sections as bytes. Reading checks the magic, the format version, the flags, the reserved
 * bytes, the order, kind and placement of every section, the zero padding and the content hash.
 */
public final class ModuleFile {
    private static final int KNOWN_FLAGS = Header.FLAG_UNSAFE | Header.FLAG_DELTA;

    private final byte[] data;
    private final int flags;
    private final List<Entry> entries;

    private ModuleFile(byte[] data, int flags, List<Entry> entries) {
        this.data = data;
        this.flags = flags;
        this.entries = entries;
    }

    public int flags() {
        return flags;
    }

    public int sectionCount() {
        return entries.size();
    }

    public int sectionId(int index) {
        return entries.get(index).id();
    }

    /** The index of the section with the ID, or -1 when the file has none. */
    public int find(int id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    /** A source over the section's bytes, whose positions are file offsets. */
    public ByteSource open(int index) {
        Entry entry = entries.get(index);
        return new ByteSource(data, entry.offset(), entry.offset() + entry.length());
    }

    public byte[] content(int index) {
        Entry entry = entries.get(index);
        byte[] content = new byte[entry.length()];
        for (int i = 0; i < content.length; i++) {
            content[i] = data[entry.offset() + i];
        }
        return content;
    }

    public byte[] contentHash() {
        return headerHash(data);
    }

    /** The contentHash that write gives a file with these sections; ancillary sections do not change it (BC-22). */
    public static byte[] contentHash(int flags, List<Section> sections) throws ModuleFormatException {
        return headerHash(write(flags, sections));
    }

    private static byte[] headerHash(byte[] file) {
        byte[] hash = new byte[Sha256.SIZE];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = file[Header.CONTENT_HASH_OFFSET + i];
        }
        return hash;
    }

    /** Sorts the sections by ID, lays them out and fills in the content hash. */
    public static byte[] write(int flags, List<Section> sections) throws ModuleFormatException {
        checkFlags(-1, flags);
        var sorted = new ArrayList<Section>();
        for (Section section : sections) {
            sorted.add(section);
        }
        sorted.sort((left, right) -> compareIds(left.id(), right.id()));
        var entries = new ArrayList<Entry>();
        int position = align(Header.SIZE + Header.DIRECTORY_ENTRY_SIZE * sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Section section = sorted.get(i);
            checkKind(-1, section.id());
            if (i > 0 && sorted.get(i - 1).id() == section.id()) {
                throw new ModuleFormatException("two sections have the ID " + hex(section.id()));
            }
            if (section.content().length == 0) {
                throw new ModuleFormatException("section " + hex(section.id()) + " has no content (BC-24)");
            }
            position = align(position);
            entries.add(new Entry(section.id(), position, section.content().length));
            position += section.content().length;
        }
        var out = new ByteSink();
        out.u32(Header.MAGIC);
        out.u16(Header.FORMAT_MAJOR);
        out.u16(Header.FORMAT_MINOR);
        out.u32(flags);
        out.zeros(Header.SECTION_COUNT_OFFSET - Header.RESERVED_OFFSET);
        out.u32(entries.size());
        out.zeros(Sha256.SIZE);
        for (Entry entry : entries) {
            out.u32(entry.id());
            out.u32(entry.offset());
            out.u32(entry.length());
        }
        for (int i = 0; i < entries.size(); i++) {
            out.zeros(entries.get(i).offset() - out.size());
            out.bytes(sorted.get(i).content());
        }
        byte[] file = out.toByteArray();
        byte[] hash = hash(file, entries);
        for (int i = 0; i < hash.length; i++) {
            file[Header.CONTENT_HASH_OFFSET + i] = hash[i];
        }
        return file;
    }

    public static ModuleFile read(byte[] data) throws ModuleFormatException {
        if (data.length < Header.SIZE) {
            throw new ModuleFormatException(0, "the file has " + data.length + " bytes, fewer than the "
                    + Header.SIZE + " of the header");
        }
        var header = new ByteSource(data, 0, Header.SIZE);
        if (header.u32() != Header.MAGIC) {
            throw new ModuleFormatException(Header.MAGIC_OFFSET, "the file does not start with RFLC");
        }
        int major = header.u16();
        int minor = header.u16();
        if (major != Header.FORMAT_MAJOR || minor != Header.FORMAT_MINOR) {
            throw new ModuleFormatException(Header.FORMAT_MAJOR_OFFSET, "format " + major + "." + minor
                    + " is not accepted; this reader accepts format " + Header.FORMAT_MAJOR + "."
                    + Header.FORMAT_MINOR + " only (BC-20)");
        }
        int flags = header.u32();
        checkFlags(Header.FLAGS_OFFSET, flags);
        for (int i = Header.RESERVED_OFFSET; i < Header.SECTION_COUNT_OFFSET; i++) {
            if (data[i] != 0) {
                throw new ModuleFormatException(i, "a reserved header byte is not zero (BC-24)");
            }
        }
        header.bytes(Header.SECTION_COUNT_OFFSET - Header.RESERVED_OFFSET);
        int count = header.u32();
        if (count < 0 || count > (data.length - Header.SIZE) / Header.DIRECTORY_ENTRY_SIZE) {
            throw new ModuleFormatException(Header.SECTION_COUNT_OFFSET, "a directory of " + hex(count)
                    + " sections runs past the end of the file");
        }
        int directoryEnd = Header.SIZE + Header.DIRECTORY_ENTRY_SIZE * count;
        var directory = new ByteSource(data, Header.SIZE, directoryEnd);
        var entries = new ArrayList<Entry>();
        int end = directoryEnd;
        for (int i = 0; i < count; i++) {
            int at = directory.position();
            int id = directory.u32();
            int offset = directory.u32();
            int length = directory.u32();
            if (i > 0) {
                int previous = entries.get(i - 1).id();
                if (previous == id) {
                    throw new ModuleFormatException(at, "two sections have the ID " + hex(id));
                }
                if (compareIds(previous, id) > 0) {
                    throw new ModuleFormatException(at, "the directory is not sorted by section ID (BC-02)");
                }
            }
            checkKind(at, id);
            if (offset != align(end)) {
                throw new ModuleFormatException(at + 4, "section " + hex(id) + " starts at " + hex(offset)
                        + ", not at " + hex(align(end)) + " (BC-02)");
            }
            if (length == 0) {
                throw new ModuleFormatException(at + 8, "section " + hex(id) + " has no content (BC-24)");
            }
            if (length < 0 || length > data.length - offset) {
                throw new ModuleFormatException(at + 8, "section " + hex(id) + " runs past the end of the file");
            }
            for (int k = end; k < offset; k++) {
                if (data[k] != 0) {
                    throw new ModuleFormatException(k, "a padding byte is not zero (BC-24)");
                }
            }
            entries.add(new Entry(id, offset, length));
            end = offset + length;
        }
        if (data.length != end) {
            throw new ModuleFormatException(end, "the file continues after its last section (BC-02)");
        }
        byte[] hash = hash(data, entries);
        for (int i = 0; i < hash.length; i++) {
            if (data[Header.CONTENT_HASH_OFFSET + i] != hash[i]) {
                throw new ModuleFormatException(Header.CONTENT_HASH_OFFSET, "contentHash does not match the content (BC-22)");
            }
        }
        return new ModuleFile(data, flags, entries);
    }

    // BC-22: header bytes 4 to 27, then each critical section as its ID, its length and its bytes.
    private static byte[] hash(byte[] file, List<Entry> entries) {
        var sha = new Sha256();
        sha.update(file, Header.FORMAT_MAJOR_OFFSET, Header.SECTION_COUNT_OFFSET - Header.FORMAT_MAJOR_OFFSET);
        for (Entry entry : entries) {
            if (!SectionId.isAncillary(entry.id())) {
                var prefix = new ByteSink();
                prefix.u32(entry.id());
                prefix.u32(entry.length());
                sha.update(prefix.toByteArray());
                sha.update(file, entry.offset(), entry.length());
            }
        }
        return sha.digest();
    }

    private static void checkFlags(int offset, int flags) throws ModuleFormatException {
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new ModuleFormatException(offset, "unknown header flag " + hex(flags & ~KNOWN_FLAGS)
                    + " (BC-20)");
        }
        if ((flags & Header.FLAG_DELTA) != 0) {
            throw new ModuleFormatException(offset, "delta modules come with v0.5; until then a reader rejects the DELTA flag");
        }
    }

    private static void checkKind(int offset, int id) throws ModuleFormatException {
        if (id == SectionId.DELTA) {
            throw new ModuleFormatException(offset, "delta modules come with v0.5; until then a reader rejects the DELTA section");
        }
        if (!SectionId.isAncillary(id) && (id < SectionId.IDENTS || id > SectionId.CODE)) {
            throw new ModuleFormatException(offset, "unknown critical section " + hex(id) + " (BC-20)");
        }
    }

    // Section IDs compare as unsigned numbers, so ancillary sections sort last (BC-02).
    private static int compareIds(int left, int right) {
        int a = left ^ 0x80000000;
        int b = right ^ 0x80000000;
        return a < b ? -1 : a == b ? 0 : 1;
    }

    private static int align(int position) {
        return (position + Header.ALIGNMENT - 1) / Header.ALIGNMENT * Header.ALIGNMENT;
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }

    private record Entry(int id, int offset, int length) {
    }
}
