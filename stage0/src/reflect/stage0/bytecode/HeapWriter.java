package reflect.stage0.bytecode;

import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.Map;

/**
 * Builds IDENTS, LITERALS or BLOBS (BC-02): the empty entry at offset 0, then each other entry
 * once, in the order of its first use, so a canonical traversal gives canonical bytes (BC-24).
 */
public final class HeapWriter {
    private final int section;
    private final ByteSink bytes = new ByteSink();
    private final Map<Blob, Integer> offsets = new HashMap<>();

    public HeapWriter(int section) {
        if (section != SectionId.IDENTS && section != SectionId.LITERALS && section != SectionId.BLOBS) {
            throw new IllegalArgumentException("Section " + section + " is not a heap");
        }
        this.section = section;
        add(new byte[0]);
    }

    /** The IDENTS offset of the identifier, which BC-21 limits to 65,535 UTF-8 bytes. */
    public int ident(String text) throws ModuleFormatException {
        expect(SectionId.IDENTS);
        byte[] utf8 = Utf8.encode(text);
        if (utf8.length > Limits.MAX_IDENTIFIER_BYTES) {
            throw new ModuleFormatException("an identifier of " + utf8.length + " UTF-8 bytes exceeds the limit of "
                    + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
        }
        return add(utf8);
    }

    /** The LITERALS offset of the string, kept as UTF-16 code units. */
    public int literal(String text) {
        expect(SectionId.LITERALS);
        return add(Utf16.encode(text));
    }

    /** The BLOBS offset of the bytes. */
    public int blob(byte[] content) {
        expect(SectionId.BLOBS);
        return add(content);
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }

    private int add(byte[] payload) {
        var key = new Blob(payload);
        int known = offsets.getOrDefault(key, -1);
        if (known >= 0) {
            return known;
        }
        int offset = bytes.size();
        bytes.uleb(section == SectionId.LITERALS ? payload.length / 2 : payload.length);
        bytes.bytes(payload);
        offsets.put(key, offset);
        return offset;
    }

    private void expect(int kind) {
        if (section != kind) {
            throw new IllegalStateException("This heap is section " + section + ", not " + kind);
        }
    }
}
