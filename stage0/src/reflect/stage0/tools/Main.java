package reflect.stage0.tools;

import java.io.PrintStream;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1
                && (args[0].equals("--help") || args[0].equals("-h")))) {
            usage(System.out);
            return;
        }
        System.err.println("Unknown command or arguments: " + String.join(" ", args));
        usage(System.err);
        System.exit(2);
    }

    private static void usage(PrintStream out) {
        out.println("Usage: stage0 <command> [arguments]");
        out.println("Commands rfla, rfld, vm0 and test arrive in later v0.0.x stages.");
    }
}
