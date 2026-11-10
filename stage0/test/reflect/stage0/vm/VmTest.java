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
    private static final String ABSTRACT_MEMBER_ERROR = "reflect.core.AbstractMemberError";
    private static final String ARITHMETIC_EXCEPTION = "reflect.core.ArithmeticException";
    private static final String CLASS_CAST_EXCEPTION = "reflect.core.ClassCastException";
    private static final String NULL_POINTER_EXCEPTION = "reflect.core.NullPointerException";
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
        // A field of another module resolves by its name and its type (Q69), when the instruction links.
        String core = Files.readString(Path.of("tests/bytecode/lib/reflect.core.rfla")).replace(
                ".class public reflect.core.Object {\n", ".class public reflect.core.Object {\n    .field public int count\n");
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.core defines no field long"
                + " reflect.core.Object::count\n"), run(program("        ldstr \"x\"\n"
                + "        ldfld long reflect.core.Object::count\n        pop\n        ret\n", ""), null,
                Map.of("reflect.core", core)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.core defines no field int"
                + " reflect.core.Object::total\n"), run(program("        ldstr \"x\"\n"
                + "        ldfld int reflect.core.Object::total\n        pop\n        ret\n", ""), null,
                Map.of("reflect.core", core)));
        String absent = ".module m 0.0.0\n.main m.App\n.requires reflect.core\n.requires static x\n\n"
                + ".class public final m.App {\n"
                + "    .method public static void main(reflect.core.String[] args) {\n"
                + "        call static void x.T::f()\n        ret\n    }\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module x of x.T is absent, which m requires"
                + " static\n"), run(absent));
        // String.valueOf(Object?) calls Object::toString through the slot of the value's run-time type (Q103).
        String plain = Files.readString(Path.of("tests/bytecode/lib/reflect.core.rfla"))
                .replace("    .method public virtual native reflect.core.String toString() {\n    }\n", "")
                .replace("        .override reflect.core.String reflect.core.Object::toString()\n", "");
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.core defines no method"
                + " reflect.core.String reflect.core.Object::toString()\n"), run(program("        ldstr \"x\"\n" + PRINTLN
                + "        ret\n", ""), null, Map.of("reflect.core", plain)));
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
        assertEquals(new Run(2, null, "", "vm0: m.App::main: the code ends without ret\n"),
                run(program("        ldstr \"x\"\n", "")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: VM0 does not run locals of value types yet\n"),
                run(program("        .locals (valuetype m.E e)\n        ret\n", "")
                + ".class public enum m.E : int {\n}\n"));
        // The main class loads before main runs, and its layout needs the zero value of each field.
        assertEquals(new Run(2, null, "", "vm0: m.App::e: VM0 does not run fields of value types yet\n"),
                run(program("        ret\n", "    .field valuetype m.E e\n") + ".class public enum m.E : int {\n}\n"));
        assertEquals(new Run(2, null, "", "vm0: m.App::e: VM0 does not run fields of value types yet\n"),
                run(program("        ret\n", "    .field static valuetype m.E e\n") + ".class public enum m.E : int {\n}\n"));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 0: VM0 does not run value types yet, such as"
                + " m.S\n"), run(program("        newobj void m.S::new()\n        pop\n        ret\n", "")
                + ".class public struct m.S {\n    .method public void new() {\n        ret\n    }\n}\n"));
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

    @Test
    public static void aNullReceiverIsANullPointerException() throws Exception {
        // A local of a class type starts at null (BC-17). ldfld and stfld check their object (BC-14), and call
        // of an instance method its receiver (Q100). Until v0.0.8 each is a fault.
        String point = ".class public m.P {\n    .field public int x\n    .method public void f() {\n        ret\n    }\n}\n";
        String load = "        .locals (m.P p)\n        ldloc p\n";
        assertEquals(new Run(1, NULL_POINTER_EXCEPTION, "", NULL_POINTER_EXCEPTION + ": ldfld int m.P::x: the receiver is"
                + " null\n"), run(program(load + "        ldfld int m.P::x\n        pop\n        ret\n", "") + point));
        assertEquals(new Run(1, NULL_POINTER_EXCEPTION, "", NULL_POINTER_EXCEPTION + ": stfld int m.P::x: the receiver is"
                + " null\n"), run(program(load + "        ldc.i4 1\n        stfld int m.P::x\n        ret\n", "") + point));
        assertEquals(new Run(1, NULL_POINTER_EXCEPTION, "", NULL_POINTER_EXCEPTION + ": call void m.P::f(): the receiver"
                + " is null\n"), run(program(load + "        call void m.P::f()\n        ret\n", "") + point));
    }

    @Test
    public static void newobjNamesAConstructorOfAClass() throws Exception {
        // What newobj names is checked when it links (VM-16); a violation is a TypeLoadError (Q101).
        String types = ".class public abstract m.Shape {\n    .method public void new() {\n        ret\n    }\n}\n"
                + ".class public interface m.I {\n    .method public void new() {\n        ret\n    }\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": newobj names static void m.App::f(), which is"
                + " not a constructor\n"), run(program("        newobj static void m.App::f()\n        pop\n        ret\n",
                "    .method static void f() {\n        ret\n    }\n")));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": newobj names a constructor of the abstract class"
                + " m.Shape\n"), run(program("        newobj void m.Shape::new()\n        pop\n        ret\n", "") + types));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": newobj names a constructor of the interface"
                + " m.I\n"), run(program("        newobj void m.I::new()\n        pop\n        ret\n", "") + types));
    }

    @Test
    public static void ldfldAndStfldNameInstanceFields() throws Exception {
        // A static field there is a link error (Q101), found before the object.
        String count = "    .field static int count\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": ldfld names the static field int m.App::count\n"),
                run(program("        ldstr \"x\"\n        ldfld int m.App::count\n        pop\n        ret\n", count)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": stfld names the static field int m.App::count\n"),
                run(program("        ldstr \"x\"\n        ldc.i4 1\n        stfld int m.App::count\n        ret\n", count)));
    }

    @Test
    public static void ldsfldAndStsfldNameStaticFields() throws Exception {
        // An instance field there is a link error (Q101).
        String x = "    .field int x\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": ldsfld names the instance field int m.App::x\n"),
                run(program("        ldsfld int m.App::x\n        pop\n        ret\n", x)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": stsfld names the instance field int m.App::x\n"),
                run(program("        ldc.i4 1\n        stsfld int m.App::x\n        ret\n", x)));
    }

    @Test
    public static void ldconstNamesAConstFieldAndLdsfldAndStsfldTheOthers() throws Exception {
        // ldconst reads the Constant row of a const field, and ldsfld and stsfld the storage of a static field that
        // is not const; a violation is a link error (Q98, Q101).
        String fields = "    .field static const int K = 1\n    .field static int count\n    .field int x\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": ldconst names int m.App::count, which is not"
                + " const\n"), run(program("        ldconst int m.App::count\n        pop\n        ret\n", fields)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": ldconst names int m.App::x, which is not"
                + " const\n"), run(program("        ldconst int m.App::x\n        pop\n        ret\n", fields)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": ldsfld names the const field int m.App::K\n"),
                run(program("        ldsfld int m.App::K\n        pop\n        ret\n", fields)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": stsfld names the const field int m.App::K\n"),
                run(program("        ldc.i4 2\n        stsfld int m.App::K\n        ret\n", fields)));
    }

    @Test
    public static void aFaultOfAStaticInitializer() throws Exception {
        // Until v0.0.8, a fault that is an exception ends the initializer as ExceptionInInitializerError, with
        // the fault as its message; an error, and Runtime.exit, end it unchanged (Q102).
        String use = "        ldstr \"before\"\n" + PRINTLN + "        ldsfld int m.B::x\n        pop\n        ret\n";
        String eiie = "reflect.core.ExceptionInInitializerError";
        assertEquals(new Run(1, eiie, "before\n", eiie + ": " + ARITHMETIC_EXCEPTION + ": division by zero\n"),
                run(program(use, "") + initializer("        ldc.i4 1\n        ldc.i4 0\n        div.i32\n        pop\n")));
        assertEquals(new Run(1, eiie, "before\n", eiie + ": reflect.io.IOException: VM0 writes to file descriptors 1 and"
                + " 2 only, not 3\n"), run(program(use, "") + initializer("        ldc.i4 3\n        ldstr \"x\"\n"
                + "        call static void reflect.io.Console::write(int, reflect.core.String)\n")));
        assertEquals(new Run(1, eiie, "before\n", eiie + ": " + NULL_POINTER_EXCEPTION + ": ldfld int m.B::y: the receiver"
                + " is null\n"), run(program(use, "") + initializer("        .locals (m.B b)\n        ldloc b\n"
                + "        ldfld int m.B::y\n        pop\n")));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "before\n", TYPE_LOAD_ERROR + ": the module reflect.io defines no method"
                + " static void reflect.io.Console::missing()\n"), run(program(use, "")
                + initializer("        call static void reflect.io.Console::missing()\n")));
        assertEquals(new Run(1, "reflect.core.StackOverflowError", "before\n", "reflect.core.StackOverflowError: the calls"
                + " fill the 256 MiB stack of VM0\n"), run(program(use, "") + initializer("        call static void m.B::down()\n")
                .replace("}\n}\n", "}\n    .method static void down() {\n        call static void m.B::down()\n"
                + "        ret\n    }\n}\n")));
        assertEquals(new Run(5, null, "before\n", ""), run(program(use, "") + initializer("        ldc.i4 5\n"
                + "        call static void reflect.core.Runtime::exit(int)\n")));
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "before\n", ABSTRACT_MEMBER_ERROR + ": a call reaches the abstract"
                + " method static void m.B::f()\n"), run(program(use, "") + initializer("        call static void m.B::f()\n")
                .replace("}\n}\n", "}\n    .method static abstract void f() {\n    }\n}\n")));
        // The error of a failed initializer passes through the initializer that started it, unchanged.
        String chain = initializer("        ldsfld int m.C::z\n        pop\n") + ".class public m.C {\n"
                + "    .field static int z\n    .method static void static() {\n        ldc.i4 1\n        ldc.i4 0\n"
                + "        div.i32\n        pop\n        ret\n    }\n}\n";
        assertEquals(new Run(1, eiie, "before\n", eiie + ": " + ARITHMETIC_EXCEPTION + ": division by zero\n"),
                run(program(use, "") + chain));
    }

    // A class m.B with the static field x, the instance field y, and a static initializer with this code.
    private static String initializer(String code) {
        return ".class public m.B {\n    .field static int x\n    .field int y\n    .method static void static() {\n"
                + code + "        ret\n    }\n}\n";
    }

    @Test
    public static void aReceiverOfAnotherClassIsInvalidCode() throws Exception {
        // A verifier rejects these (BC-07), and VM0, which has none, stops when it meets them (Q101).
        String classes = ".class public m.A {\n    .field public int x\n    .method public void f() {\n        ret\n    }\n}\n"
                + ".class public m.B {\n    .method public void new() {\n        ldarg 0\n"
                + "        call void reflect.core.Object::new()\n        ret\n    }\n}\n";
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: the code is invalid: the receiver of ldfld int"
                + " m.A::x is not a m.A\n"), run(program("        newobj void m.B::new()\n        ldfld int m.A::x\n"
                + "        pop\n        ret\n", "") + classes));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: the code is invalid: the receiver of call void"
                + " m.A::f() is not a m.A\n"), run(program("        ldstr \"x\"\n        call void m.A::f()\n        ret\n", "")
                + classes));
    }

    @Test
    public static void aNumberWhereAReferenceStandsIsInvalidCode() throws Exception {
        // A verifier rejects these (BC-07), and VM0 stops when it meets them (Q101).
        String invalid = "vm0: m.App::main: instruction %d: the code is invalid: a number stands where a reference does\n";
        assertEquals(new Run(2, null, "", invalid.formatted(1)), run(program("        ldc.i4 0\n        brnull L\n"
                + "    L:\n        ret\n", "")));
        assertEquals(new Run(2, null, "", invalid.formatted(1)), run(program("        ldc.i4 0\n        brnonnull L\n"
                + "    L:\n        ret\n", "")));
        assertEquals(new Run(2, null, "", invalid.formatted(2)), run(program("        ldnull\n        ldc.i8 0\n"
                + "        ceq.ref\n        pop\n        ret\n", "")));
        assertEquals(new Run(2, null, "", invalid.formatted(2)), run(program("        ldc.r4 0\n        ldnull\n"
                + "        ceq.ref\n        pop\n        ret\n", "")));
        assertEquals(new Run(2, null, "", invalid.formatted(1)), run(program("        ldc.i4 0\n        instanceof m.App\n"
                + "        pop\n        ret\n", "")));
        assertEquals(new Run(2, null, "", invalid.formatted(1)), run(program("        ldc.i4 0\n        castclass m.App\n"
                + "        pop\n        ret\n", "")));
    }

    @Test
    public static void aFailedCastNamesBothTypes() throws Exception {
        // castclass throws NullPointerException for null and a non-null type, and ClassCastException for a
        // reference of another type (NUL-05, BC-14); until v0.0.8 each is a fault.
        String classes = type("m.A", "") + type("m.B", "");
        assertEquals(new Run(1, NULL_POINTER_EXCEPTION, "", NULL_POINTER_EXCEPTION + ": null cannot be cast to m.A\n"),
                run(program("        ldnull\n        castclass m.A\n        pop\n        ret\n", "") + classes));
        assertEquals(new Run(1, CLASS_CAST_EXCEPTION, "", CLASS_CAST_EXCEPTION + ": m.B cannot be cast to m.A?\n"),
                run(program("        newobj void m.B::new()\n        castclass m.A?\n        pop\n        ret\n", "")
                + classes));
        assertEquals(new Run(1, CLASS_CAST_EXCEPTION, "", CLASS_CAST_EXCEPTION + ": reflect.core.String cannot be cast to"
                + " m.A\n"), run(program("        ldstr \"x\"\n        castclass m.A\n        pop\n        ret\n", "")
                + classes));
    }

    @Test
    public static void aTypeTestLinksItsTypeButInitializesNoClass() throws Exception {
        // A type test is no active use (VM-07), so the initializer of m.B does not run; the type that it names links
        // at its first execution (VM-16).
        String b = ".class public m.B {\n    .method static void static() {\n        ldstr \"init\"\n" + PRINTLN
                + "        ret\n    }\n}\n";
        assertEquals(new Run(0, null, "0\n", ""), run(program("        ldnull\n        castclass m.B?\n"
                + "        instanceof m.B\n        call static reflect.core.String reflect.core.String::valueOf(int)\n"
                + PRINTLN + "        ret\n", "") + b));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.core defines no type"
                + " reflect.core.Missing\n"), run(program("        ldnull\n        instanceof reflect.core.Missing\n"
                + "        pop\n        ret\n", "")));
    }

    @Test
    public static void typeTestsOfOtherTypesWaitForTheirStages() throws Exception {
        // VM0 tests classes and interfaces and their nullable forms; the other types arrive in later stages (Q99).
        assertEquals(new Run(2, null, "", "vm0: VM0 does not run the type reflect.core.Int32[] yet\n"),
                run(program("        ldnull\n        castclass int[]\n        pop\n        ret\n", "")));
        assertEquals(new Run(2, null, "", "vm0: VM0 does not run the type reflect.core.String[]? yet\n"),
                run(program("        ldnull\n        instanceof reflect.core.String[]?\n        pop\n        ret\n", "")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: VM0 does not run value types yet, such as"
                + " m.P\n"), run(program("        ldnull\n        instanceof m.P\n        pop\n        ret\n", "")
                + ".class public struct m.P {\n}\n"));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: VM0 does not run arrays yet\n"),
                run(program("        ldarg 0\n        instanceof reflect.core.Object\n        pop\n        ret\n", "")));
    }

    @Test
    public static void callvirtNamesAnInstanceMethodOfAClass() throws Exception {
        // What callvirt names is checked when it links (VM-16, Q101), and its receiver when it runs (BC-14).
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": callvirt names static void m.App::f(), which is"
                + " static\n"), run(program("        callvirt static void m.App::f()\n        ret\n",
                "    .method static void f() {\n        ret\n    }\n")));
        assertEquals(new Run(1, NULL_POINTER_EXCEPTION, "", NULL_POINTER_EXCEPTION + ": callvirt reflect.core.String"
                + " reflect.core.Object::toString(): the receiver is null\n"), run(program("        .locals (m.App app)\n"
                + "        ldloc app\n        callvirt reflect.core.String reflect.core.Object::toString()\n        pop\n"
                + "        ret\n", "")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: the code is invalid: the receiver of callvirt"
                + " void m.App::g() is not a m.App\n"), run(program("        ldstr \"x\"\n        callvirt void m.App::g()\n"
                + "        ret\n", "    .method public virtual void g() {\n        ret\n    }\n")));
        assertEquals(new Run(2, null, "", "vm0: m.App::main: instruction 1: the code is invalid: the receiver of callvirt"
                + " void m.I::f() is not a m.I\n"), run(program("        ldstr \"x\"\n        callvirt void m.I::f()\n"
                + "        ret\n", "") + ".class public interface m.I {\n    .method public abstract void f() {\n    }\n}\n"));
    }

    @Test
    public static void anInterfaceMethodWithoutOneImplementationIsAnError() throws Exception {
        // I::f has a default, which A and B override with defaults of their own; J makes it abstract again (Q97).
        String types = ".class public interface m.I {\n    .method public virtual void f() {\n        ret\n    }\n}\n"
                + ".class public interface m.A implements m.I {\n    .method public virtual void f() {\n"
                + "        .override void m.I::f()\n        ret\n    }\n}\n"
                + ".class public interface m.B implements m.I {\n    .method public virtual void f() {\n"
                + "        .override void m.I::f()\n        ret\n    }\n}\n"
                + ".class public interface m.J implements m.I {\n    .method public abstract void f() {\n"
                + "        .override void m.I::f()\n    }\n}\n"
                + ".class public interface m.K {\n    .method public abstract void g() {\n    }\n}\n";
        String call = "        callvirt void m.I::f()\n        ret\n";
        // Two most specific defaults conflict, a TypeLoadError at the call (Q101), unless a class member wins (MEM-03).
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the defaults void m.A::f() and void m.B::f() of"
                + " void m.I::f() conflict in m.C\n"), run(program("        newobj void m.C::new()\n" + call, "") + types
                + type("m.C implements m.A, m.B", "")));
        assertEquals(new Run(0, null, "C\n", ""), run(program("        newobj void m.C::new()\n" + call, "") + types
                + type("m.C implements m.A, m.B", "    .method public virtual void f() {\n        .override void m.I::f()\n"
                + "        ldstr \"C\"\n" + PRINTLN + "        ret\n    }\n")));
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "", ABSTRACT_MEMBER_ERROR + ": m.C does not implement void"
                + " m.J::f()\n"), run(program("        newobj void m.C::new()\n" + call, "") + types
                + type("m.C implements m.J", "")));
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "", ABSTRACT_MEMBER_ERROR + ": m.C does not implement void"
                + " m.K::g()\n"), run(program("        newobj void m.C::new()\n        callvirt void m.K::g()\n        ret\n",
                "") + types + type("m.C implements m.K", "")));
    }

    // A public class with a constructor and these other members.
    private static String type(String head, String members) {
        return ".class public " + head + " {\n    .method public void new() {\n        ldarg 0\n"
                + "        call void reflect.core.Object::new()\n        ret\n    }\n" + members + "}\n";
    }

    @Test
    public static void anInterfaceCallTakesTheSlotOfTheClassMethodThatImplementsIt() throws Exception {
        // K::m implements I::m and fills the slot of B::m. S::m names B::m, not K::m, and fills the same slot, so
        // a call of I::m on an S runs S::m, as a call of K::m does (Q97).
        String print = "        ldstr \"%s\"\n" + PRINTLN + "        ret\n    }\n";
        String types = ".class public interface m.I {\n    .method public abstract void m() {\n    }\n}\n"
                + type("m.B", "    .method public virtual void m() {\n" + print.formatted("B"))
                + type("m.K extends m.B implements m.I", "    .method public virtual void m() {\n"
                + "        .override void m.B::m()\n        .override void m.I::m()\n" + print.formatted("K"))
                + type("m.S extends m.K", "    .method public virtual void m() {\n        .override void m.B::m()\n"
                + print.formatted("S"));
        assertEquals(new Run(0, null, "S\nS\n", ""), run(program("        newobj void m.S::new()\n"
                + "        callvirt void m.I::m()\n        newobj void m.S::new()\n        callvirt void m.K::m()\n"
                + "        ret\n", "") + types));
    }

    @Test
    public static void aCallThatReachesAnAbstractMethodIsAnAbstractMemberError() throws Exception {
        // A slot that stays abstract holds a stub that throws (VM-04), and call of an abstract method throws too
        // (Q101). Until v0.0.8 each is a fault.
        String shapes = ".class public abstract m.Shape {\n    .method public void new() {\n        ldarg 0\n"
                + "        call void reflect.core.Object::new()\n        ret\n    }\n"
                + "    .method public virtual abstract int area() {\n    }\n"
                + "    .method public virtual abstract reflect.core.String toString() {\n"
                + "        .override reflect.core.String reflect.core.Object::toString()\n    }\n}\n"
                + ".class public m.Blob extends m.Shape {\n    .method public void new() {\n        ldarg 0\n"
                + "        call void m.Shape::new()\n        ret\n    }\n}\n";
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "", ABSTRACT_MEMBER_ERROR + ": m.Blob does not implement int"
                + " m.Shape::area()\n"), run(program("        newobj void m.Blob::new()\n        callvirt int m.Shape::area()\n"
                + "        pop\n        ret\n", "") + shapes));
        // String.valueOf, which println calls, takes toString() from the slot of the object's run-time type (Q103).
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "", ABSTRACT_MEMBER_ERROR + ": m.Blob does not implement"
                + " reflect.core.String m.Shape::toString()\n"), run(program("        newobj void m.Blob::new()\n" + PRINTLN
                + "        ret\n", "") + shapes));
        assertEquals(new Run(1, ABSTRACT_MEMBER_ERROR, "", ABSTRACT_MEMBER_ERROR + ": a call reaches the abstract method"
                + " int m.Shape::area()\n"), run(program("        newobj void m.Blob::new()\n        call int m.Shape::area()\n"
                + "        pop\n        ret\n", "") + shapes));
    }

    @Test
    public static void loadingChecksTheSubtypesOfASealedType() throws Exception {
        // A direct supertype that is sealed belongs to the module of the type, which is final, sealed or unsealed;
        // unsealed stands only on a type with a sealed direct supertype (DAT-06, Q95). A violation is a
        // TypeLoadError when the type loads, here when the instanceof that names it links (VM-13).
        String load = "        ldnull\n        instanceof m.B\n        pop\n        ret\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B has the sealed supertype m.A, so it is final,"
                + " sealed or unsealed (DAT-06)\n"), run(program(load, "") + ".class public sealed m.A {\n}\n"
                + ".class public m.B extends m.A {\n}\n"));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B has the sealed supertype m.I, so it is final,"
                + " sealed or unsealed (DAT-06)\n"), run(program(load, "") + ".class public sealed interface m.I {\n}\n"
                + ".class public interface m.B implements m.I {\n}\n"));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B is unsealed, but no direct supertype of it is"
                + " sealed (Q95)\n"), run(program(load, "") + ".class public unsealed m.B {\n}\n"));
        // The sealed type of reflect.core permits only the types of reflect.core.
        String core = Files.readString(Path.of("tests/bytecode/lib/reflect.core.rfla"))
                + "\n.class public sealed reflect.core.Sealed {\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B has the sealed supertype reflect.core.Sealed"
                + " of the module reflect.core, which permits only its own types (DAT-06)\n"), run(program(load, "")
                + ".class public final m.B extends reflect.core.Sealed {\n}\n", null, Map.of("reflect.core", core)));
    }

    @Test
    public static void loadingChecksTheSupertypesThatAnotherModuleDefines() throws Exception {
        // The base of a class is a class that is not final and not a record, and an InterfaceImpl row names an
        // interface; the reader checks those that the module defines, and the load those of another module (Q96).
        String load = "        ldnull\n        instanceof m.B\n        pop\n        ret\n";
        String lib = Files.readString(Path.of("tests/bytecode/lib/reflect.core.rfla"));
        Map<String, String> core = Map.of("reflect.core", lib + "\n.class public record reflect.core.Pair {\n}\n"
                + ".class public interface reflect.core.Face {\n}\n");
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B extends reflect.core.String, which is final"
                + " (Q96)\n"), run(program(load, "") + ".class public m.B extends reflect.core.String {\n}\n"));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B extends reflect.core.Pair, which is a record"
                + " (Q96)\n"), run(program(load, "") + ".class public m.B extends reflect.core.Pair {\n}\n", null, core));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B extends reflect.core.Face, which is not a"
                + " class (Q96)\n"), run(program(load, "") + ".class public m.B extends reflect.core.Face {\n}\n", null, core));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.B implements reflect.core.Object, which is not"
                + " an interface (Q96)\n"), run(program(load, "") + ".class public m.B implements reflect.core.Object {\n}\n"));
        // A class with base 0 extends reflect.core.Object, which the load checks too, before main runs.
        String finalObject = lib.replace(".class public reflect.core.Object {", ".class public final reflect.core.Object {")
                .replace(" extends reflect.core.Object {", " {");
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.App extends reflect.core.Object, which is final"
                + " (Q96)\n"), run(program("        ret\n", ""), null, Map.of("reflect.core", finalObject)));
    }

    @Test
    public static void loadingChecksTheOverrideRows() throws Exception {
        // A row names an existing method of a supertype that has a slot, is not final and takes the parameter
        // types of the override; a class method fills one slot of its base classes; and the override's return
        // type converts, without a bridge, to that of the method that the slot held, or of the interface method
        // (BC-12, MEM-02, Q97). A violation is a TypeLoadError when the type loads.
        String load = "        ldnull\n        instanceof m.B\n        pop\n        ret\n";
        String a = type("m.A", "    .method public virtual void f() {\n        ret\n    }\n"
                + "    .method public virtual void e() {\n        ret\n    }\n"
                + "    .method public virtual final void g() {\n        ret\n    }\n"
                + "    .method public void h() {\n        ret\n    }\n"
                + "    .method public virtual void p(int x) {\n        ret\n    }\n"
                + "    .method public virtual m.A make() {\n        ldnull\n        ret\n    }\n"
                + "    .method public virtual reflect.core.Object any() {\n        ldnull\n        ret\n    }\n");
        String body = "        ret\n    }\n";
        String none = "        ldnull\n" + body;
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": the module reflect.core defines no method void"
                + " reflect.core.Object::gone()\n"), run(program(load, "") + a + overriding("void f()",
                "void reflect.core.Object::gone()", body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": void m.B::f() overrides void m.C::f(), but m.C is"
                + " no supertype of m.B (BC-12)\n"), run(program(load, "") + a + type("m.C", "    .method public virtual"
                + " void f() {\n" + body) + overriding("void f()", "void m.C::f()", body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": void m.B::h() overrides void m.A::h(), which is"
                + " not a virtual or abstract instance method (BC-12)\n"), run(program(load, "") + a
                + overriding("void h()", "void m.A::h()", body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": void m.B::g() overrides void m.A::g(), which is"
                + " final (MEM-02)\n"), run(program(load, "") + a + overriding("void g()", "void m.A::g()", body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": void m.B::p(long) overrides void m.A::p(int),"
                + " whose parameter types differ (MEM-02)\n"), run(program(load, "") + a + overriding("void p(long x)",
                "void m.A::p(int)", body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": void m.B::f() fills a second slot, that of void"
                + " m.A::e() (Q97)\n"), run(program(load, "") + a + overriding("void f()", "void m.A::f()\n"
                + "        .override void m.A::e()", body)));
        // An override never widens its return type, its nullability included, and a value that needs a box needs
        // a bridge too.
        String widens = "%s: the return type of %s does not convert to that of %s (BC-12)\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "reflect.core.Object m.B::make()",
                "m.A m.A::make()")), run(program(load, "") + a + overriding("reflect.core.Object make()",
                "m.A m.A::make()", none)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "m.A? m.B::make()",
                "m.A m.A::make()")), run(program(load, "") + a + overriding("m.A? make()", "m.A m.A::make()", none)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "reflect.core.Object? m.B::any()",
                "reflect.core.Object m.A::any()")), run(program(load, "") + a + overriding("reflect.core.Object? any()",
                "reflect.core.Object m.A::any()", none)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "int m.B::any()",
                "reflect.core.Object m.A::any()")), run(program(load, "") + a + overriding("int any()",
                "reflect.core.Object m.A::any()", "        ldc.i4 0\n" + body)));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "valuetype m.S m.B::any()",
                "reflect.core.Object m.A::any()")), run(program(load, "") + a + ".class public struct m.S {\n}\n"
                + overriding("m.S any()", "reflect.core.Object m.A::any()", none)));
        // An instantiation is a reference type too; the run never loads reflect.core.Box, which stands for one.
        assertEquals(new Run(0, null, "", ""), run(program(load, "") + a + overriding("reflect.core.Box<int> any()",
                "reflect.core.Object m.A::any()", none)));
        // The method that the slot holds is the nearest one in the base classes, so m.C, which names A::any, cannot
        // widen what m.B narrowed.
        String c = type("m.C extends m.B", "    .method public virtual reflect.core.Object any() {\n"
                + "        .override reflect.core.Object m.A::any()\n" + none);
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "reflect.core.Object m.C::any()",
                "reflect.core.String m.B::any()")), run(program(load.replace("m.B", "m.C"), "") + a
                + overriding("reflect.core.String any()", "reflect.core.Object m.A::any()", none) + c));
        String face = ".class public interface m.I {\n    .method public abstract m.I self() {\n    }\n}\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", widens.formatted(TYPE_LOAD_ERROR, "reflect.core.Object m.B::self()",
                "m.I m.I::self()")), run(program(load, "") + face + type("m.B implements m.I", "    .method public virtual"
                + " reflect.core.Object self() {\n        .override m.I m.I::self()\n" + none)));
    }

    // The class m.B, which extends m.A, with a public virtual method of this head that overrides the method
    // that the MethodRef names, and this body.
    private static String overriding(String head, String overridden, String body) {
        return type("m.B extends m.A", "    .method public virtual " + head + " {\n        .override " + overridden + "\n"
                + body);
    }

    @Test
    public static void finalFieldsTakeStoresOnlyFromTheirInitializers() throws Exception {
        // Only a constructor of the declaring type stores to a final instance field, on its own this, and only
        // its static initializer to a static final; a class constructor runs on a new object, or on its own this
        // from a constructor of the class or of a subclass. VM0 checks the method when the instruction links and
        // the object when it runs; a violation is a TypeLoadError (BC-23).
        String a = type("m.A", "    .field public final int x\n    .field public static final int s\n"
                + "    .method public void set() {\n        ldarg 0\n        ldc.i4 1\n        stfld int m.A::x\n"
                + "        ret\n    }\n    .method public void new(m.A other) {\n        ldarg 0\n"
                + "        call void reflect.core.Object::new()\n        ldarg other\n        ldc.i4 1\n"
                + "        stfld int m.A::x\n        ret\n    }\n    .method public void again(m.A other) {\n"
                + "        ldarg other\n        call void m.A::new()\n        ret\n    }\n"
                + "    .method public void new(int n) {\n        ldarg 0\n        newobj void m.A::new()\n"
                + "        call void m.A::new()\n        ret\n    }\n"
                + "    .method public void new(long n) {\n        ldarg 0\n        call void reflect.core.Object::new()\n"
                + "        ldc.i4 1\n        stsfld int m.A::s\n        ret\n    }\n");
        String b = type("m.B extends m.A", "    .method public void new(int n) {\n        ldarg 0\n"
                + "        call void m.A::new()\n        ldarg 0\n        ldc.i4 1\n        stfld int m.A::x\n        ret\n"
                + "    }\n");
        String outside = TYPE_LOAD_ERROR + ": %s stores to a final field outside the %s of m.A (BC-23)\n";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", outside.formatted("stfld int m.A::x", "constructors")),
                run(program("        newobj void m.A::new()\n        callvirt void m.A::set()\n        ret\n", "") + a));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", outside.formatted("stfld int m.A::x", "constructors")),
                run(program("        ldc.i4 0\n        newobj void m.B::new(int)\n        pop\n        ret\n", "") + a + b));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", outside.formatted("stsfld int m.A::s", "static initializer")),
                run(program("        ldc.i4 1\n        stsfld int m.A::s\n        ret\n", "") + a));
        // A constructor is no static initializer.
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", outside.formatted("stsfld int m.A::s", "static initializer")),
                run(program("        ldc.i8 0\n        newobj void m.A::new(long)\n        pop\n        ret\n", "") + a));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": stfld int m.A::x stores to a final field of an"
                + " object other than the constructor's this (BC-23)\n"), run(program("        newobj void m.A::new()\n"
                + "        newobj void m.A::new(m.A)\n        pop\n        ret\n", "") + a));
        // A constructor never runs again on a finished object.
        String runs = TYPE_LOAD_ERROR + ": %s void m.A::new() runs a constructor ";
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", runs.formatted("call") + "outside the constructors of m.A and its"
                + " subclasses (BC-23)\n"), run(program("        newobj void m.A::new()\n        call void m.A::new()\n"
                + "        ret\n", "") + a));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", runs.formatted("callvirt") + "outside the constructors of m.A and its"
                + " subclasses (BC-23)\n"), run(program("        newobj void m.A::new()\n        callvirt void m.A::new()\n"
                + "        ret\n", "") + a));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", runs.formatted("call") + "outside the constructors of m.A and its"
                + " subclasses (BC-23)\n"), run(program("        newobj void m.A::new()\n        dup\n"
                + "        callvirt void m.A::again(m.A)\n        ret\n", "") + a));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", runs.formatted("call") + "on an object other than the constructor's"
                + " this (BC-23)\n"), run(program("        ldc.i4 0\n        newobj void m.A::new(int)\n        pop\n"
                + "        ret\n", "") + a));
        // A constructor of an unrelated class is no constructor of the class or of a base.
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": call void m.A::new() runs a constructor outside"
                + " the constructors of m.A and its subclasses (BC-23)\n"), run(program("        newobj void m.C::new()\n"
                + "        pop\n        ret\n", "") + a + ".class public m.C {\n    .method public void new() {\n"
                + "        ldarg 0\n        call void m.A::new()\n        ret\n    }\n}\n"));
    }

    @Test
    public static void aClassWithoutABaseNeedsReflectCoreObject() throws Exception {
        // A class with base 0 extends reflect.core.Object (Q96), which loads before it; here no module holds it.
        String main = ".module m 0.0.0\n.main m.App\n.requires static reflect.core\n\n.class public final m.App {\n"
                + "    .method public static void main(reflect.core.String[] args) {\n        ret\n    }\n}\n";
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        Vm.Result result = Vm.run(new ModuleData("m.rflc", assemble(main)), name -> null, null, List.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(new Run(1, TYPE_LOAD_ERROR, "", TYPE_LOAD_ERROR + ": m.App needs reflect.core.Object, which the module"
                + " graph does not hold\n"), new Run(result.exitCode(), result.uncaught(), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)));
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
