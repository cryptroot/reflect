package reflect.stage0.vm;

import java.util.IdentityHashMap;
import java.util.Map;

import reflect.stage0.bytecode.ModuleDef;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.lib.Nullable;

/** A module that VM0 has read (VM-12): its file, its definition, and the module of each of its requires. */
final class RuntimeModule {
    private final String file;
    private final ModuleDef definition;
    private final Map<ModuleRef, RuntimeModule> required = new IdentityHashMap<>();

    RuntimeModule(String file, ModuleDef definition) {
        this.file = file;
        this.definition = definition;
    }

    String file() {
        return file;
    }

    ModuleDef definition() {
        return definition;
    }

    String name() {
        return definition.name();
    }

    /** The module of a ModuleRef row of this module, or null for a requires static that no file holds. */
    @Nullable RuntimeModule required(ModuleRef ref) {
        return required.get(ref);
    }

    void require(ModuleRef ref, RuntimeModule module) {
        required.put(ref, module);
    }
}
