package reflect.stage0.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.Disassembler;
import reflect.stage0.bytecode.MemberRef;
import reflect.stage0.bytecode.MethodSig;
import reflect.stage0.bytecode.ModuleDef;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.bytecode.ModuleWriter;
import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeRef;
import reflect.stage0.bytecode.TypeSig;
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
        assertEquals(null, check(text, Assembler::assemble, Disassembler::disassemble));
        // rfla gives the reader no module to reject, so this assembly breaks the magic of its module.
        Suite.Assembly faulty = source -> {
            byte[] module = Assembler.assemble(source);
            module[0]++;
            return module;
        };
        assertEquals("the reader rejects the module: offset 0x0: the file does not start with RFLC",
                check(text, faulty, Disassembler::disassemble));
    }

    @Test
    public static void testReportsAModuleThatDoesNotRoundTrip() throws Exception {
        // The file need not be canonical: the round trip compares its module with the module of its canonical text.
        byte[] text = "// a comment\n.module m 0.0.0\n.requires b\n.requires a\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(null, check(text, Assembler::assemble, Disassembler::disassemble));
        // rfla writes no GENERIC MemberRef before MethodSpec (v0.0.9), so this assembly gives a module with one.
        var generic = new ModuleDef("m", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        var list = new TypeRef(core, "reflect.core", "List");
        generic.requires().add(core);
        generic.typeRefs().add(list);
        generic.memberRefs().add(new MemberRef(list, "get", new MethodSig(SigCode.METHOD_STATIC | SigCode.METHOD_GENERIC,
                1, new TypeSig.TypeVar(true, 0), new reflect.stage0.lib.ArrayList<>())));
        byte[] module = ModuleWriter.write(generic);
        assertEquals("the module has no canonical text: the MemberRef reflect.core.List::get is GENERIC, which a MethodRef"
                + " cannot state before the MethodSpec table arrives in v0.0.9",
                check(text, source -> module, Disassembler::disassemble));
        // rfld's canonical text has none of these defects, so these disassemblies add them. The canonical text
        // of the file holds three lines: .module, then .requires a and .requires b.
        assertEquals("the canonical text does not assemble: 4:1: RFA0101: expected a directive of the module, found x",
                check(text, Assembler::assemble, m -> append(Disassembler.disassemble(m), "x\n")));
        // .unsafe sets UNSAFE, bit 0 of the header flags, a u32 at offset 8 (BC-02).
        assertEquals("the canonical text assembles to other bytes, from offset 0x8",
                check(text, Assembler::assemble, m -> append(Disassembler.disassemble(m), ".unsafe\n")));
        var calls = new int[1];
        Suite.Disassembly unstable = m -> calls[0]++ == 0 ? Disassembler.disassemble(m)
                : append(Disassembler.disassemble(m), "// a comment\n");
        assertEquals("the canonical text changes in the round trip, from line 4",
                check(text, Assembler::assemble, unstable));
        assertEquals(2, calls[0]);
    }

    @Test
    public static void testRunsTheProgramsOfRunWithTheModulesOfLib() throws Exception {
        Path root = Files.createTempDirectory("reflect-suite-");
        try {
            for (String name : List.of("reflect.core", "reflect.io")) {
                write(root, "lib/" + name + ".rfla", Files.readString(Path.of("tests/bytecode/lib/" + name + ".rfla")));
            }
            // Only the files directly in lib/ are modules of the module path, so this one names no module.
            write(root, "lib/zz/reflect.io.rfla", ".module reflect.io 0.0.0\n");
            String println = "        call static void reflect.io.Console::println(reflect.core.Object?)\n";
            write(root, "format/d.rfla", "// expect-output: x\n.module d 0.0.0\n");
            write(root, "run/a.rfla", program("a", "// expect-output: Hello, world!\n", "        ldstr \"Hello, world!\"\n"
                    + println));
            write(root, "run/b.rfla", program("b", "// expect-output: Hello\n", "        ldstr \"Hello, world!\"\n"
                    + println));
            write(root, "run/c.rfla", program("c", "", "        ldstr \"x\"\n" + println));
            write(root, "run/e.rfla", ".module e 0.0.0\n");
            // A value keeps the spaces after the first, and an empty value is an empty line.
            write(root, "run/f.rfla", program("f", "// expect-output:  x\n// expect-output:\n", "        ldstr \" x\"\n"
                    + println + "        call static void reflect.io.Console::println()\n"));
            write(root, "run/g.rfla", program("g", "// expect-output: x\n", "        ldstr \"x\"\n"
                    + "        call static void reflect.io.Console::print(reflect.core.Object?)\n"));
            write(root, "run/h.rfla", program("h", "// expect-output: x\n// expect-output: y\n", "        ldstr \"x\"\n"
                    + println));
            assertEquals(new Result(1, "FAIL " + root.resolve("format/d.rfla") + ": only a file in run/ holds an"
                    + " expect-output\nPASS " + root.resolve("lib/reflect.core.rfla") + "\nPASS "
                    + root.resolve("lib/reflect.io.rfla") + "\nPASS " + root.resolve("lib/zz/reflect.io.rfla")
                    + "\nPASS " + root.resolve("run/a.rfla") + "\nFAIL "
                    + root.resolve("run/b.rfla") + ": output line 1 is \"Hello, world!\", expected \"Hello\"\nFAIL "
                    + root.resolve("run/c.rfla") + ": output line 1 is \"x\", expected no more output\nFAIL "
                    + root.resolve("run/e.rfla") + ": the exit code is 2, expected 0: vm0: the module e names no main"
                    + " class; name one with .main or --main\nPASS " + root.resolve("run/f.rfla") + "\nFAIL "
                    + root.resolve("run/g.rfla") + ": output line 1 is \"x\" without a line end, expected \"x\"\nFAIL "
                    + root.resolve("run/h.rfla") + ": output line 2 is missing, expected \"y\"\n11 files, 6 failures\n"),
                    test(root.toString()));
        } finally {
            delete(root);
        }
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

    // The check of a file outside invalid/ and run/, which runs no program.
    private static String check(byte[] text, Suite.Assembly assembly, Suite.Disassembly disassembly) {
        return Suite.check(Suite.Place.OTHER, "m.rfla", text, assembly, disassembly, name -> null);
    }

    // A program of the module whose main class prints with these lines of main.
    private static String program(String module, String directives, String main) {
        return directives + ".module " + module + " 0.0.0\n.main " + module + ".App\n.requires reflect.core\n"
                + ".requires reflect.io\n\n.class public final " + module + ".App {\n"
                + "    .method public static void main(reflect.core.String[] args) {\n" + main + "        ret\n    }\n}\n";
    }

    private static byte[] append(byte[] text, String more) {
        return (new String(text, StandardCharsets.UTF_8) + more).getBytes(StandardCharsets.UTF_8);
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
