package reflect.stage0.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import reflect.stage0.bytecode.Disassembler;
import reflect.stage0.bytecode.ModuleFormatException;

/**
 * The command rfld (README §6.2): prints the canonical text of a module file (BC-25) to standard
 * output, or prints why the file has no text after the path of the file and prints no text.
 */
final class Rfld {
    private Rfld() {
    }

    /** The exit code: 0 after the text, 1 when the file has no text, 2 for wrong arguments, files or output. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("Usage: stage0 rfld <in.rflc>");
            return 2;
        }
        byte[] text;
        try {
            text = Disassembler.disassemble(Files.readAllBytes(Path.of(args[0])));
        } catch (ModuleFormatException e) {
            err.println(args[0] + ": " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("rfld: cannot read " + args[0] + ": " + e);
            return 2;
        }
        // The UTF-8 bytes as they are, so the charset of the terminal changes no name.
        out.writeBytes(text);
        if (out.checkError()) {
            err.println("rfld: cannot write the text");
            return 2;
        }
        return 0;
    }
}
