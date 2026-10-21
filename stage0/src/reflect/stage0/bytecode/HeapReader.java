package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;

/**
 * Reads IDENTS, LITERALS or BLOBS (BC-02). The heap is a run of entries from its empty entry at
 * offset 0 to its end, each unique (BC-24). A reference must name the start of an entry, and the
 * reader makes the references in the canonical order, so the first references must meet the
 * entries in heap order; checkAllReferenced then finds an entry that nothing references (BC-24).
 */
public final class HeapReader {
    private final int section;
    private final ByteSource source;
    private final int start;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, Integer> indexes = new HashMap<>();
    private int nextIndex = 1;

    /** Reads the heap in the source, whose positions are file offsets. */
    public HeapReader(int section, ByteSource source) throws ModuleFormatException {
        this.section = section;
        this.source = source;
        this.start = source.position();
        var reader = source.slice(source.position(), source.position() + source.remaining());
        var seen = new HashMap<Blob, Integer>();
        if (reader.atEnd() || reader.u8() != 0) {
            throw new ModuleFormatException(start, name() + " does not start with its empty entry, one zero byte (BC-02)");
        }
        add(seen, new Entry(start, start + 1, start + 1, ""));
        while (!reader.atEnd()) {
            int at = reader.position();
            int units = reader.uleb();
            String text = "";
            int payload = reader.position();
            if (section == SectionId.IDENTS) {
                if (units < 0 || units > Limits.MAX_IDENTIFIER_BYTES) {
                    throw new ModuleFormatException(at, "an identifier of " + (units & 0xFFFFFFFFL)
                            + " UTF-8 bytes exceeds the limit of " + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
                }
                text = Utf8.decode(reader, units);
            } else if (section == SectionId.LITERALS) {
                text = Utf16.decode(reader, units);
            } else {
                if (units < 0 || units > reader.remaining()) {
                    throw new ModuleFormatException(at, "a blob of " + (units & 0xFFFFFFFFL)
                            + " bytes runs past the end of BLOBS");
                }
                reader.bytes(units);
            }
            add(seen, new Entry(at, payload, reader.position(), text));
        }
    }

    /** The identifier at the IDENTS offset that the column at the file offset at holds. */
    public String ident(int offset, int at) throws ModuleFormatException {
        expect(SectionId.IDENTS);
        return reference(offset, at).text();
    }

    public String literal(int offset, int at) throws ModuleFormatException {
        expect(SectionId.LITERALS);
        return reference(offset, at).text();
    }

    /** A source over the bytes of the blob, whose positions are file offsets. */
    public ByteSource blob(int offset, int at) throws ModuleFormatException {
        expect(SectionId.BLOBS);
        Entry entry = reference(offset, at);
        return source.slice(entry.payload(), entry.end());
    }

    /** Rejects the first entry that no reference met (BC-24). */
    public void checkAllReferenced() throws ModuleFormatException {
        if (nextIndex < entries.size()) {
            throw new ModuleFormatException(entries.get(nextIndex).at(), name() + " entry is not referenced (BC-24)");
        }
    }

    private Entry reference(int offset, int at) throws ModuleFormatException {
        int index = indexes.getOrDefault(offset, -1);
        if (index < 0) {
            throw new ModuleFormatException(at, name() + " offset " + ModuleFormatException.hex(offset & 0xFFFFFFFFL)
                    + " is not the start of an entry");
        }
        if (index > nextIndex) {
            throw new ModuleFormatException(at, name() + " entry " + ModuleFormatException.hex(offset)
                    + " is referenced before the entry " + ModuleFormatException.hex(entries.get(nextIndex).at() - start)
                    + "; entries follow the order of their first reference (BC-24)");
        }
        if (index == nextIndex) {
            nextIndex++;
        }
        return entries.get(index);
    }

    private void add(Map<Blob, Integer> seen, Entry entry) throws ModuleFormatException {
        var key = new Blob(source.slice(entry.payload(), entry.end()).bytes(entry.end() - entry.payload()));
        int first = seen.getOrDefault(key, -1);
        if (first >= 0) {
            throw new ModuleFormatException(entry.at(), name() + " entry repeats the entry "
                    + ModuleFormatException.hex(first) + " (BC-24)");
        }
        seen.put(key, entry.at() - start);
        indexes.put(entry.at() - start, entries.size());
        entries.add(entry);
    }

    private String name() {
        return section == SectionId.IDENTS ? "IDENTS" : section == SectionId.LITERALS ? "LITERALS" : "BLOBS";
    }

    private void expect(int kind) {
        if (section != kind) {
            throw new IllegalStateException("This heap is section " + section + ", not " + kind);
        }
    }

    // at is the file offset of the entry's length; the payload runs from payload to end.
    private record Entry(int at, int payload, int end, String text) {
    }
}
