package reflect.stage0.vm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.Opcode;
import reflect.stage0.lib.Nullable;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class VmTest {
    private static final String PRINTLN = "        call static void reflect.io.Console::println(reflect.core.Object?)\n";
    private static final String ARITHMETIC_EXCEPTION = "reflect.core.ArithmeticException";
    private static final String TYPE_LOAD_ERROR = "reflect.core.TypeLoadError";

    @Test
    public static void helloPrintsItsLine() throws Exception {
        assertEquals(new Run(0, null, "Hello, world!\n", ""), run(program("        ldstr \"Hello, world!\"\n" + PRINTLN
                + "        ret\n", "")));
    }

    @Test
    public static void callsPassTheirArgumentsAndResults() throws Exception {
        String main = "        ldstr \"x\"\n        ldstr \"y\"\n"
                + "        call m.App::second(reflect.core.String, reflect.core.String)\n" + PRINTLN
                + "        ldstr \"a\"\n        call static void reflect.io.Console::print(reflect.core.Object?)\n"
                + "        call static void reflect.io.Console::println()\n"
                + "        ldstr \"b\"\n        call m.App::show(reflect.core.String)\n        ret\n";
        String others = "    .method static reflect.core.String second(reflect.core.String first,"
                + " reflect.core.String second) {\n        ldarg second\n        ret\n    }\n"
                + "    .method static void show(reflect.core.String text) {\n        ldarg 0\n" + PRINTLN
                + "        ret\n    }\n";
        assertEquals(new Run(0, null, "y\na\nb\n", ""), run(program(main, others)));
    }

    @Test
    public static void intMainGivesTheExitCode() throws Exception {
        String main = ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires reflect.io\n\n"
                + ".class public final m.App {\n    .method public static int main(reflect.core.String[] args) {\n"
                + "        ldstr \"x\"\n" + PRINTLN + "        ldc.i4 %d\n        ret\n    }\n}\n";
        assertEquals(new Run(3, null, "x\n", ""), run(main.formatted(3)));
        // The run gives the whole int (README §6.2).
        assertEquals(new Run(-1, null, "x\n", ""), run(main.formatted(-1)));
    }

    @Test
    public static void runtimeExitEndsTheRunAtTheCall() throws Exception {
        String quit = "    .method static void quit() {\n        ldstr \"before\"\n" + PRINTLN + "        ldc.i4 4\n"
                + "        call static void reflect.core.Runtime::exit(int)\n        ldstr \"after\"\n" + PRINTLN
                + "        ret\n    }\n";
        assertEquals(new Run(4, null, "before\n", ""), run(program("        call static void m.App::quit()\n"
                + "        ldstr \"end\"\n" + PRINTLN + "        ret\n", quit)));
        // Its argument replaces the result of an int main.
        String main = ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires reflect.io\n\n"
                + ".class public final m.App {\n    .method public static int main(reflect.core.String[] args) {\n"
                + "        call static void m.App::quit()\n        ldc.i4 3\n        ret\n    }\n" + quit + "}\n";
        assertEquals(new Run(4, null, "before\n", ""), run(main));
    }

    @Test
    public static void onlyTrustedModulesDeclareNatives() throws Exception {
        String hello = "        ldstr \"Hello\"\n" + PRINTLN + "        ret\n";
        // The main class loads before main runs, so its native stops the run before any output (LIB-03).
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module m declares the native static void"
                + " m.App::f(), but only trusted modules declare natives (LIB-03)\n"),
                run(program(hello, "    .method static native void f() {\n    }\n")));
        // A trusted module may declare only the natives that VM0 has (VM-08).
        String io = Files.readString(Path.of("tests/bytecode/lib/reflect.io.rfla")).replace(
                "extends reflect.core.Object {\n", "extends reflect.core.Object {\n"
                + "    .method public static native void beep() {\n    }\n");
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": VM0 has no native static void"
                + " reflect.io.Console::beep() (VM-08)\n"), run(program(hello, ""), null, Map.of("reflect.io", io)));
        // Each native belongs to one trusted module, so the write of reflect.io does not bind in reflect.core.
        String core = Files.readString(Path.of("tests/bytecode/lib/reflect.core.rfla"))
                + "\n.class public final reflect.io.Console {\n"
                + "    .method public static native void write(int fd, reflect.core.String text) {\n    }\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": VM0 has no native static void"
                + " reflect.io.Console::write(int, reflect.core.String) (VM-08)\n"), run(program("        ldc.i4 1\n"
                + "        ldstr \"x\"\n        call static void [reflect.core]reflect.io.Console::write(int,"
                + " reflect.core.String)\n        ret\n", ""), null, Map.of("reflect.core", core)));
    }

    @Test
    public static void aMemberThatDoesNotResolveIsATypeLoadError() throws Exception {
        String before = "        ldstr \"before\"\n" + PRINTLN;
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "before\n", TYPE_LOAD_ERROR + ": the module reflect.io defines no method"
                + " static void reflect.io.Console::missing()\n"), run(program(before
                + "        call static void reflect.io.Console::missing()\n        ret\n", "")));
        // A method of the name with another signature is another method (Q69).
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.io defines no method"
                + " static void reflect.io.Console::println(reflect.core.String)\n"), run(program("        ldstr \"x\"\n"
                + "        call static void reflect.io.Console::println(reflect.core.String)\n        ret\n", "")));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.io defines no type"
                + " reflect.io.Missing\n"), run(program("        call static void reflect.io.Missing::f()\n        ret\n",
                "")));
        String absent = ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires static x\n\n"
                + ".class public final m.App {\n"
                + "    .method public static void main(reflect.core.String[] args) {\n"
                + "        call static void x.T::f()\n        ret\n    }\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module x of x.T is absent, which m requires"
                + " static\n"), run(absent));
    }

    @Test
    public static void vm0StopsWhereItCannotRun() throws Exception {
        assertEquals(new Run(2, null, "", "vm0: the module m names no main class; name one with .main or --main\n"),
                run(".module m 0.0.0\n.class public m.App {\n}\n"));
        String classes = ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires reflect.io\n\n"
                + ".class public m.App {\n    .method public static void main(reflect.core.String[] args) {\n"
                + "        ldstr \"App\"\n" + PRINTLN + "        ret\n    }\n}\n"
                + ".class public m.Hidden {\n    .method static void main(reflect.core.String[] args) {\n        ret\n"
                + "    }\n}\n.class public m.Odd {\n    .method public static void main(int args) {\n        ret\n    }\n}\n"
                + ".class public m.Wide {\n    .method public static long main(reflect.core.String[] args) {\n"
                + "        ldc.i8 0\n        ret\n    }\n}\n.class public m.Both {\n"
                + "    .method public static void main(reflect.core.String[] args) {\n        ret\n    }\n"
                + "    .method public static int main(reflect.core.String[] args) {\n        ldc.i4 0\n        ret\n"
                + "    }\n}\n";
        assertEquals(new Run(0, null, "App\n", ""), run(classes, "m.App", Map.of()));
        assertEquals(new Run(2, null, "", "vm0: the module m defines no type m.Nope\n"), run(classes, "m.Nope", Map.of()));
        String none = " has no public static main(reflect.core.String[]) that returns void or int\n";
        assertEquals(new Run(2, null, "", "vm0: m.Hidden" + none), run(classes, "m.Hidden", Map.of()));
        assertEquals(new Run(2, null, "", "vm0: m.Odd" + none), run(classes, "m.Odd", Map.of()));
        assertEquals(new Run(2, null, "", "vm0: m.Wide" + none), run(classes, "m.Wide", Map.of()));
        assertEquals(new Run(2, null, "", "vm0: m.Both has two main methods, which return void and int\n"),
                run(classes, "m.Both", Map.of()));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 2: VM0 runs the instructions of R0 only, and"
                + " div.u32 is not one\n"), run(program("        ldc.i4 1\n        ldc.i4 2\n        div.u32\n"
                + "        ret\n", "")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 0: VM0 does not run ldtype yet\n"),
                run(program("        ldtype m.App\n        ret\n", "")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: VM0 does not run instance calls yet\n"),
                run(program("        ldstr \"x\"\n        call void m.App::f()\n        ret\n",
                "    .method void f() {\n        ret\n    }\n")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: the code ends without ret\n"),
                run(program("        ldstr \"x\"\n", "")));
        assertEquals(new Run(2, null, "", "vm0: VM0 does not run the static initializer of m.App yet\n"),
                run(program("        ret\n", "    .method static void static() {\n        ret\n    }\n")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: VM0 does not run locals of value types yet\n"),
                run(program("        .locals (valuetype m.E e)\n        ret\n", "")
                + ".class public enum m.E : int {\n}\n"));
        // Code that a verifier would reject: two integers where a call takes strings, and a stack deeper than
        // maxStack.
        Run cast = run(program("        ldc.i4 1\n        ldc.i4 2\n        call static reflect.core.String"
                + " reflect.core.String::concat(reflect.core.String, reflect.core.String)\n        ret\n", ""));
        assertEquals(2, cast.exitCode());
        assertTrue(cast.err().startsWith("vm0: m.App::main: instruction 2: the code is invalid:"
                + " java.lang.ClassCastException: "));
        Run deep = run(program("        .maxstack 0\n        ldstr \"x\"\n        ret\n", ""));
        assertEquals(2, deep.exitCode());
        assertTrue(deep.err().startsWith("vm0: m.App::main: instruction 0: the code is invalid:"
                + " java.lang.ArrayIndexOutOfBoundsException: "));
    }

    @Test
    public static void consoleWritesUtf8ToOneOfTwoDescriptors() throws Exception {
        String write = "        call static void reflect.io.Console::write(int, reflect.core.String)\n        ret\n";
        assertEquals(new Run(0, null, "", "e"), run(program("        ldc.i4 2\n        ldstr \"e\"\n" + write, "")));
        assertEquals(new Run(1, "reflect.io.IOException", "", "reflect.io.IOException: VM0 writes to file descriptors 1"
                + " and 2 only, not 3\n"), run(program("        ldc.i4 3\n        ldstr \"x\"\n" + write, "")));
        // A character outside ASCII, which the container's charset would change (sketchpad D.5), and an unpaired
        // surrogate, which UTF-8 cannot hold, so U+FFFD replaces it.
        assertEquals(new Run(0, null, "" + (char) 0xC4 + (char) 0xFFFD + "\n", ""),
                run(program("        ldstr \"\\u00C4\\uD800\"\n" + PRINTLN + "        ret\n", "")));
    }

    @Test
    public static void anIntegerDivisionByZeroIsAFault() throws Exception {
        // Until v0.0.8, each one ends the run with the fault, after the line before it (Q89).
        for (String division : List.of("ldc.i4 div.i32", "ldc.i4 rem.i32", "ldc.i8 div.i64", "ldc.i8 rem.i64")) {
            String load = division.split(" ")[0];
            String mnemonic = division.split(" ")[1];
            assertEquals(new Run(1, ARITHMETIC_EXCEPTION, mnemonic + "\n", ARITHMETIC_EXCEPTION + ": division by zero\n"),
                    run(program("        ldstr \"" + mnemonic + "\"\n" + PRINTLN + "        " + load + " 7\n        " + load
                    + " 0\n        " + mnemonic + "\n        pop\n        ret\n", "")));
        }
    }

    @Test
    public static void anOverflowOfTheStackIsAFault() throws Exception {
        // Until v0.0.8, an unbounded recursion ends the run with the fault of Q94, after the line before it.
        String down = "    .method static void down() {\n        call static void m.App::down()\n        ret\n    }\n";
        assertEquals(new Run(1, "reflect.core.StackOverflowError", "before\n", "reflect.core.StackOverflowError: the"
                + " calls fill the 256 MiB stack of VM0\n"), run(program("        ldstr \"before\"\n" + PRINTLN
                + "        call static void m.App::down()\n        ret\n", down)));
    }

    @Test
    public static void theRunCountsTheOpcodesThatItStarts() throws Exception {
        // A loop of two rounds, then a call: an opcode counts each time that it starts, in main and in f.
        String loop = "        .locals (int n)\n        ldc.i4 2\n        stloc n\n    LOOP:\n        ldloc n\n        brfalse END\n"
                + "        ldloc n\n        ldc.i4 1\n        sub.i32\n        stloc n\n        br LOOP\n    END:\n"
                + "        call static void m.App::f()\n        ret\n";
        assertEquals(Map.of(Opcode.LDC_I4, 3L, Opcode.STLOC, 3L, Opcode.LDLOC, 5L, Opcode.BRFALSE, 3L, Opcode.SUB_I32,
                2L, Opcode.BR, 2L, Opcode.CALL, 1L, Opcode.NOP, 1L, Opcode.RET, 2L), counts(program(loop,
                "    .method static void f() {\n        nop\n        ret\n    }\n")));
        // An instruction that stops VM0 counts too, and a two-byte opcode by its own number.
        assertEquals(Map.of(Opcode.LDC_I4, 2L, Opcode.ADD_OVF_I32, 1L), counts(program("        ldc.i4 1\n"
                + "        ldc.i4 2\n        add.ovf.i32\n        pop\n        ret\n", "")));
    }

    @Test
    public static void anErrorOfTheRunReachesTheCaller() throws Exception {
        // main runs on a thread of its own (Q93); an error that ends it, such as OutOfMemoryError, reaches the
        // caller of run unchanged.
        var error = new AssertionError("from the module source");
        byte[] module = assemble(program("        ret\n", ""));
        var out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(error, assertThrows(AssertionError.class, () -> Vm.run(new ModuleData("m.rflc", module),
                name -> {
                    throw error;
                }, null, List.of(), out, out)));
    }

    // A module m whose main class m.App has this main and these other members.
    private static String program(String main, String others) {
        return ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires reflect.io\n\n"
                + ".class public final m.App {\n    .method public static void main(reflect.core.String[] args) {\n"
                + main + "    }\n" + others + "}\n";
    }

    private static Run run(String main) throws Exception {
        return run(main, null, Map.of());
    }

    // Runs the module on VM0 with the stub modules of lib/, or the texts that replace them.
    private static Run run(String main, @Nullable String mainClass, Map<String, String> replaced) throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        Vm.Result result = execute(main, mainClass, replaced, out, err);
        return new Run(result.exitCode(), result.uncaught(), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    // The opcodes that a run of the module executes, by number, and how often.
    private static Map<Integer, Long> counts(String main) throws Exception {
        return execute(main, null, Map.of(), new ByteArrayOutputStream(), new ByteArrayOutputStream()).counts();
    }

    private static Vm.Result execute(String main, @Nullable String mainClass, Map<String, String> replaced,
            ByteArrayOutputStream out, ByteArrayOutputStream err) throws Exception {
        var modules = new HashMap<String, ModuleData>();
        for (String name : List.of("reflect.core", "reflect.io")) {
            String file = "tests/bytecode/lib/" + name + ".rfla";
            String text = replaced.containsKey(name) ? replaced.get(name) : Files.readString(Path.of(file));
            modules.put(name, new ModuleData(file, assemble(text)));
        }
        return Vm.run(new ModuleData("m.rflc", assemble(main)), modules::get, mainClass, List.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static byte[] assemble(String text) throws Exception {
        return Assembler.assemble(text.getBytes(StandardCharsets.UTF_8));
    }

    private record Run(int exitCode, @Nullable String uncaught, String out, String err) {
    }
}
