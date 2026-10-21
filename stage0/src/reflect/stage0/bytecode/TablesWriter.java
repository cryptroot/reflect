package reflect.stage0.bytecode;

/** Builds TABLES (§3.1): the directory of the present tables in ID order, then their rows. */
final class TablesWriter {
    private final ByteSink directory = new ByteSink();
    private final ByteSink rows = new ByteSink();
    private int count;
    private int lastId;
    private int tableEnd;

    /** Starts the table if it has rows, and returns whether it does; tables come in ID order. */
    boolean begin(int id, int rowCount) throws ModuleFormatException {
        if (rows.size() != tableEnd || id <= lastId) {
            throw new IllegalStateException("Table " + id + " starts out of order or before the last one ends");
        }
        lastId = id;
        if (rowCount == 0) {
            return false;
        }
        if (rowCount > Limits.MAX_ROWS) {
            throw new ModuleFormatException("table " + ModuleFormatException.hex(id) + " has " + rowCount
                    + " rows, more than the limit of " + Limits.MAX_ROWS + " (BC-21)");
        }
        directory.u32(id);
        directory.u32(rowCount);
        directory.u32(TableId.rowSize(id));
        count++;
        tableEnd = rows.size() + rowCount * TableId.rowSize(id);
        return true;
    }

    void u32(int value) {
        rows.u32(value);
    }

    byte[] toByteArray() {
        if (rows.size() != tableEnd) {
            throw new IllegalStateException("The last table is not complete");
        }
        var out = new ByteSink();
        out.u32(count);
        out.bytes(directory.toByteArray());
        out.bytes(rows.toByteArray());
        return out.toByteArray();
    }
}
