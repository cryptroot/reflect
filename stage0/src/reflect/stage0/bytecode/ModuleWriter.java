package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Writes a module (README §7.3): it sorts the tables by their keys and numbers the rows, makes the
 * heap references in the canonical order of BC-24 while it writes the rows, lays out the sections,
 * and writes DEBUG from the line entries and local names of the bodies. Content that breaks a rule,
 * or exceeds a limit of BC-21, is an error, never a truncated module.
 */
public final class ModuleWriter {
    static final int MODULE_REF_FLAGS = Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC;
    static final int VISIBILITY = Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE;
    static final int TYPE_FLAGS = VISIBILITY | Flags.TYPE_ABSTRACT | Flags.TYPE_FINAL | Flags.TYPE_SEALED
            | Flags.TYPE_UNSEALED;
    static final int FIELD_FLAGS = VISIBILITY | Flags.FIELD_STATIC | Flags.FIELD_FINAL | Flags.FIELD_CONST
            | Flags.FIELD_BACKING;
    static final int METHOD_FLAGS = VISIBILITY | Flags.METHOD_STATIC | Flags.METHOD_VIRTUAL | Flags.METHOD_ABSTRACT
            | Flags.METHOD_FINAL | Flags.METHOD_CONSTRUCTOR | Flags.METHOD_NATIVE | Flags.METHOD_EXTERN
            | Flags.METHOD_UNSAFE | Flags.METHOD_MUTATING | Flags.METHOD_CONST | Flags.METHOD_PRECOMPILE
            | Flags.METHOD_SYNTHESIZED;
    static final int PARAM_FLAGS = Flags.PARAM_SCOPED | Flags.PARAM_THIS;
    // Until GenericParam rows exist (v0.0.9), no definition has type parameters; the parameters of
    // the type of a MemberRef are not known to this module.
    static final Signatures.Scope DEFINITION = new Signatures.Scope(0, 0);
    static final Signatures.Scope REFERENCE = new Signatures.Scope(-1, 0);

    private final ModuleDef module;
    private final HeapWriter idents = new HeapWriter(SectionId.IDENTS);
    private final HeapWriter literals = new HeapWriter(SectionId.LITERALS);
    private final HeapWriter blobs = new HeapWriter(SectionId.BLOBS);
    private final List<ModuleRef> moduleRefs = new ArrayList<>();
    private final Map<ModuleRef, Integer> moduleRefRows = new HashMap<>();
    private final List<Export> exports = new ArrayList<>();
    private final List<TypeDef> typeDefs = new ArrayList<>();
    private final Map<TypeDef, Integer> typeDefRows = new HashMap<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();
    private final Map<TypeRef, Integer> typeRefRows = new HashMap<>();
    private final List<FieldDef> fields = new ArrayList<>();
    private final Map<FieldDef, Integer> fieldRows = new HashMap<>();
    private final List<MethodDef> methods = new ArrayList<>();
    private final Map<MethodDef, Integer> methodRows = new HashMap<>();
    private final List<String> methodOwners = new ArrayList<>();
    private final List<ParamDef> params = new ArrayList<>();
    private final Map<ParamDef, Integer> paramRows = new HashMap<>();
    private final Map<TypeDef, Integer> firstFields = new HashMap<>();
    private final Map<TypeDef, Integer> firstMethods = new HashMap<>();
    private final Map<MethodDef, Integer> firstParams = new HashMap<>();
    private final List<MemberRef> memberRefs = new ArrayList<>();
    private final Map<MemberRef, Integer> memberRefRows = new HashMap<>();
    private final Map<MemberRef, byte[]> memberRefSignatures = new HashMap<>();

    private ModuleWriter(ModuleDef module) {
        this.module = module;
    }

    public static byte[] write(ModuleDef module) throws ModuleFormatException {
        var writer = new ModuleWriter(module);
        writer.numberModuleRefs();
        writer.sortExports();
        writer.numberTypeDefs();
        writer.numberTypeRefs();
        writer.numberMembers();
        writer.numberMemberRefs();
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

    // Nothing names an Export row, so its rows need only their order and unique keys (BC-24).
    private void sortExports() throws ModuleFormatException {
        for (Export export : module.exports()) {
            if (export.kind() != Flags.EXPORTS && export.kind() != Flags.OPENS) {
                throw new ModuleFormatException("Export " + export.namespace() + " has the undefined kind "
                        + export.kind() + " (BC-20)");
            }
            exports.add(export);
        }
        exports.sort(ModuleWriter::compareExports);
        for (int i = 1; i < exports.size(); i++) {
            if (compareExports(exports.get(i - 1), exports.get(i)) == 0) {
                Export export = exports.get(i);
                throw new ModuleFormatException("two Export rows have the key of "
                        + (export.kind() == Flags.OPENS ? "opens " : "exports ") + export.namespace()
                        + (export.target().isEmpty() ? "" : " to " + export.target()) + " (BC-24)");
            }
        }
    }

    // The key of BC-11 is every column: the namespace, the kind, then the target, whose empty name,
    // every module, comes first.
    static int compareExports(Export left, Export right) {
        int order = Utf8.compare(left.namespace(), right.namespace());
        if (order == 0) {
            order = compareRows(left.kind(), right.kind());
        }
        return order != 0 ? order : Utf8.compare(left.target(), right.target());
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
            throw new ModuleFormatException(owner + " is not an enum, but has a backing type (BC-11)");
        }
        TypeDef outer = type.enclosing();
        if (outer != null) {
            if (!type.namespace().isEmpty()) {
                throw new ModuleFormatException("nested " + owner + " has a namespace; a nested type has namespace 0 (BC-11)");
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
                    throw new ModuleFormatException("nested " + owner + " has a namespace; a nested type has namespace 0 (BC-11)");
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

    // Field and Method rows keep the runs of their types in row order, and Param rows the runs of
    // their methods. An empty run starts where the next one does, so with no rows it starts at 1.
    private void numberMembers() throws ModuleFormatException {
        for (TypeDef type : typeDefs) {
            firstFields.put(type, fields.size() + 1);
            for (FieldDef field : type.fields()) {
                String owner = "field " + type.qualifiedName() + "::" + field.name();
                if (fieldRows.containsKey(field)) {
                    throw new ModuleFormatException(owner + " belongs to two types or appears twice");
                }
                checkFlags(field.flags(), FIELD_FLAGS, owner);
                checkVisibility(field.flags(), owner);
                if ((field.flags() & Flags.FIELD_CONST) != 0 && (field.flags() & Flags.FIELD_STATIC) == 0) {
                    throw new ModuleFormatException(owner + " is const but not static; a const field is also STATIC (BC-11)");
                }
                check(Signatures.problem(field.type(), Signatures.OTHER, DEFINITION, module.name()), owner);
                fields.add(field);
                fieldRows.put(field, fields.size());
            }
            firstMethods.put(type, methods.size() + 1);
            for (MethodDef method : type.methods()) {
                String owner = "method " + type.qualifiedName() + "::" + method.name();
                if (methodRows.containsKey(method)) {
                    throw new ModuleFormatException(owner + " belongs to two types or appears twice");
                }
                checkMethod(method, owner);
                checkSignature(method, owner);
                checkBody(method, owner);
                methods.add(method);
                methodRows.put(method, methods.size());
                methodOwners.add(owner);
                firstParams.put(method, params.size() + 1);
                for (ParamDef param : method.params()) {
                    if (paramRows.containsKey(param)) {
                        throw new ModuleFormatException("a parameter of " + owner + " belongs to two methods or appears twice");
                    }
                    checkFlags(param.flags(), PARAM_FLAGS, "parameter " + param.name() + " of " + owner);
                    params.add(param);
                    paramRows.put(param, params.size());
                }
            }
        }
    }

    private void numberMemberRefs() throws ModuleFormatException {
        for (MemberRef ref : module.memberRefs()) {
            String owner = "MemberRef " + ref.parent().qualifiedName() + "::" + ref.name();
            if (memberRefRows.containsKey(ref)) {
                throw new ModuleFormatException("the module lists the " + owner + " twice");
            }
            if (!(ref.parent() instanceof TypeRef parent)) {
                throw new ModuleFormatException(owner + " has a TypeDef as parent; a MemberRef names a member of a"
                        + " TypeRef or TypeSpec (BC-11)");
            }
            if (!typeRefRows.containsKey(parent)) {
                throw new ModuleFormatException(owner + " has a parent that the module does not list");
            }
            MemberSig signature = ref.signature();
            if (signature instanceof FieldSig field) {
                check(Signatures.problem(field.type(), Signatures.OTHER, REFERENCE, module.name()), owner);
            } else {
                check(Signatures.problem((MethodSig) signature, REFERENCE, module.name()), owner);
            }
            memberRefSignatures.put(ref, Signatures.member(signature, this::signatureToken));
            memberRefRows.put(ref, 0);
            memberRefs.add(ref);
        }
        memberRefs.sort(this::compareMemberRefs);
        for (int i = 0; i < memberRefs.size(); i++) {
            if (i > 0 && compareMemberRefs(memberRefs.get(i - 1), memberRefs.get(i)) == 0) {
                MemberRef ref = memberRefs.get(i);
                throw new ModuleFormatException("two MemberRef rows have the key of " + ref.parent().qualifiedName()
                        + "::" + ref.name() + " (BC-24)");
            }
            memberRefRows.put(memberRefs.get(i), i + 1);
        }
    }

    // The key of BC-11: parent token, name, then the bytes of the signature.
    private int compareMemberRefs(MemberRef left, MemberRef right) {
        int order = compareRows(typeRefRows.getOrDefault((TypeRef) left.parent(), 0),
                typeRefRows.getOrDefault((TypeRef) right.parent(), 0));
        if (order == 0) {
            order = Utf8.compare(left.name(), right.name());
        }
        return order != 0 ? order : Blob.compare(memberRefSignatures.getOrDefault(left, new byte[0]),
                memberRefSignatures.getOrDefault(right, new byte[0]));
    }

    // The rules that tie a definition's signature to its row: STATIC in both or in neither, no
    // GENERIC until GenericParam rows exist, and one Param row per parameter (BC-11, BC-24).
    private void checkSignature(MethodDef method, String owner) throws ModuleFormatException {
        MethodSig signature = method.signature();
        check(Signatures.problem(signature, DEFINITION, module.name()), owner);
        if (((signature.flags() & SigCode.METHOD_STATIC) != 0) != ((method.flags() & Flags.METHOD_STATIC) != 0)) {
            throw new ModuleFormatException(owner + ": the signature has STATIC exactly when the method has (BC-11)");
        }
        if ((signature.flags() & SigCode.METHOD_GENERIC) != 0) {
            throw new ModuleFormatException(owner + ": the signature is GENERIC, but the method has no GenericParam"
                    + " rows (BC-24)");
        }
        if (signature.params().size() != method.params().size()) {
            throw new ModuleFormatException(owner + ": the signature has " + signature.params().size()
                    + " parameters, but the method has " + method.params().size() + " Param rows (BC-11)");
        }
    }

    // A method has a body exactly when it is not ABSTRACT, NATIVE or EXTERN (§3.1).
    private void checkBody(MethodDef method, String owner) throws ModuleFormatException {
        MethodBody body = method.body();
        if (Code.hasBody(method.flags()) != (body != null)) {
            throw new ModuleFormatException(owner + (body == null ? " has no body" : " has a body")
                    + "; a method has one exactly when it is not ABSTRACT, NATIVE or EXTERN (BC-11)");
        }
        if (body != null) {
            check(Code.problem(body, Code.arguments(method.signature()), module.name()), owner);
        }
    }

    private static void check(@Nullable String problem, String owner) throws ModuleFormatException {
        if (problem != null) {
            throw new ModuleFormatException(owner + ": " + problem);
        }
    }

    private int signatureToken(TypeHandle type) throws ModuleFormatException {
        return typeToken(type, "a type in a signature");
    }

    // The token of a row that an instruction names.
    private int token(Handle handle) throws ModuleFormatException {
        if (handle instanceof TypeHandle type) {
            return typeToken(type, "a type in an instruction");
        }
        int row;
        int table;
        String name;
        if (handle instanceof FieldDef field) {
            row = fieldRows.getOrDefault(field, 0);
            table = TableId.FIELD;
            name = "the field " + field.name();
        } else if (handle instanceof MethodDef method) {
            row = methodRows.getOrDefault(method, 0);
            table = TableId.METHOD;
            name = "the method " + method.name();
        } else {
            var ref = (MemberRef) handle;
            row = memberRefRows.getOrDefault(ref, 0);
            table = TableId.MEMBER_REF;
            name = "the MemberRef " + ref.parent().qualifiedName() + "::" + ref.name();
        }
        if (row == 0) {
            throw new ModuleFormatException("an instruction names " + name + ", which the module does not list");
        }
        return (table << 24) | row;
    }

    private static void checkMethod(MethodDef method, String owner) throws ModuleFormatException {
        checkFlags(method.flags(), METHOD_FLAGS, owner);
        checkVisibility(method.flags(), owner);
        String problem = constructorName(method.flags(), method.name());
        if (problem != null) {
            throw new ModuleFormatException(owner + ": " + problem);
        }
        if (method.params().size() > Limits.MAX_PARAMS) {
            throw new ModuleFormatException(owner + " has " + method.params().size()
                    + " parameters, more than the limit of " + Limits.MAX_PARAMS + " (BC-21)");
        }
    }

    /** Why the name does not fit the CONSTRUCTOR and STATIC flags (§3.1), or null when it does. */
    static @Nullable String constructorName(int flags, String name) {
        boolean constructor = (flags & Flags.METHOD_CONSTRUCTOR) != 0;
        String expected = (flags & Flags.METHOD_STATIC) != 0 ? "static" : "new";
        if (constructor && !name.equals(expected)) {
            return "a constructor is named new and a static initializer static, so this one is " + expected + " (BC-11)";
        }
        if (!constructor && (name.equals("new") || name.equals("static"))) {
            return "only constructors and static initializers are named new and static (BC-11)";
        }
        return null;
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
        if (tables.begin(TableId.EXPORT, exports.size())) {
            for (Export export : exports) {
                tables.u32(idents.ident(export.namespace()));
                tables.u32(export.kind());
                tables.u32(idents.ident(export.target()));
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
                tables.u32(firstFields.getOrDefault(type, 0));
                tables.u32(firstMethods.getOrDefault(type, 0));
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
        if (tables.begin(TableId.FIELD, fields.size())) {
            for (FieldDef field : fields) {
                tables.u32(field.flags());
                tables.u32(idents.ident(field.name()));
                tables.u32(blobs.blob(Signatures.field(field.type(), this::signatureToken)));
            }
        }
        // The heap entries of the bodies follow those of every table (BC-24), so the code columns are
        // filled in once the bodies are laid out.
        int[] codeColumns = new int[methods.size()];
        if (tables.begin(TableId.METHOD, methods.size())) {
            for (int i = 0; i < methods.size(); i++) {
                MethodDef method = methods.get(i);
                tables.u32(method.flags());
                tables.u32(idents.ident(method.name()));
                tables.u32(blobs.blob(Signatures.method(method.signature(), this::signatureToken)));
                codeColumns[i] = tables.position();
                tables.u32(0);
                tables.u32(firstParams.getOrDefault(method, 0));
                tables.u32(1);
            }
        }
        if (tables.begin(TableId.PARAM, params.size())) {
            for (ParamDef param : params) {
                tables.u32(param.flags());
                tables.u32(idents.ident(param.name()));
            }
        }
        if (tables.begin(TableId.MEMBER_REF, memberRefs.size())) {
            for (MemberRef ref : memberRefs) {
                tables.u32(typeToken(ref.parent(), "the parent of a MemberRef"));
                tables.u32(idents.ident(ref.name()));
                tables.u32(blobs.blob(memberRefSignatures.getOrDefault(ref, new byte[0])));
            }
        }
        // The bodies follow one another in the order of their Method rows (BC-17).
        var code = new ByteSink();
        int[][] starts = new int[methods.size()][];
        for (int i = 0; i < methods.size(); i++) {
            MethodDef method = methods.get(i);
            MethodBody body = method.body();
            starts[i] = new int[0];
            if (body != null) {
                tables.set(codeColumns[i], code.size());
                starts[i] = Code.body(body, code, blobs, literals, this::token, methodOwners.get(i));
            }
        }
        var sections = new ArrayList<Section>();
        sections.add(new Section(SectionId.IDENTS, idents.toByteArray()));
        sections.add(new Section(SectionId.LITERALS, literals.toByteArray()));
        sections.add(new Section(SectionId.BLOBS, blobs.toByteArray()));
        sections.add(new Section(SectionId.TABLES, tables.toByteArray()));
        if (code.size() > 0) {
            sections.add(new Section(SectionId.CODE, code.toByteArray()));
        }
        int flags = module.unsafe() ? Header.FLAG_UNSAFE : 0;
        // DEBUG starts with the contentHash, which leaves the ancillary sections out (BC-22).
        byte[] hash = ModuleFile.contentHash(flags, sections);
        byte[] debug = Debug.write(methods, starts, methodOwners, hash);
        if (debug.length > 0) {
            sections.add(new Section(SectionId.DEBUG, debug));
        }
        for (Section section : module.ancillary()) {
            String id = ModuleFormatException.hex(section.id() & 0xFFFFFFFFL);
            if (!SectionId.isAncillary(section.id())) {
                throw new ModuleFormatException("section " + id + " is kept as bytes, but it is not ancillary");
            }
            // A reader would read this DEBUG instead of keeping it (BC-20).
            if (section.id() == SectionId.DEBUG && !Debug.stale(section.content(), hash)) {
                throw new ModuleFormatException("section " + id + " is kept as bytes, but it starts with the module's"
                        + " contentHash, so it is not a stale DEBUG (BC-20)");
            }
            sections.add(section);
        }
        return ModuleFile.write(flags, sections);
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
            throw new ModuleFormatException(owner + " has more than one visibility flag (BC-11)");
        }
    }
}
