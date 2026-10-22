package reflect.stage0.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.AssemblyException;
import reflect.stage0.bytecode.Diagnostic;
import reflect.stage0.bytecode.ModuleFormatException;
import reflect.stage0.bytecode.ModuleReader;

/**
 * The command test (README §6.2): checks the .rfla files under a directory, in path order. A file in
 * invalid/ must fail with the one expect-error of its leading comments (§6.3); any other file must hold
 * none and assemble into a module that the reader accepts.
 */
final class Suite {
    private static final String EXPECT_ERROR = "// expect-error:";

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
            int failures = 0;
            for (Path file : files) {
                String failure = check(root.relativize(file).startsWith("invalid"), Files.readAllBytes(file));
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

    // The failure of one file, or null when it passes.
    private static String check(boolean invalid, byte[] text) {
        List<String> expected = expectedErrors(text);
        if (expected.size() != (invalid ? 1 : 0)) {
            return invalid ? "a file in invalid/ holds one expect-error, not " + expected.size()
                    : "only a file in invalid/ holds an expect-error";
        }
        byte[] module;
        try {
            module = Assembler.assemble(text);
        } catch (AssemblyException e) {
            if (!invalid) {
                return e.getMessage();
            }
            String found = Diagnostic.name(e.code()) + " @ " + e.line() + ":" + e.column();
            return found.equals(expected.get(0)) ? null : "expected " + expected.get(0) + ", found " + e.getMessage();
        }
        if (invalid) {
            return "expected " + expected.get(0) + ", but the file assembles";
        }
        try {
            ModuleReader.read(module);
            return null;
        } catch (ModuleFormatException e) {
            return "the reader rejects the module: " + e.getMessage();
        }
    }

    // The values of the expect-error directives among the leading // lines of the file (TST-01).
    private static List<String> expectedErrors(byte[] text) {
        var values = new ArrayList<String>();
        for (String line : new String(text, StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("//")) {
                break;
            }
            if (line.startsWith(EXPECT_ERROR)) {
                values.add(line.substring(EXPECT_ERROR.length()).strip());
            }
        }
        return values;
    }
}
