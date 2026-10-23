package reflect.stage0.vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.ModuleDef;
import reflect.stage0.bytecode.ModuleFormatException;
import reflect.stage0.bytecode.ModuleReader;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.lib.Nullable;

/**
 * The modules of one run (VM-12): the main module, and each module that its requires reach, read
 * at start-up. A source supplies the module of each name (Q88); one that it lacks is an error,
 * except for a requires static, which is optional at run time (MOD-01). A requires cycle is an
 * error (MOD-03).
 */
final class Modules {
    private final ModuleSource source;
    private final Map<String, RuntimeModule> byName = new HashMap<>();
    private final List<RuntimeModule> all = new ArrayList<>();
    private final List<String> chain = new ArrayList<>();

    private Modules(ModuleSource source) {
        this.source = source;
    }

    /** Reads the main module and the modules that it reaches; a Stop names what does not resolve. */
    static Modules load(ModuleData main, ModuleSource source) {
        var modules = new Modules(source);
        modules.resolve(main, null);
        return modules;
    }

    /** The main module. */
    RuntimeModule main() {
        return all.get(0);
    }

    /** The modules in the order that VM0 read them, the main module first. */
    List<RuntimeModule> all() {
        return all;
    }

    private RuntimeModule resolve(ModuleData data, @Nullable String name) {
        ModuleDef definition;
        try {
            definition = ModuleReader.read(data.bytes());
        } catch (ModuleFormatException e) {
            throw new Stop(data.file() + ": " + e.getMessage());
        }
        if (name != null && !definition.name().equals(name)) {
            throw new Stop(data.file() + " holds the module " + definition.name() + ", not " + name + " (MOD-03)");
        }
        var module = new RuntimeModule(data.file(), definition);
        byName.put(module.name(), module);
        all.add(module);
        chain.add(module.name());
        for (ModuleRef ref : definition.requires()) {
            int at = chain.indexOf(ref.name());
            if (at >= 0) {
                throw new Stop("a requires cycle (MOD-03): " + String.join(" requires ", chain.subList(at, chain.size()))
                        + " requires " + ref.name());
            }
            RuntimeModule target = byName.get(ref.name());
            if (target == null) {
                ModuleData found = source.find(ref.name());
                if (found == null) {
                    if ((ref.flags() & Flags.REQUIRES_STATIC) != 0) {
                        continue;
                    }
                    throw new Stop("no module " + ref.name() + " on the module path, which " + module.name() + " requires");
                }
                target = resolve(found, ref.name());
            }
            module.require(ref, target);
        }
        chain.remove(chain.size() - 1);
        return module;
    }
}
