package reflect.stage0.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import reflect.stage0.vm.ModuleData;
import reflect.stage0.vm.ModulePath;
import reflect.stage0.vm.Vm;

/**
 * The command vm0 run (README §6.2): runs a module on VM0 with the modules of the module path. The
 * options come before the module file; the words after it are the arguments of main.
 */
final class Vm0 {
    private static final String USAGE = "Usage: stage0 vm0 run [--module-path <dirs>] [--main <class>] <app.rflc> [args]";

    private Vm0() {
    }

    /** The exit code of the run (Q89), or 2 for wrong arguments or a file that cannot be read. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !args[0].equals("run")) {
            return usage(err);
        }
        String modulePath = null;
        String mainClass = null;
        int i = 1;
        while (i < args.length && args[i].startsWith("--")) {
            if (i + 1 == args.length) {
                return usage(err);
            }
            if (args[i].equals("--module-path") && modulePath == null) {
                modulePath = args[i + 1];
            } else if (args[i].equals("--main") && mainClass == null) {
                mainClass = args[i + 1];
            } else {
                return usage(err);
            }
            i += 2;
        }
        if (i == args.length) {
            return usage(err);
        }
        byte[] module;
        try {
            module = Files.readAllBytes(Path.of(args[i]));
        } catch (IOException e) {
            err.println("vm0: cannot read " + args[i] + ": " + e);
            return 2;
        }
        ModulePath path = modulePath == null ? new ModulePath(List.of()) : ModulePath.parse(modulePath);
        List<String> mainArgs = Arrays.asList(args).subList(i + 1, args.length);
        return Vm.run(new ModuleData(args[i], module), path, mainClass, mainArgs, out, err).exitCode();
    }

    private static int usage(PrintStream err) {
        err.println(USAGE);
        return 2;
    }
}
