package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;

/**
 * Writes a module (README §7.3): it sorts the tables by their keys and numbers the rows, makes the
 * heap references in the canonical order of BC-24 while it writes the rows, and lays out the
 * sections. Content that breaks a rule, or exceeds a limit of BC-21, is an error, never a
 * truncated module.
 */
public final class ModuleWriter {
    private static final int MODULE_REF_FLAGS = Flags.REQUIRES_TRANSITIVE | Flags.REQUIRES_STATIC;

    private final ModuleDef module;
    private final HeapWriter idents = new HeapWriter(SectionId.IDENTS);
    private final HeapWriter literals = new HeapWriter(SectionId.LITERALS);
    private final HeapWriter blobs = new HeapWriter(SectionId.BLOBS);
    private final List<ModuleRef> moduleRefs = new ArrayList<>();
    private final Map<ModuleRef, Integer> moduleRefRows = new HashMap<>();

    private ModuleWriter(ModuleDef module) {
        this.module = module;
    }

    public static byte[] write(ModuleDef module) throws ModuleFormatException {
        var writer = new ModuleWriter(module);
        writer.number();
        return writer.file();
    }

    private void number() throws ModuleFormatException {
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

    private byte[] file() throws ModuleFormatException {
        var tables = new TablesWriter();
        tables.begin(TableId.MODULE, 1);
        tables.u32(idents.ident(module.name()));
        tables.u32(module.major());
        tables.u32(module.minor());
        tables.u32(module.patch());
        tables.u32(0);
        tables.u32(0);
        if (tables.begin(TableId.MODULE_REF, moduleRefs.size())) {
            for (ModuleRef ref : moduleRefs) {
                tables.u32(idents.ident(ref.name()));
                tables.u32(ref.flags());
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

    private static void checkFlags(int flags, int defined, String owner) throws ModuleFormatException {
        if ((flags & ~defined) != 0) {
            throw new ModuleFormatException(owner + " has the undefined flags "
                    + ModuleFormatException.hex((flags & ~defined) & 0xFFFFFFFFL) + " (BC-20)");
        }
    }
}
