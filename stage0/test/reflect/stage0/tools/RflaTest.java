package reflect.stage0.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class RflaTest {
    @Test
    public static void rflaWritesTheModuleOrReportsTheFirstDiagnostic() throws Exception {
        Path directory = Files.createTempDirectory("reflect-rfla-");
        Path in = directory.resolve("m.rfla");
        Path out = directory.resolve("m.rflc");
        try {
            String text = ".module m 1.2.3\n.class a.A {\n}\n";
            Files.writeString(in, text);
            assertEquals(new Result(0, ""), rfla(in.toString(), "-o", out.toString()));
            assertEquals(Arrays.toString(Assembler.assemble(text.getBytes(StandardCharsets.UTF_8))),
                    Arrays.toString(Files.readAllBytes(out)));
            Files.delete(out);
            Files.writeString(in, ".module m 1.2.3\n.class a.A {\n");
            assertEquals(new Result(1, in + ":3:1: RFA0101: expected .field, .method, .class or }, found the end of the"
                    + " file\n"), rfla(in.toString(), "-o", out.toString()));
            assertTrue(!Files.exists(out));
            Result missing = rfla(directory.resolve("missing.rfla").toString(), "-o", out.toString());
            assertEquals(2, missing.exitCode());
            assertTrue(missing.output().startsWith("rfla: cannot read "));
            var usage = new Result(2, "Usage: stage0 rfla <in.rfla> -o <out.rflc>\n");
            assertEquals(usage, rfla(in.toString(), out.toString()));
            assertEquals(usage, rfla(in.toString(), "-x", out.toString()));
            assertEquals(usage, rfla(in.toString(), "-o", out.toString(), "extra"));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(in);
            Files.delete(directory);
        }
    }

    // Runs the command in its own JVM, as a user does, with standard error after standard output.
    private static Result rfla(String... args) throws Exception {
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "out/stage0/stage0.jar", "rfla"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {
    }
}
