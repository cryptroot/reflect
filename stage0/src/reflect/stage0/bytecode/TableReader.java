package reflect.stage0.bytecode;

/**
 * Reads the TABLES directory (§3.1) and gives the u32 columns of each present table in place.
 * It rejects an unknown or unsupported table, a table without rows or over the row limit, a wrong
 * row size, a directory out of ID order, and rows that do not fill TABLES exactly.
 */
final class TableReader {
    private final ByteSource source;
    private final int[] rows = new int[TableId.TUPLE_NAMES + 1];
    private final int[] starts = new int[TableId.TUPLE_NAMES + 1];

    TableReader(ByteSource source, int[] supported) throws ModuleFormatException {
        this.source = source;
        int start = source.position();
        int end = start + source.remaining();
        var directory = source.slice(start, end);
        int count = directory.u32();
        if (count < 0 || count > directory.remaining() / 12) {
            throw new ModuleFormatException(start, "a TABLES directory of " + hex(count) + " tables runs past the end of TABLES");
        }
        long position = directory.position() + 12L * count;
        int previous = 0;
        for (int i = 0; i < count; i++) {
            int entry = directory.position();
            int id = directory.u32();
            int rowCount = directory.u32();
            int rowSize = directory.u32();
            if (id < 1 || id > TableId.TUPLE_NAMES || TableId.columns(id) == 0) {
                throw new ModuleFormatException(entry, "unknown table " + hex(id) + " (BC-20)");
            }
            if (id == previous) {
                throw new ModuleFormatException(entry, "table " + hex(id) + " appears twice in the TABLES directory");
            }
            if (id < previous) {
                throw new ModuleFormatException(entry, "the TABLES directory is not sorted by table ID (BC-11)");
            }
            if (!contains(supported, id)) {
                throw new ModuleFormatException(entry, "this reader does not support table " + hex(id) + " yet");
            }
            if (rowCount == 0) {
                throw new ModuleFormatException(entry + 4, "table " + hex(id) + " has no rows; a table is present"
                        + " only when it has rows (BC-24)");
            }
            if (rowCount < 0 || rowCount > Limits.MAX_ROWS) {
                throw new ModuleFormatException(entry + 4, "table " + hex(id) + " has " + (rowCount & 0xFFFFFFFFL)
                        + " rows, more than the limit of " + Limits.MAX_ROWS + " (BC-21)");
            }
            if (rowSize != TableId.rowSize(id)) {
                throw new ModuleFormatException(entry + 8, "table " + hex(id) + " has rows of " + (rowSize & 0xFFFFFFFFL)
                        + " bytes, not " + TableId.rowSize(id) + " (BC-11)");
            }
            long next = position + (long) rowCount * rowSize;
            if (next > end) {
                throw new ModuleFormatException(entry + 4, "the rows of table " + hex(id) + " run past the end of TABLES");
            }
            rows[id] = rowCount;
            starts[id] = (int) position;
            position = next;
            previous = id;
        }
        if (position != end) {
            throw new ModuleFormatException((int) position, "TABLES continues after the rows of its last table");
        }
    }

    /** The number of rows of the table, 0 when it is absent. */
    int rows(int table) {
        return rows[table];
    }

    /** The file offset of a column of a row; rows count from 1. */
    int offset(int table, int row, int column) {
        return starts[table] + (row - 1) * TableId.rowSize(table) + 4 * column;
    }

    int value(int table, int row, int column) throws ModuleFormatException {
        int at = offset(table, row, column);
        return source.slice(at, at + 4).u32();
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }
}
