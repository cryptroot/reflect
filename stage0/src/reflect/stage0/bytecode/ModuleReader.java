package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * Reads a module (README §7.3) and checks it: the container (ModuleFile), the heaps, the TABLES
 * directory, and each row, column by column. It resolves the heap references in the canonical
 * order of BC-24, so a heap out of that order is rejected. Every error names its file offset.
 */
public final class ModuleReader {
    private static final int[] SUPPORTED = {TableId.MODULE, TableId.MODULE_REF, TableId.TYPE_DEF, TableId.TYPE_REF};
    private static final int MODULE_REF_FLAGS = Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC;
    private static final int VISIBILITY = Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE;
    private static final int TYPE_FLAGS = VISIBILITY | Flags.TYPE_ABSTRACT | Flags.TYPE_FINAL | Flags.TYPE_SEALED
            | Flags.TYPE_UNSEALED;
    private static final int[] TYPE_TOKENS = {TableId.TYPE_DEF, TableId.TYPE_REF, TableId.TYPE_SPEC};
    private static final int[] SCOPE_TOKENS = {TableId.MODULE_REF, TableId.TYPE_REF};

    private final ModuleFile file;
    private final HeapReader idents;
    private final HeapReader literals;
    private final HeapReader blobs;
    private final TableReader tables;
    private final List<ModuleRef> moduleRefs = new ArrayList<>();
    private final List<TypeDef> typeDefs = new ArrayList<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();

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
        int main = row(TableId.MODULE, 1, 5, TableId.TYPE_DEF, true);
        module.setUnsafe((file.flags() & Header.FLAG_UNSAFE) != 0);
        readModuleRefs();
        List<TypeDefRow> typeDefRows = readTypeDefs();
        List<TypeRefRow> typeRefRows = readTypeRefs();
        buildTypeRefs(typeRefRows);
        buildTypeDefs(typeDefRows);
        for (int row = 1; row <= typeDefRows.size(); row++) {
            int base = typeDefRows.get(row - 1).base();
            if (base != 0) {
                typeDefs.get(row - 1).setBase(typeHandle(base));
            }
        }
        module.setMain(main == 0 ? null : typeDefs.get(main - 1));
        for (ModuleRef ref : moduleRefs) {
            module.requires().add(ref);
        }
        for (TypeDef type : typeDefs) {
            module.types().add(type);
        }
        for (TypeRef ref : typeRefs) {
            module.typeRefs().add(ref);
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

    private void readModuleRefs() throws ModuleFormatException {
        String previous = "";
        for (int row = 1; row <= tables.rows(TableId.MODULE_REF); row++) {
            String name = ident(TableId.MODULE_REF, row, 0);
            if (row > 1 && Utf8.compare(previous, name) >= 0) {
                throw new ModuleFormatException(tables.offset(TableId.MODULE_REF, row, 0), Utf8.compare(previous, name) == 0
                        ? "two ModuleRef rows have the name " + name + " (BC-24)"
                        : "the ModuleRef rows are not sorted by name (BC-24)");
            }
            moduleRefs.add(new ModuleRef(name, flags(TableId.MODULE_REF, row, 1, MODULE_REF_FLAGS, "ModuleRef")));
            previous = name;
        }
    }

    private List<TypeDefRow> readTypeDefs() throws ModuleFormatException {
        var rows = new ArrayList<TypeDefRow>();
        for (int row = 1; row <= tables.rows(TableId.TYPE_DEF); row++) {
            String name = ident(TableId.TYPE_DEF, row, 0);
            String namespace = ident(TableId.TYPE_DEF, row, 1);
            int kind = value(TableId.TYPE_DEF, row, 2);
            if (kind < Flags.KIND_CLASS || kind > Flags.KIND_ANNOTATION) {
                throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, row, 2), "TypeDef kind "
                        + hex(kind) + " is not defined (BC-20)");
            }
            int flags = flags(TableId.TYPE_DEF, row, 3, TYPE_FLAGS, "TypeDef");
            visibility(TableId.TYPE_DEF, row, 3, flags);
            int base = token(TableId.TYPE_DEF, row, 4, TYPE_TOKENS, true, "a TypeDef, TypeRef or TypeSpec row");
            int backing = value(TableId.TYPE_DEF, row, 5);
            if (kind == Flags.KIND_ENUM ? !ModuleWriter.integral(backing) : backing != 0) {
                throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, row, 5), kind == Flags.KIND_ENUM
                        ? "the backing type of an enum is one of I8, U8, I16, U16, I32, U32, I64 and U64, not "
                                + hex(backing) + " (DAT-05)"
                        : "TypeDef backing is " + hex(backing) + ", but only an enum has a backing type (BC-11)");
            }
            int enclosing = row(TableId.TYPE_DEF, row, 6, TableId.TYPE_DEF, true);
            if (enclosing != 0 && !namespace.isEmpty()) {
                throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, row, 1), "a nested type has namespace 0 (BC-11)");
            }
            for (int column = 7; column <= 9; column++) {
                int first = value(TableId.TYPE_DEF, row, column);
                if (first != 1) {
                    throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, row, column), "the run of rows starts"
                            + " at row 1, not at row " + (first & 0xFFFFFFFFL) + ", since the table has no rows (BC-11)");
                }
            }
            var current = new TypeDefRow(name, namespace, kind, flags, base, backing, enclosing);
            if (row > 1) {
                int order = compare(rows.get(row - 2), current);
                if (order >= 0) {
                    throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, row, 0), order == 0
                            ? "two TypeDef rows have equal keys (BC-24)"
                            : "the TypeDef rows are not sorted by namespace, name and enclosing type (BC-24)");
                }
            }
            rows.add(current);
        }
        return rows;
    }

    private List<TypeRefRow> readTypeRefs() throws ModuleFormatException {
        var rows = new ArrayList<TypeRefRow>();
        for (int row = 1; row <= tables.rows(TableId.TYPE_REF); row++) {
            int scope = token(TableId.TYPE_REF, row, 0, SCOPE_TOKENS, false, "a ModuleRef or TypeRef row");
            String namespace = ident(TableId.TYPE_REF, row, 1);
            String name = ident(TableId.TYPE_REF, row, 2);
            if (scope >>> 24 == TableId.TYPE_REF && !namespace.isEmpty()) {
                throw new ModuleFormatException(tables.offset(TableId.TYPE_REF, row, 1), "a nested type has namespace 0 (BC-11)");
            }
            var current = new TypeRefRow(scope, namespace, name);
            if (row > 1) {
                TypeRefRow previous = rows.get(row - 2);
                int order = ModuleWriter.compareRows(previous.scope(), scope);
                if (order == 0) {
                    order = Utf8.compare(previous.namespace(), namespace);
                }
                if (order == 0) {
                    order = Utf8.compare(previous.name(), name);
                }
                if (order >= 0) {
                    throw new ModuleFormatException(tables.offset(TableId.TYPE_REF, row, 0), order == 0
                            ? "two TypeRef rows have equal keys (BC-24)"
                            : "the TypeRef rows are not sorted by scope, namespace and name (BC-24)");
                }
            }
            rows.add(current);
        }
        return rows;
    }

    // Builds each chain of nested types from its outermost type, and rejects a cycle.
    private void buildTypeDefs(List<TypeDefRow> rows) throws ModuleFormatException {
        @Nullable TypeDef[] built = new TypeDef[rows.size() + 1];
        int[] mark = new int[rows.size() + 1];
        for (int row = 1; row <= rows.size(); row++) {
            var chain = new ArrayList<Integer>();
            for (int current = row; current != 0 && built[current] == null; current = rows.get(current - 1).enclosing()) {
                if (mark[current] == row) {
                    throw new ModuleFormatException(tables.offset(TableId.TYPE_DEF, current, 6),
                            "the enclosing types of TypeDef row " + current + " form a cycle");
                }
                mark[current] = row;
                chain.add(current);
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                int current = chain.get(i);
                TypeDefRow raw = rows.get(current - 1);
                built[current] = new TypeDef(raw.namespace(), raw.name(), raw.kind(), raw.flags(), raw.backing(),
                        raw.enclosing() == 0 ? null : built[raw.enclosing()]);
            }
        }
        for (int row = 1; row <= rows.size(); row++) {
            typeDefs.add(built[row]);
        }
    }

    private void buildTypeRefs(List<TypeRefRow> rows) throws ModuleFormatException {
        @Nullable TypeRef[] built = new TypeRef[rows.size() + 1];
        int[] mark = new int[rows.size() + 1];
        for (int row = 1; row <= rows.size(); row++) {
            var chain = new ArrayList<Integer>();
            int current = row;
            while (current != 0 && built[current] == null) {
                if (mark[current] == row) {
                    throw new ModuleFormatException(tables.offset(TableId.TYPE_REF, current, 0),
                            "the enclosing types of TypeRef row " + current + " form a cycle");
                }
                mark[current] = row;
                chain.add(current);
                int scope = rows.get(current - 1).scope();
                current = scope >>> 24 == TableId.TYPE_REF ? scope & 0xFFFFFF : 0;
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                int index = chain.get(i);
                TypeRefRow raw = rows.get(index - 1);
                int scope = raw.scope();
                TypeScope resolved = scope >>> 24 == TableId.TYPE_REF ? built[scope & 0xFFFFFF]
                        : moduleRefs.get((scope & 0xFFFFFF) - 1);
                built[index] = new TypeRef(resolved, raw.namespace(), raw.name());
            }
        }
        for (int row = 1; row <= rows.size(); row++) {
            typeRefs.add(built[row]);
        }
    }

    private TypeHandle typeHandle(int token) {
        int row = token & 0xFFFFFF;
        return token >>> 24 == TableId.TYPE_DEF ? typeDefs.get(row - 1) : typeRefs.get(row - 1);
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
                    + hex(flags & ~defined) + " are not defined (BC-20)");
        }
        return flags;
    }

    private void visibility(int table, int row, int column, int flags) throws ModuleFormatException {
        int visibility = flags & VISIBILITY;
        if ((visibility & (visibility - 1)) != 0) {
            throw new ModuleFormatException(tables.offset(table, row, column), "the flags hold more than one"
                    + " visibility flag (BC-11)");
        }
    }

    // A column that holds a row of the target table, or 0 when optional.
    private int row(int table, int row, int column, int target, boolean optional) throws ModuleFormatException {
        int value = value(table, row, column);
        if ((value == 0 && !optional) || value < 0 || value > tables.rows(target)) {
            throw new ModuleFormatException(tables.offset(table, row, column), "row " + (value & 0xFFFFFFFFL)
                    + " of table " + hex(target) + " does not exist");
        }
        return value;
    }

    // A column that holds a token of one of the tables, or 0 when optional.
    private int token(int table, int row, int column, int[] targets, boolean optional, String expected)
            throws ModuleFormatException {
        int value = value(table, row, column);
        if (value == 0 && optional) {
            return 0;
        }
        int target = value >>> 24;
        boolean allowed = false;
        for (int candidate : targets) {
            allowed |= candidate == target;
        }
        if (!allowed) {
            throw new ModuleFormatException(tables.offset(table, row, column), "token " + hex(value)
                    + " does not name " + expected);
        }
        int targetRow = value & 0xFFFFFF;
        if (targetRow == 0 || targetRow > tables.rows(target)) {
            throw new ModuleFormatException(tables.offset(table, row, column), "token " + hex(value) + " names row "
                    + targetRow + " of table " + hex(target) + ", which does not exist");
        }
        return value;
    }

    private static int compare(TypeDefRow left, TypeDefRow right) {
        int order = Utf8.compare(left.namespace(), right.namespace());
        if (order == 0) {
            order = Utf8.compare(left.name(), right.name());
        }
        return order != 0 ? order : ModuleWriter.compareRows(left.enclosing(), right.enclosing());
    }

    private static int require(ModuleFile file, int id) throws ModuleFormatException {
        int index = file.find(id);
        if (index < 0) {
            throw new ModuleFormatException(Header.SIZE, "the module has no section " + hex(id)
                    + "; IDENTS, LITERALS, BLOBS and TABLES are always present (BC-24)");
        }
        return index;
    }

    private static int directoryEntry(int index) {
        return Header.SIZE + Header.DIRECTORY_ENTRY_SIZE * index;
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }

    private record TypeDefRow(String name, String namespace, int kind, int flags, int base, int backing, int enclosing) {
    }

    private record TypeRefRow(int scope, String namespace, String name) {
    }
}
