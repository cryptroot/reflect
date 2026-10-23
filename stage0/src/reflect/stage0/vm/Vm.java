package reflect.stage0.vm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.MemberRef;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.MethodHandle;
import reflect.stage0.bytecode.MethodSig;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.bytecode.TypeRef;
import reflect.stage0.lib.Nullable;

/**
 * VM0 (BOOT-04, README §7.6): one run of a module. It reads the module graph (VM-12), finds the
 * main class and its main method, and runs it. Types load at their first use (VM-13), and an
 * instruction resolves what it names at its first execution (VM-16). VM0 writes the console and its
 * messages as UTF-8 bytes.
 */
public final class Vm {
    /** How a run ends: its exit code, and the type of an uncaught exception or null. */
    public record Result(int exitCode, @Nullable String uncaught) {
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Map<TypeDef, RuntimeType> types = new IdentityHashMap<>();
    private final Map<String, String> literals = new HashMap<>();

    private Vm(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs the main module with the modules of the source: the main class is mainClass, a qualified name
     * of GEN-12, or else that of the module (.main). A Stop exits with 2 and a fault with 1 (Q89).
     */
    public static Result run(ModuleData main, ModuleSource source, @Nullable String mainClass, List<String> args,
            PrintStream out, PrintStream err) {
        var vm = new Vm(out, err);
        try {
            Modules modules = Modules.load(main, source);
            RuntimeMethod entry = vm.entry(modules.main(), mainClass);
            Interpreter.invoke(vm, entry, new Object[] {args.toArray(new String[0])});
            return new Result(0, null);
        } catch (Stop e) {
            vm.report("vm0: " + e.getMessage());
            return new Result(2, null);
        } catch (Fault e) {
            vm.report(e.type() + ": " + e.getMessage());
            return new Result(1, e.type());
        } catch (RuntimeException e) {
            vm.report("vm0: VM0 failed: " + e);
            return new Result(2, null);
        } finally {
            out.flush();
            err.flush();
        }
    }

    // The main method (ex §2.4): public static void main(reflect.core.String[]) of the main class.
    private RuntimeMethod entry(RuntimeModule module, @Nullable String mainClass) {
        TypeDef type = mainClass == null ? module.definition().main() : module.type(mainClass);
        if (type == null) {
            throw new Stop(mainClass == null ? "the module " + module.name() + " names no main class; name one with"
                    + " .main or --main" : "the module " + module.name() + " defines no type " + mainClass);
        }
        String wanted = "static void " + type.qualifiedName() + "::main([reflect.core]reflect.core.String[])";
        for (MethodDef method : type.methods()) {
            if ((method.flags() & Flags.PUBLIC) != 0 && Spelling.method(type.qualifiedName(), method.name(),
                    method.signature(), module.name(), true).equals(wanted)) {
                return type(module, type).method(method);
            }
        }
        throw new Stop(type.qualifiedName() + " has no public static void main(reflect.core.String[])");
    }

    /** The run-time type of a type of the module, which loads at its first use (VM-13). */
    RuntimeType type(RuntimeModule module, TypeDef definition) {
        RuntimeType type = types.get(definition);
        if (type == null) {
            load(module, definition);
            type = new RuntimeType(module, definition);
            types.put(definition, type);
        }
        return type;
    }

    /** The run-time type that a type token or a signature of the module names. */
    RuntimeType type(RuntimeModule from, TypeHandle handle) {
        if (handle instanceof TypeDef definition) {
            return type(from, definition);
        }
        TypeRef ref = (TypeRef) handle;
        TypeRef top = ref;
        while (top.scope() instanceof TypeRef outer) {
            top = outer;
        }
        ModuleRef scope = (ModuleRef) top.scope();
        RuntimeModule module = from.required(scope);
        if (module == null) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + scope.name() + " of " + ref.qualifiedName()
                    + " is absent, which " + from.name() + " requires static");
        }
        TypeDef definition = module.type(ref.qualifiedName());
        if (definition == null) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + module.name() + " defines no type "
                    + ref.qualifiedName());
        }
        return type(module, definition);
    }

    /** The run-time method that a method token of the module names: a Method row, or a MemberRef (Q69). */
    RuntimeMethod method(RuntimeModule from, MethodHandle handle) {
        if (handle instanceof MethodDef definition) {
            return type(from, from.owner(definition)).method(definition);
        }
        MemberRef ref = (MemberRef) handle;
        RuntimeType parent = type(from, ref.parent());
        MethodSig signature = (MethodSig) ref.signature();
        String wanted = Spelling.method(parent.name(), ref.name(), signature, from.name(), true);
        for (MethodDef definition : parent.definition().methods()) {
            if (Spelling.method(parent.name(), definition.name(), definition.signature(), parent.module().name(), true)
                    .equals(wanted)) {
                return parent.method(definition);
            }
        }
        throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + parent.module().name() + " defines no method "
                + Spelling.method(parent.name(), ref.name(), signature, from.name(), false));
    }

    /** The interned string of a literal (NUM-05). */
    String literal(String text) {
        return literals.computeIfAbsent(text, key -> key);
    }

    /** Writes bytes to standard output (1) or standard error (2), in the order of the calls. */
    void write(int fd, byte[] bytes) {
        out.flush();
        PrintStream stream = fd == 1 ? out : err;
        stream.writeBytes(bytes);
        stream.flush();
    }

    // Loading checks a type (VM-13): each native belongs to a trusted module and has a Java method (LIB-03,
    // VM-08). Static initializers arrive in v0.0.6, so until then VM0 stops at a type that has one.
    private static void load(RuntimeModule module, TypeDef definition) {
        for (MethodDef method : definition.methods()) {
            if ((method.flags() & Flags.METHOD_NATIVE) != 0) {
                String signature = Spelling.method(definition.qualifiedName(), method.name(), method.signature(),
                        module.name(), false);
                if (!Natives.trusted(module.name())) {
                    throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + module.name() + " declares the native "
                            + signature + ", but only trusted modules declare natives (LIB-03)");
                }
                if (Natives.find(module.name(), signature) == null) {
                    throw new Fault(Fault.TYPE_LOAD_ERROR, "VM0 has no native " + signature + " (VM-08)");
                }
            }
            if ((method.flags() & Flags.METHOD_CONSTRUCTOR) != 0 && method.name().equals("static")) {
                throw new Stop("VM0 does not run the static initializer of " + definition.qualifiedName() + " yet");
            }
        }
    }

    private void report(String message) {
        write(2, (message + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
