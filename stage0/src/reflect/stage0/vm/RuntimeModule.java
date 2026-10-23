package reflect.stage0.vm;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.ModuleDef;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.lib.Nullable;

/** A module that VM0 has read (VM-12): its file, its definition, and the module of each of its requires. */
final class RuntimeModule {
    private final String file;
    private final ModuleDef definition;
    private final Map<ModuleRef, RuntimeModule> required = new IdentityHashMap<>();
    private final Map<String, TypeDef> types = new HashMap<>();
    private final Map<MethodDef, TypeDef> owners = new IdentityHashMap<>();

    RuntimeModule(String file, ModuleDef definition) {
        this.file = file;
        this.definition = definition;
        for (TypeDef type : definition.types()) {
            types.put(type.qualifiedName(), type);
            for (MethodDef method : type.methods()) {
                owners.put(method, type);
            }
        }
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

    /** The type that the module defines with this name of GEN-12, or null. */
    @Nullable TypeDef type(String qualifiedName) {
        return types.get(qualifiedName);
    }

    /** The type that lists a method of the module. */
    TypeDef owner(MethodDef method) {
        return owners.get(method);
    }
}
