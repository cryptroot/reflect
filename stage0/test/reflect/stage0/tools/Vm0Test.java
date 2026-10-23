package reflect.stage0.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class Vm0Test {
    private static final String HELLO = ".module hello 0.0.0\n.main hello.App\n.requires reflect.core\n"
            + ".requires reflect.io\n\n.class public final hello.App {\n"
            + "    .method public static void main(reflect.core.String[] args) {\n"
            + "        ldstr \"Hello, @C4@!\"\n"
            + "        call static void reflect.io.Console::println(reflect.core.Object?)\n"
            + "        ret\n    }\n}\n";

    @Test
    public static void vm0RunsAModuleWithTheModulePath() throws Exception {
        Path root = Files.createTempDirectory("reflect-vm0-");
        try {
            Path lib = stubs(root);
            Path hello = module(root, "hello", HELLO.replace("@C4@", "\\u00C4"));
            // A character outside ASCII, which the charset of the container's standard output would change.
            var expected = new Result(0, "Hello, " + (char) 0xC4 + "!\n", "");
            assertEquals(expected, vm0(root, "run", "--module-path", lib.toString(), hello.toString()));
            // --main names the main class of a module without .main, and the words after the module file
            // belong to main, options or not.
            Path plain = module(root, "plain", HELLO.replace(".main hello.App\n", "").replace("@C4@", "\\u00C4"));
            assertEquals(expected, vm0(root, "run", "--main", "hello.App", "--module-path", lib.toString(),
                    plain.toString(), "--main", "x"));
            assertEquals(new Result(2, "", "vm0: the module hello names no main class; name one with .main or"
                    + " --main\n"), vm0(root, "run", "--module-path", lib.toString(), plain.toString()));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void vm0ExitsWith1AfterAFault() throws Exception {
        Path root = Files.createTempDirectory("reflect-vm0-");
        try {
            Path lib = stubs(root);
            Path hello = module(root, "hello", HELLO.replace("@C4@", "x").replace("        ret\n    }\n",
                    "        ret\n    }\n    .method static native void f() {\n    }\n"));
            assertEquals(new Result(1, "", "reflect.core.TypeLoadError: the module hello declares the native static"
                    + " void hello.App::f(), but only trusted modules declare natives (LIB-03)\n"),
                    vm0(root, "run", "--module-path", lib.toString(), hello.toString()));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void vm0ExitsWith2WhenItCannotStart() throws Exception {
        Path root = Files.createTempDirectory("reflect-vm0-");
        try {
            Path hello = module(root, "hello", HELLO.replace("@C4@", "x"));
            assertEquals(new Result(2, "", "vm0: no module reflect.core on the module path, which hello requires\n"),
                    vm0(root, "run", hello.toString()));
            byte[] broken = Files.readAllBytes(hello);
            broken[0]++;
            Files.write(hello, broken);
            assertEquals(new Result(2, "", "vm0: " + hello + ": offset 0x0: the file does not start with RFLC\n"),
                    vm0(root, "run", hello.toString()));
            Result missing = vm0(root, "run", root.resolve("missing.rflc").toString());
            assertEquals(2, missing.exitCode());
            assertEquals("", missing.output());
            assertTrue(missing.error().startsWith("vm0: cannot read "));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void vm0ChecksItsArguments() throws Exception {
        Path root = Files.createTempDirectory("reflect-vm0-");
        try {
            var usage = new Result(2, "", "Usage: stage0 vm0 run [--module-path <dirs>] [--main <class>] <app.rflc>"
                    + " [args]\n");
            assertEquals(usage, vm0(root));
            assertEquals(usage, vm0(root, "start", "a.rflc"));
            assertEquals(usage, vm0(root, "run"));
            assertEquals(usage, vm0(root, "run", "--module-path"));
            assertEquals(usage, vm0(root, "run", "--module-path", "lib"));
            assertEquals(usage, vm0(root, "run", "--main", "a.A", "--main", "b.B", "a.rflc"));
            assertEquals(usage, vm0(root, "run", "--module-path", "a", "--module-path", "b", "a.rflc"));
            assertEquals(usage, vm0(root, "run", "--trace", "x", "a.rflc"));
        } finally {
            delete(root);
        }
    }

    // The stubs of lib/, assembled into a directory of the module path.
    private static Path stubs(Path root) throws Exception {
        Path lib = Files.createDirectory(root.resolve("lib"));
        for (String name : List.of("reflect.core", "reflect.io")) {
            module(lib, name, Files.readString(Path.of("tests/bytecode/lib/" + name + ".rfla")));
        }
        return lib;
    }

    private static Path module(Path directory, String name, String text) throws Exception {
        Path file = directory.resolve(name + ".rflc");
        Files.write(file, Assembler.assemble(text.getBytes(StandardCharsets.UTF_8)));
        return file;
    }

    // Runs the command in its own JVM, as a user does, with standard error in its own file.
    private static Result vm0(Path root, String... args) throws Exception {
        Path error = root.resolve("error.txt");
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "out/stage0/stage0.jar", "vm0"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectError(error.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        String text = Files.readString(error);
        Files.delete(error);
        return new Result(exitCode, output, text);
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record Result(int exitCode, String output, String error) {
    }
}
