package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Writes a module (README §7.3): it sorts the tables by their keys and numbers the rows, makes the
 * heap references in the canonical order of BC-24 while it writes the rows, and lays out the
 * sections. Content that breaks a rule, or exceeds a limit of BC-21, is an error, never a
 * truncated module.
 */
public final class ModuleWriter {
    private static final int MODULE_REF_FLAGS = Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC;
    private static final int VISIBILITY = Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE;
    private static final int TYPE_FLAGS = VISIBILITY | Flags.TYPE_ABSTRACT | Flags.TYPE_FINAL | Flags.TYPE_SEALED
            | Flags.TYPE_UNSEALED;

    private final ModuleDef module;
    private final HeapWriter idents = new HeapWriter(SectionId.IDENTS);
    private final HeapWriter literals = new HeapWriter(SectionId.LITERALS);
    private final HeapWriter blobs = new HeapWriter(SectionId.BLOBS);
    private final List<ModuleRef> moduleRefs = new ArrayList<>();
    private final Map<ModuleRef, Integer> moduleRefRows = new HashMap<>();
    private final List<TypeDef> typeDefs = new ArrayList<>();
    private final Map<TypeDef, Integer> typeDefRows = new HashMap<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();
    private final Map<TypeRef, Integer> typeRefRows = new HashMap<>();

    private ModuleWriter(ModuleDef module) {
        this.module = module;
    }

    public static byte[] write(ModuleDef module) throws ModuleFormatException {
        var writer = new ModuleWriter(module);
        writer.numberModuleRefs();
        writer.numberTypeDefs();
        writer.numberTypeRefs();
        return writer.file();
    }

    private void numberModuleRefs() throws ModuleFormatException {
        for (ModuleRef ref : module.requires()) {
            if (moduleRefRows.containsKey(ref)) {
                throw new ModuleFormatException("the module lists the ModuleRef " + ref.name() + " twice");
            }
            checkFlags(ref.flags(), MODULE_REF_FLAGS, "ModuleRef " + ref.name());
            moduleRefRows.put(ref, 0);
            moduleRefs.add(ref);
        }
        moduleRefs.sort((left, right) -> Utf8.compare(left.name(), right.name()));
        for (int i = 0; i < moduleRefs.size(); i++) {
            if (i > 0 && Utf8.compare(moduleRefs.get(i - 1).name(), moduleRefs.get(i).name()) == 0) {
                throw new ModuleFormatException("two ModuleRef rows have the name " + moduleRefs.get(i).name() + " (BC-24)");
            }
            moduleRefRows.put(moduleRefs.get(i), i + 1);
        }
    }

    private void numberTypeDefs() throws ModuleFormatException {
        for (TypeDef type : module.types()) {
            if (typeDefRows.containsKey(type)) {
                throw new ModuleFormatException("the module lists the type " + type.name() + " twice");
            }
            typeDefRows.put(type, 0);
            typeDefs.add(type);
        }
        for (TypeDef type : typeDefs) {
            checkType(type);
        }
        typeDefs.sort(ModuleWriter::compareTypeDefs);
        for (int i = 0; i < typeDefs.size(); i++) {
            if (i > 0 && compareTypeDefs(typeDefs.get(i - 1), typeDefs.get(i)) == 0) {
                throw new ModuleFormatException("two TypeDef rows have the key of " + typeDefs.get(i).qualifiedName() + " (BC-24)");
            }
            typeDefRows.put(typeDefs.get(i), i + 1);
        }
    }

    private void checkType(TypeDef type) throws ModuleFormatException {
        String owner = "type " + type.name();
        if (type.kind() < Flags.KIND_CLASS || type.kind() > Flags.KIND_ANNOTATION) {
            throw new ModuleFormatException(owner + " has the undefined kind " + type.kind() + " (BC-20)");
        }
        checkFlags(type.flags(), TYPE_FLAGS, owner);
        checkVisibility(type.flags(), owner);
        if (type.kind() == Flags.KIND_ENUM && !integral(type.backing())) {
            throw new ModuleFormatException("enum " + type.name() + " has the backing type code " + type.backing()
                    + "; the backing type is one of I8, U8, I16, U16, I32, U32, I64 and U64 (DAT-05)");
        }
        if (type.kind() != Flags.KIND_ENUM && type.backing() != 0) {
            throw new ModuleFormatException(owner + " is not an enum, but has a backing type (§3.1)");
        }
        TypeDef outer = type.enclosing();
        if (outer != null) {
            if (!type.namespace().isEmpty()) {
                throw new ModuleFormatException("nested " + owner + " has a namespace; a nested type has namespace 0 (§3.1)");
            }
            if (!typeDefRows.containsKey(outer)) {
                throw new ModuleFormatException(owner + " is nested in a type that the module does not list");
            }
        }
    }

    // The key of BC-11: namespace, name, and the enclosing type, whose row follows the same order. The
    // recursion ends, since a type gets its enclosing type when it is made, so no chain is a cycle.
    private static int compareTypeDefs(TypeDef left, TypeDef right) {
        int order = Utf8.compare(left.namespace(), right.namespace());
        if (order == 0) {
            order = Utf8.compare(left.name(), right.name());
        }
        if (order != 0) {
            return order;
        }
        TypeDef a = left.enclosing();
        TypeDef b = right.enclosing();
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return compareTypeDefs(a, b);
    }

    private void numberTypeRefs() throws ModuleFormatException {
        for (TypeRef ref : module.typeRefs()) {
            if (typeRefRows.containsKey(ref)) {
                throw new ModuleFormatException("the module lists the TypeRef " + ref.name() + " twice");
            }
            typeRefRows.put(ref, 0);
            typeRefs.add(ref);
        }
        for (TypeRef ref : typeRefs) {
            String owner = "TypeRef " + ref.name();
            TypeScope scope = ref.scope();
            if (scope instanceof ModuleRef required) {
                if (!moduleRefRows.containsKey(required)) {
                    throw new ModuleFormatException(owner + " names the module " + required.name()
                            + ", which the module does not require");
                }
            } else if (scope instanceof TypeRef outer) {
                if (!ref.namespace().isEmpty()) {
                    throw new ModuleFormatException("nested " + owner + " has a namespace; a nested type has namespace 0 (§3.1)");
                }
                if (!typeRefRows.containsKey(outer)) {
                    throw new ModuleFormatException(owner + " is nested in a TypeRef that the module does not list");
                }
            }
        }
        typeRefs.sort(this::compareTypeRefs);
        for (int i = 0; i < typeRefs.size(); i++) {
            if (i > 0 && compareTypeRefs(typeRefs.get(i - 1), typeRefs.get(i)) == 0) {
                throw new ModuleFormatException("two TypeRef rows have the key of " + typeRefs.get(i).qualifiedName() + " (BC-24)");
            }
            typeRefRows.put(typeRefs.get(i), i + 1);
        }
    }

    // The key of BC-11: the scope token, then namespace and name. ModuleRef tokens come first.
    private int compareTypeRefs(TypeRef left, TypeRef right) {
        int order = compareScopes(left.scope(), right.scope());
        if (order == 0) {
            order = Utf8.compare(left.namespace(), right.namespace());
        }
        return order != 0 ? order : Utf8.compare(left.name(), right.name());
    }

    private int compareScopes(TypeScope left, TypeScope right) {
        if (left instanceof ModuleRef a) {
            if (right instanceof ModuleRef b) {
                return compareRows(moduleRefRows.getOrDefault(a, 0), moduleRefRows.getOrDefault(b, 0));
            }
            return -1;
        }
        if (right instanceof ModuleRef) {
            return 1;
        }
        return compareTypeRefs((TypeRef) left, (TypeRef) right);
    }

    private byte[] file() throws ModuleFormatException {
        var tables = new TablesWriter();
        tables.begin(TableId.MODULE, 1);
        tables.u32(idents.ident(module.name()));
        tables.u32(module.major());
        tables.u32(module.minor());
        tables.u32(module.patch());
        tables.u32(0);
        tables.u32(typeDefRow(module.main(), "the module's main class"));
        if (tables.begin(TableId.MODULE_REF, moduleRefs.size())) {
            for (ModuleRef ref : moduleRefs) {
                tables.u32(idents.ident(ref.name()));
                tables.u32(ref.flags());
            }
        }
        if (tables.begin(TableId.TYPE_DEF, typeDefs.size())) {
            for (TypeDef type : typeDefs) {
                tables.u32(idents.ident(type.name()));
                tables.u32(idents.ident(type.namespace()));
                tables.u32(type.kind());
                tables.u32(type.flags());
                tables.u32(typeToken(type.base(), "the base type of " + type.qualifiedName()));
                tables.u32(type.backing());
                tables.u32(typeDefRow(type.enclosing(), "the enclosing type of " + type.qualifiedName()));
                tables.u32(1);
                tables.u32(1);
                tables.u32(1);
            }
        }
        if (tables.begin(TableId.TYPE_REF, typeRefs.size())) {
            for (TypeRef ref : typeRefs) {
                tables.u32(scopeToken(ref.scope()));
                tables.u32(idents.ident(ref.namespace()));
                tables.u32(idents.ident(ref.name()));
            }
        }
        var sections = new ArrayList<Section>();
        sections.add(new Section(SectionId.IDENTS, idents.toByteArray()));
        sections.add(new Section(SectionId.LITERALS, literals.toByteArray()));
        sections.add(new Section(SectionId.BLOBS, blobs.toByteArray()));
        sections.add(new Section(SectionId.TABLES, tables.toByteArray()));
        for (Section section : module.ancillary()) {
            if (!SectionId.isAncillary(section.id())) {
                throw new ModuleFormatException("section " + ModuleFormatException.hex(section.id() & 0xFFFFFFFFL)
                        + " is kept as bytes, but it is not ancillary");
            }
            sections.add(section);
        }
        return ModuleFile.write(module.unsafe() ? Header.FLAG_UNSAFE : 0, sections);
    }

    private int typeDefRow(@Nullable TypeDef type, String what) throws ModuleFormatException {
        if (type == null) {
            return 0;
        }
        int row = typeDefRows.getOrDefault(type, 0);
        if (row == 0) {
            throw new ModuleFormatException(what + " is " + type.qualifiedName() + ", which the module does not list");
        }
        return row;
    }

    /** The token of a TypeDef or TypeRef row, or 0 for none. */
    private int typeToken(@Nullable TypeHandle handle, String what) throws ModuleFormatException {
        if (handle == null) {
            return 0;
        }
        int row;
        int table;
        if (handle instanceof TypeDef type) {
            row = typeDefRows.getOrDefault(type, 0);
            table = TableId.TYPE_DEF;
        } else {
            row = typeRefRows.getOrDefault((TypeRef) handle, 0);
            table = TableId.TYPE_REF;
        }
        if (row == 0) {
            throw new ModuleFormatException(what + " is " + handle.qualifiedName() + ", which the module does not list");
        }
        return (table << 24) | row;
    }

    private int scopeToken(TypeScope scope) {
        if (scope instanceof ModuleRef required) {
            return (TableId.MODULE_REF << 24) | moduleRefRows.getOrDefault(required, 0);
        }
        return (TableId.TYPE_REF << 24) | typeRefRows.getOrDefault((TypeRef) scope, 0);
    }

    static int compareRows(int left, int right) {
        return left < right ? -1 : left == right ? 0 : 1;
    }

    static boolean integral(int code) {
        return code == SigCode.I8 || code == SigCode.U8 || code == SigCode.I16 || code == SigCode.U16
                || code == SigCode.I32 || code == SigCode.U32 || code == SigCode.I64 || code == SigCode.U64;
    }

    private static void checkFlags(int flags, int defined, String owner) throws ModuleFormatException {
        if ((flags & ~defined) != 0) {
            throw new ModuleFormatException(owner + " has the undefined flags "
                    + ModuleFormatException.hex((flags & ~defined) & 0xFFFFFFFFL) + " (BC-20)");
        }
    }

    private static void checkVisibility(int flags, String owner) throws ModuleFormatException {
        int visibility = flags & VISIBILITY;
        if ((visibility & (visibility - 1)) != 0) {
            throw new ModuleFormatException(owner + " has more than one visibility flag (§3.1)");
        }
    }
}
