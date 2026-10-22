package reflect.stage0.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.AssemblyException;

/**
 * The command rfla (README §6.2): assembles a .rfla file into a module file, or prints the first
 * diagnostic after the path of the file and writes no module.
 */
final class Rfla {
    private Rfla() {
    }

    /** The exit code: 0 when the module is written, 1 after a diagnostic, 2 for wrong arguments or files. */
    static int run(String[] args, PrintStream err) {
        if (args.length != 3 || !args[1].equals("-o")) {
            err.println("Usage: stage0 rfla <in.rfla> -o <out.rflc>");
            return 2;
        }
        byte[] module;
        try {
            module = Assembler.assemble(Files.readAllBytes(Path.of(args[0])));
        } catch (AssemblyException e) {
            err.println(args[0] + ":" + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("rfla: cannot read " + args[0] + ": " + e);
            return 2;
        }
        try {
            Files.write(Path.of(args[2]), module);
        } catch (IOException e) {
            err.println("rfla: cannot write " + args[2] + ": " + e);
            return 2;
        }
        return 0;
    }
}
