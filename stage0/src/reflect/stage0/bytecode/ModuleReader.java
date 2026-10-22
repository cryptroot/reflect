package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Reads a module (README §7.3) and checks it: the container (ModuleFile), the heaps, the TABLES
 * directory, each row, column by column, the bodies in CODE, and DEBUG unless it is stale (BC-20).
 * It resolves the heap references in the canonical order of BC-24, so a heap out of that order is
 * rejected, and last it writes the model again and compares the bytes. Every error names its file
 * offset.
 */
public final class ModuleReader {
    private static final int[] SUPPORTED = {TableId.MODULE, TableId.MODULE_REF, TableId.EXPORT, TableId.TYPE_DEF,
        TableId.TYPE_REF, TableId.FIELD, TableId.METHOD, TableId.PARAM, TableId.INTERFACE_IMPL, TableId.MEMBER_REF};
    private static final int[] TYPE_TOKENS = {TableId.TYPE_DEF, TableId.TYPE_REF, TableId.TYPE_SPEC};
    private static final int[] SCOPE_TOKENS = {TableId.MODULE_REF, TableId.TYPE_REF};
    private static final int[] PARENT_TOKENS = {TableId.TYPE_REF, TableId.TYPE_SPEC};

    private final ModuleFile file;
    private final HeapReader idents;
    private final HeapReader literals;
    private final HeapReader blobs;
    private final TableReader tables;
    private final List<ModuleRef> moduleRefs = new ArrayList<>();
    private final List<TypeDef> typeDefs = new ArrayList<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();
    private String moduleName = "";

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

    // The tables are read in ID order, which is also the order of their heap references (BC-24).
    private ModuleDef module() throws ModuleFormatException {
        int rows = tables.rows(TableId.MODULE);
        if (rows != 1) {
            throw new ModuleFormatException(file.open(file.find(SectionId.TABLES)).position(), "the Module table has "
                    + rows + " rows; it has exactly one (BC-24)");
        }
        moduleName = ident(TableId.MODULE, 1, 0);
        var module = new ModuleDef(moduleName, value(TableId.MODULE, 1, 1), value(TableId.MODULE, 1, 2),
                value(TableId.MODULE, 1, 3));
        flags(TableId.MODULE, 1, 4, 0, "Module");
        int main = row(TableId.MODULE, 1, 5, TableId.TYPE_DEF, true);
        module.setUnsafe((file.flags() & Header.FLAG_UNSAFE) != 0);
        readModuleRefs();
        for (Export export : readExports()) {
            module.exports().add(export);
        }
        List<TypeDefRow> typeDefRows = readTypeDefs();
        int[] fieldRuns = runs(TableId.TYPE_DEF, 7, TableId.FIELD, "Field");
        int[] methodRuns = runs(TableId.TYPE_DEF, 8, TableId.METHOD, "Method");
        runs(TableId.TYPE_DEF, 9, TableId.GENERIC_PARAM, "GenericParam");
        List<TypeRefRow> typeRefRows = readTypeRefs();
        // Signatures name these types, so they exist before the tables that hold signatures.
        buildTypeRefs(typeRefRows);
        buildTypeDefs(typeDefRows);
        List<FieldDef> fields = readFields();
        int[] paramRuns = runs(TableId.METHOD, 4, TableId.PARAM, "Param");
        runs(TableId.METHOD, 5, TableId.GENERIC_PARAM, "GenericParam");
        List<MethodDef> methods = readMethods(paramRuns);
        List<ParamDef> params = readParams();
        readInterfaceImpls();
        for (MemberRef ref : readMemberRefs()) {
            module.memberRefs().add(ref);
        }
        Map<Integer, Code.Decoded> bodies = readBodies(methods, token -> rowOf(token, fields, methods,
                module.memberRefs()));
        for (int row = 1; row <= typeDefRows.size(); row++) {
            TypeDef type = typeDefs.get(row - 1);
            int base = typeDefRows.get(row - 1).base();
            if (base != 0) {
                type.setBase(typeHandle(base));
            }
            for (int field = fieldRuns[row]; field < fieldRuns[row + 1]; field++) {
                type.fields().add(fields.get(field - 1));
            }
            for (int method = methodRuns[row]; method < methodRuns[row + 1]; method++) {
                type.methods().add(methods.get(method - 1));
            }
        }
        for (int row = 1; row <= methods.size(); row++) {
            for (int param = paramRuns[row]; param < paramRuns[row + 1]; param++) {
                methods.get(row - 1).params().add(params.get(param - 1));
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
        idents.checkAllReferenced();
        literals.checkAllReferenced();
        blobs.checkAllReferenced();
        var kept = new ArrayList<Section>();
        for (int i = 0; i < file.sectionCount(); i++) {
            int id = file.sectionId(i);
            if (id == SectionId.DEBUG && !Debug.stale(file.content(i), file.contentHash())) {
                Debug.read(file.open(i), methods.size(), bodies);
            } else if (SectionId.isAncillary(id)) {
                // An unknown ancillary section, or a stale DEBUG, which the module means the same without (BC-20).
                kept.add(new Section(id, file.content(i)));
            }
        }
        checkCanonical(ModuleWriter.write(module));
        for (Section section : kept) {
            module.ancillary().add(section);
        }
        return module;
    }

    // The canonical form (BC-24): a valid module has one encoding, so writing the model again gives back
    // each section that the reader read. The checks before name the defects that they find, and this one
    // the first byte of any other. The sections kept as bytes join the model after it, so the writer
    // never meets a stale DEBUG whose contentHash is that of the canonical encoding.
    private void checkCanonical(byte[] canonical) throws ModuleFormatException {
        ModuleFile written = ModuleFile.read(canonical);
        for (int i = 0; i < written.sectionCount(); i++) {
            int index = file.find(written.sectionId(i));
            int at = Blob.firstDifference(file.content(index), written.content(i));
            if (at >= 0) {
                throw new ModuleFormatException(file.open(index).position() + at, "the canonical encoding of the module"
                        + " differs at this byte (BC-24)");
            }
        }
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
            moduleRefs.add(new ModuleRef(name, flags(TableId.MODULE_REF, row, 1, ModuleWriter.MODULE_REF_FLAGS,
                    "ModuleRef")));
            previous = name;
        }
    }

    private List<Export> readExports() throws ModuleFormatException {
        var exports = new ArrayList<Export>();
        for (int row = 1; row <= tables.rows(TableId.EXPORT); row++) {
            String namespace = ident(TableId.EXPORT, row, 0);
            int kind = value(TableId.EXPORT, row, 1);
            if (kind != Flags.EXPORTS && kind != Flags.OPENS) {
                throw new ModuleFormatException(tables.offset(TableId.EXPORT, row, 1), "Export kind " + hex(kind)
                        + " is not defined (BC-20)");
            }
            var current = new Export(namespace, kind, ident(TableId.EXPORT, row, 2));
            if (row > 1) {
                int order = ModuleWriter.compareExports(exports.get(row - 2), current);
                if (order >= 0) {
                    throw new ModuleFormatException(tables.offset(TableId.EXPORT, row, 0), order == 0
                            ? "two Export rows have equal keys (BC-24)"
                            : "the Export rows are not sorted by namespace, kind and target (BC-24)");
                }
            }
            exports.add(current);
        }
        return exports;
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
            int flags = flags(TableId.TYPE_DEF, row, 3, ModuleWriter.TYPE_FLAGS, "TypeDef");
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

    private List<FieldDef> readFields() throws ModuleFormatException {
        var fields = new ArrayList<FieldDef>();
        for (int row = 1; row <= tables.rows(TableId.FIELD); row++) {
            int flags = flags(TableId.FIELD, row, 0, ModuleWriter.FIELD_FLAGS, "Field");
            visibility(TableId.FIELD, row, 0, flags);
            if ((flags & Flags.FIELD_CONST) != 0 && (flags & Flags.FIELD_STATIC) == 0) {
                throw new ModuleFormatException(tables.offset(TableId.FIELD, row, 0), "a const field is also STATIC (BC-11)");
            }
            String name = ident(TableId.FIELD, row, 1);
            ByteSource source = blobSource(TableId.FIELD, row, 2);
            int at = source.position();
            TypeSig type = Signatures.field(source, this::handleOf);
            check(at, Signatures.problem(type, Signatures.OTHER, ModuleWriter.DEFINITION, moduleName));
            fields.add(new FieldDef(flags, name, type));
        }
        return fields;
    }

    private List<MethodDef> readMethods(int[] paramRuns) throws ModuleFormatException {
        var methods = new ArrayList<MethodDef>();
        for (int row = 1; row <= tables.rows(TableId.METHOD); row++) {
            int flags = flags(TableId.METHOD, row, 0, ModuleWriter.METHOD_FLAGS, "Method");
            visibility(TableId.METHOD, row, 0, flags);
            String name = ident(TableId.METHOD, row, 1);
            String problem = ModuleWriter.constructorName(flags, name);
            if (problem != null) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, row, 1), problem);
            }
            ByteSource source = blobSource(TableId.METHOD, row, 2);
            int at = source.position();
            MethodSig signature = Signatures.method(source, this::handleOf);
            check(at, Signatures.problem(signature, ModuleWriter.DEFINITION, moduleName));
            if (((signature.flags() & SigCode.METHOD_STATIC) != 0) != ((flags & Flags.METHOD_STATIC) != 0)) {
                throw new ModuleFormatException(at, "the signature has STATIC exactly when the method has (BC-11)");
            }
            if ((signature.flags() & SigCode.METHOD_GENERIC) != 0) {
                throw new ModuleFormatException(at, "the signature is GENERIC, but the method has no GenericParam rows (BC-24)");
            }
            if (!Code.hasBody(flags) && value(TableId.METHOD, row, 3) != 0) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, row, 3), "an ABSTRACT, NATIVE or EXTERN"
                        + " method has no body, so its code is 0 (BC-11)");
            }
            int count = paramRuns[row + 1] - paramRuns[row];
            if (count > Limits.MAX_PARAMS) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, row, 4), "the method has " + count
                        + " parameters, more than the limit of " + Limits.MAX_PARAMS + " (BC-21)");
            }
            if (count != signature.params().size()) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, row, 4), "the signature has "
                        + signature.params().size() + " parameters, but the method has " + count + " Param rows (BC-11)");
            }
            methods.add(new MethodDef(flags, name, signature));
        }
        return methods;
    }

    private List<ParamDef> readParams() throws ModuleFormatException {
        var params = new ArrayList<ParamDef>();
        for (int row = 1; row <= tables.rows(TableId.PARAM); row++) {
            int flags = flags(TableId.PARAM, row, 0, ModuleWriter.PARAM_FLAGS, "Param");
            params.add(new ParamDef(flags, ident(TableId.PARAM, row, 1)));
        }
        return params;
    }

    // Adds the interface of each row to its type, in row order.
    private void readInterfaceImpls() throws ModuleFormatException {
        int previousType = 0;
        int previousToken = 0;
        for (int row = 1; row <= tables.rows(TableId.INTERFACE_IMPL); row++) {
            int type = row(TableId.INTERFACE_IMPL, row, 0, TableId.TYPE_DEF, false);
            int token = token(TableId.INTERFACE_IMPL, row, 1, TYPE_TOKENS, false, "a TypeDef, TypeRef or TypeSpec row");
            if (row > 1) {
                int order = ModuleWriter.compareRows(previousType, type);
                if (order == 0) {
                    order = ModuleWriter.compareRows(previousToken, token);
                }
                if (order >= 0) {
                    throw new ModuleFormatException(tables.offset(TableId.INTERFACE_IMPL, row, 0), order == 0
                            ? "two InterfaceImpl rows have equal keys (BC-24)"
                            : "the InterfaceImpl rows are not sorted by class and interface (BC-24)");
                }
            }
            TypeHandle implemented = typeHandle(token);
            if (implemented instanceof TypeDef def && !ModuleWriter.interfaceKind(def.kind())) {
                throw new ModuleFormatException(tables.offset(TableId.INTERFACE_IMPL, row, 1), "the InterfaceImpl row"
                        + " names " + def.qualifiedName() + ", which is not an interface (BC-11)");
            }
            typeDefs.get(type - 1).interfaces().add(implemented);
            previousType = type;
            previousToken = token;
        }
    }

    private List<MemberRef> readMemberRefs() throws ModuleFormatException {
        var refs = new ArrayList<MemberRef>();
        var rows = new ArrayList<MemberRefRow>();
        for (int row = 1; row <= tables.rows(TableId.MEMBER_REF); row++) {
            int parent = token(TableId.MEMBER_REF, row, 0, PARENT_TOKENS, false, "a TypeRef or TypeSpec row");
            String name = ident(TableId.MEMBER_REF, row, 1);
            ByteSource source = blobSource(TableId.MEMBER_REF, row, 2);
            int at = source.position();
            byte[] bytes = source.slice(at, at + source.remaining()).bytes(source.remaining());
            MemberSig signature = Signatures.member(source, this::handleOf);
            check(at, signature instanceof FieldSig field
                    ? Signatures.problem(field.type(), Signatures.OTHER, ModuleWriter.REFERENCE, moduleName)
                    : Signatures.problem((MethodSig) signature, ModuleWriter.REFERENCE, moduleName));
            var current = new MemberRefRow(parent, name, bytes);
            if (row > 1) {
                MemberRefRow previous = rows.get(row - 2);
                int order = ModuleWriter.compareRows(previous.parent(), parent);
                if (order == 0) {
                    order = Utf8.compare(previous.name(), name);
                }
                if (order == 0) {
                    order = Blob.compare(previous.signature(), bytes);
                }
                if (order >= 0) {
                    throw new ModuleFormatException(tables.offset(TableId.MEMBER_REF, row, 0), order == 0
                            ? "two MemberRef rows have equal keys (BC-24)"
                            : "the MemberRef rows are not sorted by parent, name and signature (BC-24)");
                }
            }
            rows.add(current);
            refs.add(new MemberRef(typeHandle(parent), name, signature));
        }
        return refs;
    }

    // The bodies follow one another from CODE offset 0 in the order of their Method rows (BC-17).
    // They are read after every table, since their heap entries come last (BC-24). Returns them by
    // row, with the code offsets that DEBUG names.
    private Map<Integer, Code.Decoded> readBodies(List<MethodDef> methods, Code.Handles rows)
            throws ModuleFormatException {
        Map<Integer, Code.Decoded> bodies = new HashMap<>();
        int first = 0;
        for (int row = methods.size(); row >= 1; row--) {
            if (Code.hasBody(methods.get(row - 1).flags())) {
                first = row;
            }
        }
        int index = file.find(SectionId.CODE);
        if (index < 0 || first == 0) {
            if (first != 0) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, first, 3), "the method has a body, but"
                        + " the module has no CODE section (BC-24)");
            }
            if (index >= 0) {
                throw new ModuleFormatException(directoryEntry(index), "the module has a CODE section, but no method"
                        + " has a body (BC-24)");
            }
            return bodies;
        }
        ByteSource code = file.open(index);
        int start = code.position();
        for (int row = first; row <= methods.size(); row++) {
            MethodDef method = methods.get(row - 1);
            if (!Code.hasBody(method.flags())) {
                continue;
            }
            int offset = code.position() - start;
            if (value(TableId.METHOD, row, 3) != offset) {
                throw new ModuleFormatException(tables.offset(TableId.METHOD, row, 3), "the body of the method starts"
                        + " at CODE offset " + hex(offset) + " (BC-17)");
            }
            Code.Decoded decoded = Code.body(code, blobs, literals, rows, Code.arguments(method.signature()), moduleName);
            method.setBody(decoded.body());
            bodies.put(row, decoded);
        }
        if (!code.atEnd()) {
            throw new ModuleFormatException(code.position(), "CODE continues after the last body (BC-17)");
        }
        return bodies;
    }

    /**
     * Checks a first-row column (§3.1): the runs start at row 1, in order, and partition the rows of
     * the child table among the owners. Returns the start of each owner's run, and after the last
     * owner the row after the end of the table.
     */
    private int[] runs(int owner, int column, int child, String what) throws ModuleFormatException {
        int owners = tables.rows(owner);
        int children = tables.rows(child);
        if (owners == 0 && children > 0) {
            throw new ModuleFormatException(tables.offset(child, 1, 0), what + " rows belong to no owner (BC-11)");
        }
        int[] starts = new int[owners + 2];
        int previous = 1;
        for (int row = 1; row <= owners; row++) {
            int start = value(owner, row, column);
            if (row == 1 && start != 1) {
                throw new ModuleFormatException(tables.offset(owner, row, column), "the first run of " + what
                        + " rows starts at row " + (start & 0xFFFFFFFFL) + ", not at row 1 (BC-11)");
            }
            if (start < previous || start > children + 1) {
                throw new ModuleFormatException(tables.offset(owner, row, column), "the run of " + what
                        + " rows starts at row " + (start & 0xFFFFFFFFL) + ", outside rows " + previous + " to "
                        + (children + 1) + " (BC-11)");
            }
            starts[row] = start;
            previous = start;
        }
        starts[owners + 1] = children + 1;
        return starts;
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

    private ByteSource blobSource(int table, int row, int column) throws ModuleFormatException {
        return blobs.blob(value(table, row, column), tables.offset(table, row, column));
    }

    // The type of a token in a signature, or null when it names no TypeDef or TypeRef row.
    private @Nullable TypeHandle handleOf(int token) {
        int row = token & 0xFFFFFF;
        int table = token >>> 24;
        if (table == TableId.TYPE_DEF && row >= 1 && row <= typeDefs.size()) {
            return typeDefs.get(row - 1);
        }
        if (table == TableId.TYPE_REF && row >= 1 && row <= typeRefs.size()) {
            return typeRefs.get(row - 1);
        }
        return null;
    }

    // The row of a token in an instruction, or null when it names no TypeDef, TypeRef, Field, Method or
    // MemberRef row.
    private @Nullable Handle rowOf(int token, List<FieldDef> fields, List<MethodDef> methods, List<MemberRef> refs) {
        int row = token & 0xFFFFFF;
        int table = token >>> 24;
        if (table == TableId.FIELD) {
            return row >= 1 && row <= fields.size() ? fields.get(row - 1) : null;
        }
        if (table == TableId.METHOD) {
            return row >= 1 && row <= methods.size() ? methods.get(row - 1) : null;
        }
        if (table == TableId.MEMBER_REF) {
            return row >= 1 && row <= refs.size() ? refs.get(row - 1) : null;
        }
        return handleOf(token);
    }

    private static void check(int at, @Nullable String problem) throws ModuleFormatException {
        if (problem != null) {
            throw new ModuleFormatException(at, problem);
        }
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
        int visibility = flags & ModuleWriter.VISIBILITY;
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

    private record MemberRefRow(int parent, String name, byte[] signature) {
    }
}
