package reflect.stage0.vm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class ModulePathTest {
    @Test
    public static void requiresResolveToTheFilesOfTheModulePath() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            Path app = directory(root, "app");
            write(lib, "a", ".module a 0.0.0\n.requires c\n");
            write(app, "b", ".module b 0.0.0\n.requires transitive c\n");
            write(lib, "c", ".module c 0.0.0\n");
            // lib twice is one directory, and the static s is absent, which run time allows (MOD-01).
            Modules modules = Modules.load(main(".module m 0.0.0\n.requires a\n.requires b\n.requires static s\n"),
                    new ModulePath(List.of(lib, app, lib)));
            assertEquals(List.of("m", "a", "c", "b"), names(modules));
            RuntimeModule m = modules.main();
            assertEquals("a", required(m, "a").name());
            assertEquals(lib.resolve("a.rflc").toString(), required(m, "a").file());
            assertEquals(null, m.required(ref(m, "s")));
            // c is read once, for both modules that require it.
            assertTrue(required(required(m, "a"), "c") == required(required(m, "b"), "c"));
            write(app, "s", ".module s 0.0.0\n");
            assertEquals(List.of("m", "a", "c", "b", "s"), names(Modules.load(main(".module m 0.0.0\n.requires a\n"
                    + ".requires b\n.requires static s\n"), new ModulePath(List.of(lib, app)))));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void aModuleThatThePathLacksStopsVm0() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            write(lib, "a", ".module a 0.0.0\n.requires y\n");
            var path = new ModulePath(List.of(lib));
            assertEquals("no module x on the module path, which m requires",
                    stop(".module m 0.0.0\n.requires x\n", path));
            assertEquals("no module y on the module path, which a requires",
                    stop(".module m 0.0.0\n.requires a\n", path));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void aModuleInTwoDirectoriesStopsVm0() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            Path app = directory(root, "app");
            write(lib, "x", ".module x 0.0.0\n");
            write(app, "x", ".module x 0.0.0\n");
            assertEquals("two directories of the module path hold x.rflc: " + lib.resolve("x.rflc") + " and "
                    + app.resolve("x.rflc") + " (MOD-03)", stop(".module m 0.0.0\n.requires x\n",
                    new ModulePath(List.of(lib, app))));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void aFileWithAnotherModuleStopsVm0() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            write(lib, "x", ".module y 0.0.0\n");
            assertEquals(lib.resolve("x.rflc") + " holds the module y, not x (MOD-03)",
                    stop(".module m 0.0.0\n.requires x\n", new ModulePath(List.of(lib))));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void aRequiresCycleStopsVm0() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            write(lib, "a", ".module a 0.0.0\n.requires b\n");
            write(lib, "b", ".module b 0.0.0\n.requires a\n");
            write(lib, "c", ".module c 0.0.0\n.requires m\n");
            var path = new ModulePath(List.of(lib));
            assertEquals("a requires cycle (MOD-03): a requires b requires a", stop(".module m 0.0.0\n.requires a\n", path));
            // The main module is in the chain too, so a module that requires it closes a cycle, even when
            // the path holds a file of its name.
            write(lib, "m", ".module m 0.0.0\n");
            assertEquals("a requires cycle (MOD-03): m requires c requires m", stop(".module m 0.0.0\n.requires c\n", path));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void aModuleThatTheReaderRejectsStopsVm0() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            byte[] module = Assembler.assemble(".module x 0.0.0\n".getBytes(StandardCharsets.UTF_8));
            module[0]++;
            Files.write(lib.resolve("x.rflc"), module);
            assertEquals(lib.resolve("x.rflc") + ": offset 0x0: the file does not start with RFLC",
                    stop(".module m 0.0.0\n.requires x\n", new ModulePath(List.of(lib))));
            Stop main = assertThrows(Stop.class, () -> Modules.load(new ModuleData("m.rflc", new byte[] {'R'}),
                    new ModulePath(List.of(lib))));
            assertTrue(main.getMessage().startsWith("m.rflc: offset 0x0: "));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void parseSplitsAtThePathSeparator() throws Exception {
        Path root = Files.createTempDirectory("reflect-path-");
        try {
            Path lib = directory(root, "lib");
            Path app = directory(root, "app");
            write(lib, "a", ".module a 0.0.0\n");
            write(app, "b", ".module b 0.0.0\n");
            // An empty element names no directory.
            ModulePath path = ModulePath.parse(lib + File.pathSeparator + File.pathSeparator + app);
            assertEquals(List.of(lib, app), path.directories());
            assertEquals(List.of("m", "a", "b"), names(Modules.load(main(".module m 0.0.0\n.requires a\n.requires b\n"),
                    path)));
            assertEquals(List.of(), ModulePath.parse("").directories());
        } finally {
            delete(root);
        }
    }

    private static String stop(String main, ModuleSource source) throws Exception {
        ModuleData data = main(main);
        return assertThrows(Stop.class, () -> Modules.load(data, source)).getMessage();
    }

    private static ModuleData main(String text) throws Exception {
        return new ModuleData("m.rflc", Assembler.assemble(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ModuleRef ref(RuntimeModule module, String name) {
        for (ModuleRef ref : module.definition().requires()) {
            if (ref.name().equals(name)) {
                return ref;
            }
        }
        throw new AssertionError("no requires " + name);
    }

    private static RuntimeModule required(RuntimeModule module, String name) {
        RuntimeModule required = module.required(ref(module, name));
        if (required == null) {
            throw new AssertionError("requires " + name + " has no module");
        }
        return required;
    }

    private static List<String> names(Modules modules) {
        var names = new ArrayList<String>();
        for (RuntimeModule module : modules.all()) {
            names.add(module.name());
        }
        return names;
    }

    private static Path directory(Path root, String name) throws Exception {
        return Files.createDirectory(root.resolve(name));
    }

    private static void write(Path directory, String name, String text) throws Exception {
        Files.write(directory.resolve(name + ".rflc"), Assembler.assemble(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
