package reflect.stage0.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.AssemblyException;
import reflect.stage0.bytecode.Diagnostic;
import reflect.stage0.bytecode.Disassembler;
import reflect.stage0.bytecode.ModuleFormatException;
import reflect.stage0.bytecode.ModuleReader;
import reflect.stage0.vm.ModuleData;
import reflect.stage0.vm.ModuleSource;
import reflect.stage0.vm.Vm;

/**
 * The command test (README §6.2): checks the .rfla files under a directory, in path order. A file in
 * invalid/ must fail with the one expect-error of its leading comments (§6.3); any other file must hold
 * none, assemble into a module that the reader accepts, and round-trip (§7.5). A file in run/ then runs
 * on VM0, with the modules of lib/ as its module path, and must meet its expect-output and expect-exit
 * (Q90).
 */
final class Suite {
    private static final String EXPECT_ERROR = "// expect-error:";
    private static final String EXPECT_EXIT = "// expect-exit:";
    private static final String EXPECT_OUTPUT = "// expect-output:";

    /** Where a file sits under the directory, which decides what it must do. */
    enum Place {
        INVALID, RUN, OTHER
    }

    /** Turns .rfla text into a module: rfla's assembler, or in a test one that writes a defect. */
    interface Assembly {
        byte[] assemble(byte[] text) throws AssemblyException;
    }

    /** Turns a module into its canonical text: rfld's disassembler, or in a test one that prints a defect. */
    interface Disassembly {
        byte[] disassemble(byte[] module) throws ModuleFormatException;
    }

    private Suite() {
    }

    /** The exit code: 0 when every file passes, 1 when one fails, 2 for wrong arguments, unreadable or no files. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("Usage: stage0 test <directory>");
            return 2;
        }
        Path root = Path.of(args[0]);
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(path -> path.toString().endsWith(".rfla")).sorted().toList();
            if (files.isEmpty()) {
                err.println("test: no .rfla files in " + root);
                return 2;
            }
            ModuleSource lib = lib(root, files);
            int failures = 0;
            for (Path file : files) {
                Path relative = root.relativize(file);
                Place place = relative.startsWith("invalid") ? Place.INVALID : relative.startsWith("run") ? Place.RUN
                        : Place.OTHER;
                String failure = check(place, file.toString(), Files.readAllBytes(file), Assembler::assemble,
                        Disassembler::disassemble, lib);
                if (failure == null) {
                    out.println("PASS " + file);
                } else {
                    failures++;
                    out.println("FAIL " + file + ": " + failure);
                }
            }
            out.println(files.size() + " files, " + failures + " failures");
            return failures == 0 ? 0 : 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("test: cannot read " + root + ": " + e);
            return 2;
        }
    }

    // The modules of lib/ for the programs of run/: the module of requires m is lib/m.rfla, as the file
    // m.rflc is in a directory of a module path (Q88). A file that does not assemble fails in its own check.
    private static ModuleSource lib(Path root, List<Path> files) throws IOException {
        var modules = new HashMap<String, ModuleData>();
        for (Path file : files) {
            Path relative = root.relativize(file);
            if (relative.getNameCount() == 2 && relative.startsWith("lib")) {
                String name = file.getFileName().toString();
                try {
                    modules.put(name.substring(0, name.length() - ".rfla".length()),
                            new ModuleData(file.toString(), Assembler.assemble(Files.readAllBytes(file))));
                } catch (AssemblyException e) {
                    // Reported with the file.
                }
            }
        }
        return modules::get;
    }

    // The failure of one file, or null when it passes. The file names the module in messages of VM0.
    static String check(Place place, String file, byte[] text, Assembly assembly, Disassembly disassembly,
            ModuleSource lib) {
        Directives directives = directives(text);
        List<String> errors = directives.errors();
        if (errors.size() != (place == Place.INVALID ? 1 : 0)) {
            return place == Place.INVALID ? "a file in invalid/ holds one expect-error, not " + errors.size()
                    : "only a file in invalid/ holds an expect-error";
        }
        List<String> outputs = directives.outputs();
        if (place != Place.RUN && !outputs.isEmpty()) {
            return "only a file in run/ holds an expect-output";
        }
        List<String> exits = directives.exits();
        if (place != Place.RUN && !exits.isEmpty()) {
            return "only a file in run/ holds an expect-exit";
        }
        if (exits.size() > 1) {
            return "a file in run/ holds at most one expect-exit, not " + exits.size();
        }
        int exitCode = exits.isEmpty() ? 0 : exitCode(exits.get(0));
        if (exitCode < 0) {
            return "expect-exit takes a number from 0 to 255, not \"" + exits.get(0) + "\"";
        }
        byte[] module;
        try {
            module = assembly.assemble(text);
        } catch (AssemblyException e) {
            if (place != Place.INVALID) {
                return e.getMessage();
            }
            String found = Diagnostic.name(e.code()) + " @ " + e.line() + ":" + e.column();
            return found.equals(errors.get(0)) ? null : "expected " + errors.get(0) + ", found " + e.getMessage();
        }
        if (place == Place.INVALID) {
            return "expected " + errors.get(0) + ", but the file assembles";
        }
        try {
            ModuleReader.read(module);
        } catch (ModuleFormatException e) {
            return "the reader rejects the module: " + e.getMessage();
        }
        String failure = roundTrip(module, assembly, disassembly);
        return failure != null || place != Place.RUN ? failure : execute(file, module, outputs, exitCode, lib);
    }

    // The round trip of BC-08 (README §7.5): module A gives text T, T assembles to module B, and B gives
    // text T2. It holds when A equals B and T equals T2; the failure, or null when it holds.
    private static String roundTrip(byte[] a, Assembly assembly, Disassembly disassembly) {
        try {
            byte[] t = disassembly.disassemble(a);
            byte[] b = assembly.assemble(t);
            int offset = Arrays.mismatch(a, b);
            if (offset >= 0) {
                return String.format(Locale.ROOT, "the canonical text assembles to other bytes, from offset 0x%X", offset);
            }
            int at = Arrays.mismatch(t, disassembly.disassemble(b));
            return at < 0 ? null : "the canonical text changes in the round trip, from line " + line(t, at);
        } catch (ModuleFormatException e) {
            return "the module has no canonical text: " + e.getMessage();
        } catch (AssemblyException e) {
            return "the canonical text does not assemble: " + e.getMessage();
        }
    }

    // A program of run/ on a new VM0 in this JVM (Q90): the low 8 bits of its exit code, as Linux gives them,
    // equal the expected code, and its standard output equals the expect-output lines, each followed by LF,
    // byte for byte. Standard error is not compared, but a failure shows its first line.
    private static String execute(String file, byte[] module, List<String> outputs, int expectedExit,
            ModuleSource lib) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        Vm.Result result = Vm.run(new ModuleData(file, module), lib, null, List.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String error = err.toString(StandardCharsets.UTF_8);
        int exitCode = result.exitCode() & 0xFF;
        if (exitCode != expectedExit) {
            return "the exit code is " + exitCode + ", expected " + expectedExit + (error.isEmpty() ? "" : ": "
                    + error.split("\n", 2)[0]);
        }
        var expected = new StringBuilder();
        for (String output : outputs) {
            expected.append(output).append('\n');
        }
        if (Arrays.equals(expected.toString().getBytes(StandardCharsets.UTF_8), out.toByteArray())) {
            return null;
        }
        return difference(outputs, out.toString(StandardCharsets.UTF_8));
    }

    // The first line where the output and the expect-output lines differ.
    private static String difference(List<String> outputs, String output) {
        String[] pieces = output.split("\n", -1);
        // Each piece but the last ends with LF; the last is the text after the last LF.
        int lines = pieces.length - 1;
        String rest = pieces[lines];
        int i = 0;
        while (i < lines && i < outputs.size() && pieces[i].equals(outputs.get(i))) {
            i++;
        }
        String expected = i < outputs.size() ? "\"" + outputs.get(i) + "\"" : "no more output";
        if (i < lines) {
            return "output line " + (i + 1) + " is \"" + pieces[i] + "\", expected " + expected;
        }
        if (!rest.isEmpty()) {
            return "output line " + (i + 1) + " is \"" + rest + "\" without a line end, expected " + expected;
        }
        return "output line " + (i + 1) + " is missing, expected " + expected;
    }

    // The line of the byte at the index, counted from 1.
    private static int line(byte[] text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text[i] == '\n') {
                line++;
            }
        }
        return line;
    }

    // The values of the directives of TST-01 among the leading // lines of the file. The value of
    // expect-output and expect-exit is the text after its colon and one space; that of expect-error has no
    // outer spaces.
    private static Directives directives(byte[] text) {
        var directives = new Directives(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (String line : new String(text, StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("//")) {
                break;
            }
            if (line.startsWith(EXPECT_ERROR)) {
                directives.errors().add(line.substring(EXPECT_ERROR.length()).strip());
            } else if (line.startsWith(EXPECT_OUTPUT)) {
                directives.outputs().add(value(line, EXPECT_OUTPUT));
            } else if (line.startsWith(EXPECT_EXIT)) {
                directives.exits().add(value(line, EXPECT_EXIT));
            }
        }
        return directives;
    }

    private static String value(String line, String directive) {
        String value = line.substring(directive.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    // The exit code that an expect-exit gives: a number from 0 to 255, the codes that Linux gives, or -1.
    private static int exitCode(String value) {
        return value.matches("[0-9]{1,3}") && Integer.parseInt(value) <= 255 ? Integer.parseInt(value) : -1;
    }

    private record Directives(List<String> errors, List<String> outputs, List<String> exits) {
    }
}
