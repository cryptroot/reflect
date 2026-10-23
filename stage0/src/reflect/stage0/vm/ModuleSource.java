package reflect.stage0.vm;

import reflect.stage0.lib.Nullable;

/** Where VM0 finds the module that a requires names: the module path, or in a test the modules of lib/. */
@FunctionalInterface
public interface ModuleSource {
    /** The module of the name, or null when the source has none. */
    @Nullable ModuleData find(String name);
}
