package reflect.stage0.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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

public final class RfldTest {
    @Test
    public static void rfldPrintsTheTextOrWhyTheFileHasNone() throws Exception {
        Path directory = Files.createTempDirectory("reflect-rfld-");
        Path in = directory.resolve("m.rflc");
        Path error = directory.resolve("error.txt");
        try {
            // A name outside ASCII, which the charset of the container's standard output would change.
            String text = ".module m 1.2.3\n\n.class a." + (char) 0xC4 + " {\n}\n";
            byte[] module = Assembler.assemble(text.getBytes(StandardCharsets.UTF_8));
            Files.write(in, module);
            assertEquals(new Result(0, text, ""), rfld(error, in.toString()));
            module[0]++;
            Files.write(in, module);
            assertEquals(new Result(1, "", in + ": offset 0x0: the file does not start with RFLC\n"),
                    rfld(error, in.toString()));
            Result missing = rfld(error, directory.resolve("missing.rflc").toString());
            assertEquals(2, missing.exitCode());
            assertEquals("", missing.output());
            assertTrue(missing.error().startsWith("rfld: cannot read "));
            var usage = new Result(2, "", "Usage: stage0 rfld <in.rflc>\n");
            assertEquals(usage, rfld(error));
            assertEquals(usage, rfld(error, in.toString(), "extra"));
        } finally {
            Files.deleteIfExists(error);
            Files.deleteIfExists(in);
            Files.delete(directory);
        }
    }

    @Test
    public static void rfldReportsATextThatItCannotWrite() throws Exception {
        Path directory = Files.createTempDirectory("reflect-rfld-");
        Path in = directory.resolve("m.rflc");
        try {
            Files.write(in, Assembler.assemble(".module m 1.2.3\n".getBytes(StandardCharsets.UTF_8)));
            var closed = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("the stream is closed");
                }
            });
            var error = new ByteArrayOutputStream();
            assertEquals(2, Rfld.run(new String[] {in.toString()}, closed, new PrintStream(error, true,
                    StandardCharsets.UTF_8)));
            assertEquals("rfld: cannot write the text\n", error.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(in);
            Files.delete(directory);
        }
    }

    // Runs the command in its own JVM, as a user does, with standard error in its own file.
    private static Result rfld(Path error, String... args) throws Exception {
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "out/stage0/stage0.jar", "rfld"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectError(error.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new Result(exitCode, output, Files.readString(error));
    }

    private record Result(int exitCode, String output, String error) {
    }
}
