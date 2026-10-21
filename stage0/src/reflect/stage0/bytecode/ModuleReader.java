package reflect.stage0.bytecode;

/**
 * Reads a module (README §7.3) and checks it: the container (ModuleFile), the heaps, the TABLES
 * directory, and each row, column by column. It resolves the heap references in the canonical
 * order of BC-24, so a heap out of that order is rejected. Every error names its file offset.
 */
public final class ModuleReader {
    private static final int[] SUPPORTED = {TableId.MODULE, TableId.MODULE_REF};
    private static final int MODULE_REF_FLAGS = Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC;

    private final ModuleFile file;
    private final HeapReader idents;
    private final HeapReader literals;
    private final HeapReader blobs;
    private final TableReader tables;

    private ModuleReader(ModuleFile file) throws ModuleFormatException {
        this.file = file;
        idents = new HeapReader(SectionId.IDENTS, file.open(require(file, SectionId.IDENTS)));
        literals = new HeapReader(SectionId.LITERALS, file.open(require(file, SectionId.LITERALS)));
        blobs = new HeapReader(SectionId.BLOBS, file.open(require(file, SectionId.BLOBS)));
        tables = new TableReader(file.open(require(file, SectionId.TABLES)), SUPPORTED);
    }

    public static ModuleDef read(byte[] data) throws ModuleFormatException {
        return new ModuleReader(ModuleFile.read(data)).module();
    }

    private ModuleDef module() throws ModuleFormatException {
        int rows = tables.rows(TableId.MODULE);
        if (rows != 1) {
            throw new ModuleFormatException(file.open(file.find(SectionId.TABLES)).position(), "the Module table has "
                    + rows + " rows; it has exactly one (BC-24)");
        }
        var module = new ModuleDef(ident(TableId.MODULE, 1, 0), value(TableId.MODULE, 1, 1),
                value(TableId.MODULE, 1, 2), value(TableId.MODULE, 1, 3));
        flags(TableId.MODULE, 1, 4, 0, "Module");
        row(TableId.MODULE, 1, 5, TableId.TYPE_DEF, true);
        module.setUnsafe((file.flags() & Header.FLAG_UNSAFE) != 0);
        String previous = "";
        for (int row = 1; row <= tables.rows(TableId.MODULE_REF); row++) {
            String name = ident(TableId.MODULE_REF, row, 0);
            if (row > 1 && Utf8.compare(previous, name) >= 0) {
                throw new ModuleFormatException(tables.offset(TableId.MODULE_REF, row, 0), Utf8.compare(previous, name) == 0
                        ? "two ModuleRef rows have the name " + name + " (BC-24)"
                        : "the ModuleRef rows are not sorted by name (BC-24)");
            }
            module.requires().add(new ModuleRef(name, flags(TableId.MODULE_REF, row, 1, MODULE_REF_FLAGS, "ModuleRef")));
            previous = name;
        }
        int code = file.find(SectionId.CODE);
        if (code >= 0) {
            throw new ModuleFormatException(directoryEntry(code), "the module has a CODE section, but no method"
                    + " has a body (BC-24)");
        }
        idents.checkAllReferenced();
        literals.checkAllReferenced();
        blobs.checkAllReferenced();
        for (int i = 0; i < file.sectionCount(); i++) {
            if (SectionId.isAncillary(file.sectionId(i))) {
                module.ancillary().add(new Section(file.sectionId(i), file.content(i)));
            }
        }
        return module;
    }

    private int value(int table, int row, int column) throws ModuleFormatException {
        return tables.value(table, row, column);
    }

    private String ident(int table, int row, int column) throws ModuleFormatException {
        return idents.ident(value(table, row, column), tables.offset(table, row, column));
    }

    private int flags(int table, int row, int column, int defined, String owner) throws ModuleFormatException {
        int flags = value(table, row, column);
        if ((flags & ~defined) != 0) {
            throw new ModuleFormatException(tables.offset(table, row, column), owner + " flags "
                    + ModuleFormatException.hex((flags & ~defined) & 0xFFFFFFFFL) + " are not defined (BC-20)");
        }
        return flags;
    }

    // A column that holds a row of the target table, or 0 when optional.
    private int row(int table, int row, int column, int target, boolean optional) throws ModuleFormatException {
        int value = value(table, row, column);
        if ((value == 0 && !optional) || value < 0 || value > tables.rows(target)) {
            throw new ModuleFormatException(tables.offset(table, row, column), "row " + (value & 0xFFFFFFFFL)
                    + " of table " + ModuleFormatException.hex(target) + " does not exist");
        }
        return value;
    }

    private static int require(ModuleFile file, int id) throws ModuleFormatException {
        int index = file.find(id);
        if (index < 0) {
            throw new ModuleFormatException(Header.SIZE, "the module has no section " + ModuleFormatException.hex(id)
                    + "; IDENTS, LITERALS, BLOBS and TABLES are always present (BC-24)");
        }
        return index;
    }

    private static int directoryEntry(int index) {
        return Header.SIZE + Header.DIRECTORY_ENTRY_SIZE * index;
    }
}
