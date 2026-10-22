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

public final class SuiteTest {
    @Test
    public static void testPassesWhenEveryFileMeetsItsExpectation() throws Exception {
        Path root = Files.createTempDirectory("reflect-suite-");
        try {
            write(root, "format/a.rfla", ".module a 0.0.0\n");
            write(root, "invalid/b.rfla", "// a comment\n// expect-error: RFA0101 @ 3:17\n.module b 0.0.0 x\n");
            assertEquals(new Result(0, "PASS " + root.resolve("format/a.rfla") + "\nPASS " + root.resolve("invalid/b.rfla")
                    + "\n2 files, 0 failures\n"), test(root.toString()));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void testReportsEachFileThatFails() throws Exception {
        Path root = Files.createTempDirectory("reflect-suite-");
        try {
            String error = "RFA0101: expected a directive of the module, found x";
            write(root, "format/a.rfla", ".module a 0.0.0 x\n");
            write(root, "format/b.rfla", "// expect-error: RFA0101 @ 2:17\n.module b 0.0.0 x\n");
            write(root, "invalid/c.rfla", "// expect-error: RFA0101 @ 2:1\n.module c 0.0.0 x\n");
            write(root, "invalid/d.rfla", "// expect-error: RFA0201 @ 2:17\n.module d 0.0.0 x\n");
            write(root, "invalid/e.rfla", "// expect-error: RFA0101 @ 2:1\n.module e 0.0.0\n");
            write(root, "invalid/f.rfla", ".module f 0.0.0 x\n// expect-error: RFA0101 @ 1:17\n");
            write(root, "invalid/g.rfla", "// expect-error: RFA0101 @ 3:17\n// expect-error: RFA0101 @ 3:17\n"
                    + ".module g 0.0.0 x\n");
            assertEquals(new Result(1, "FAIL " + root.resolve("format/a.rfla") + ": 1:17: " + error + "\n"
                    + "FAIL " + root.resolve("format/b.rfla") + ": only a file in invalid/ holds an expect-error\n"
                    + "FAIL " + root.resolve("invalid/c.rfla") + ": expected RFA0101 @ 2:1, found 2:17: " + error + "\n"
                    + "FAIL " + root.resolve("invalid/d.rfla") + ": expected RFA0201 @ 2:17, found 2:17: " + error + "\n"
                    + "FAIL " + root.resolve("invalid/e.rfla") + ": expected RFA0101 @ 2:1, but the file assembles\n"
                    + "FAIL " + root.resolve("invalid/f.rfla") + ": a file in invalid/ holds one expect-error, not 0\n"
                    + "FAIL " + root.resolve("invalid/g.rfla") + ": a file in invalid/ holds one expect-error, not 2\n"
                    + "7 files, 7 failures\n"), test(root.toString()));
        } finally {
            delete(root);
        }
    }

    @Test
    public static void testReportsAModuleThatTheReaderRejects() throws Exception {
        byte[] text = ".module a 0.0.0\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(null, Suite.check(false, text, Assembler::assemble));
        // rfla gives the reader no module to reject, so this assembly breaks the magic of its module.
        Suite.Assembly faulty = source -> {
            byte[] module = Assembler.assemble(source);
            module[0]++;
            return module;
        };
        assertEquals("the reader rejects the module: offset 0x0: the file does not start with RFLC",
                Suite.check(false, text, faulty));
    }

    @Test
    public static void testNeedsOneDirectoryWithRflaFiles() throws Exception {
        Path root = Files.createTempDirectory("reflect-suite-");
        try {
            write(root, "format/a.txt", "");
            assertEquals(new Result(2, "test: no .rfla files in " + root + "\n"), test(root.toString()));
            Result missing = test(root.resolve("missing").toString());
            assertEquals(2, missing.exitCode());
            assertTrue(missing.output().startsWith("test: cannot read "));
            var usage = new Result(2, "Usage: stage0 test <directory>\n");
            assertEquals(usage, test());
            assertEquals(usage, test(root.toString(), "extra"));
        } finally {
            delete(root);
        }
    }

    // Runs the command in its own JVM, as a user does, with standard error in standard output.
    private static Result test(String... args) throws Exception {
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "out/stage0/stage0.jar", "test"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private static void write(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record Result(int exitCode, String output) {
    }
}
