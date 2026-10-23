package reflect.stage0.tools;

import java.io.PrintStream;
import java.util.Arrays;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1
                && (args[0].equals("--help") || args[0].equals("-h")))) {
            usage(System.out);
            return;
        }
        if (args[0].equals("rfla")) {
            System.exit(Rfla.run(Arrays.copyOfRange(args, 1, args.length), System.err));
        }
        if (args[0].equals("rfld")) {
            System.exit(Rfld.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        if (args[0].equals("vm0")) {
            System.exit(Vm0.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        if (args[0].equals("test")) {
            System.exit(Suite.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        System.err.println("Unknown command or arguments: " + String.join(" ", args));
        usage(System.err);
        System.exit(2);
    }

    private static void usage(PrintStream out) {
        out.println("Usage: stage0 <command> [arguments]");
        out.println("  rfla <in.rfla> -o <out.rflc>    assemble a module");
        out.println("  rfld <in.rflc>                  disassemble a module to standard output");
        out.println("  vm0 run [--module-path <dirs>] [--main <class>] <app.rflc> [args]");
        out.println("                                  run a module on VM0");
        out.println("  test <directory>                check the .rfla suite in the directory");
    }
}
