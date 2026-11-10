package reflect.stage0.vm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import reflect.stage0.bytecode.FieldDef;
import reflect.stage0.bytecode.FieldHandle;
import reflect.stage0.bytecode.FieldSig;
import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.MemberRef;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.MethodHandle;
import reflect.stage0.bytecode.MethodSig;
import reflect.stage0.bytecode.ModuleRef;
import reflect.stage0.bytecode.Opcode;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.bytecode.TypeRef;
import reflect.stage0.bytecode.TypeSig;
import reflect.stage0.bytecode.TypeSpec;
import reflect.stage0.lib.Nullable;

/**
 * VM0 (BOOT-04, README §7.6): one run of a module. It reads the module graph (VM-12), finds the
 * main class and its main method, and runs it. Types load at their first use (VM-13), and an
 * instruction resolves what it names at its first execution (VM-16). VM0 writes the console and its
 * messages as UTF-8 bytes.
 */
public final class Vm {
    /**
     * How a run ends: its exit code, the type of an uncaught exception or null, and the count of each opcode
     * that it started, keyed by number (README §7.6).
     */
    public record Result(int exitCode, @Nullable String uncaught, Map<Integer, Long> counts) {
    }

    // The stack of the thread that runs main (Q93). In the build image a call of VM0 takes about 180 to 310
    // bytes of it, so 256 MiB holds at least 860,000 calls, and the JVM's default of 1 MiB about 2,000
    // (sketchpad D.6).
    private static final long STACK_SIZE = 256L << 20;

    private static final String CORE = "reflect.core";
    private static final String TO_STRING = "reflect.core.String reflect.core.Object::toString()";

    private final PrintStream out;
    private final PrintStream err;
    private final Map<TypeDef, RuntimeType> types = new IdentityHashMap<>();
    private final Map<String, String> literals = new HashMap<>();
    // One slot for each one-byte opcode, then one for each two-byte opcode.
    private final long[] counts = new long[0x200];
    private @Nullable Modules modules;
    private @Nullable RuntimeMethod toString;

    Vm(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs the main module with the modules of the source: the main class is mainClass, a qualified name
     * of GEN-12, or else that of the module (.main). main runs on a new thread with a stack of 256 MiB
     * (Q93). The run exits with the result of int main, the argument of Runtime.exit, or 0; a Stop exits
     * with 2 and a fault with 1 (Q89).
     */
    public static Result run(ModuleData main, ModuleSource source, @Nullable String mainClass, List<String> args,
            PrintStream out, PrintStream err) {
        var vm = new Vm(out, err);
        try {
            return CompletableFuture.supplyAsync(() -> vm.start(main, source, mainClass, args),
                    task -> Thread.ofPlatform().name("vm0 main").stackSize(STACK_SIZE).start(task)).join();
        } catch (CompletionException e) {
            // start gives each run a result, so only an error such as OutOfMemoryError ends the thread without
            // one. It reaches the caller as it did when main ran on the caller's thread.
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private Result start(ModuleData main, ModuleSource source, @Nullable String mainClass, List<String> args) {
        try {
            Modules graph = resolve(main, source);
            RuntimeMethod entry = entry(graph.main(), mainClass);
            // The call of main is a static call, an active use of the main class (VM-07).
            entry.type().initialize(this);
            Object value = Interpreter.invoke(this, entry, new Object[] {args.toArray(new String[0])});
            return result(value == null ? 0 : (Integer) value, null);
        } catch (Exit e) {
            return result(e.code(), null);
        } catch (Stop e) {
            report("vm0: " + e.getMessage());
            return result(2, null);
        } catch (Fault e) {
            report(e.type() + ": " + e.getMessage());
            return result(1, e.type());
        } catch (StackOverflowError e) {
            // A fault until v0.0.8 (Q94). The JVM has unwound the stack to here, so the report has room.
            report(Fault.STACK_OVERFLOW_ERROR + ": the calls fill the " + (STACK_SIZE >> 20) + " MiB stack of VM0");
            return result(1, Fault.STACK_OVERFLOW_ERROR);
        } catch (RuntimeException e) {
            report("vm0: VM0 failed: " + e);
            return result(2, null);
        } finally {
            out.flush();
            err.flush();
        }
    }

    /** Reads the modules of the run (VM-12), whose reflect.core gives the base of a class with base 0 (Q96). */
    Modules resolve(ModuleData main, ModuleSource source) {
        Modules graph = Modules.load(main, source);
        modules = graph;
        return graph;
    }

    private Result result(int exitCode, @Nullable String uncaught) {
        var executed = new TreeMap<Integer, Long>();
        for (int slot = 0; slot < counts.length; slot++) {
            if (counts[slot] != 0) {
                executed.put(slot < 0x100 ? slot : (Opcode.PREFIX << 8) | (slot & 0xFF), counts[slot]);
            }
        }
        return new Result(exitCode, uncaught, executed);
    }

    // The main method (ex §2.4): the one public static main(reflect.core.String[]) of the main class that
    // returns void or int.
    private RuntimeMethod entry(RuntimeModule module, @Nullable String mainClass) {
        TypeDef type = mainClass == null ? module.definition().main() : module.type(mainClass);
        if (type == null) {
            throw new Stop(mainClass == null ? "the module " + module.name() + " names no main class; name one with"
                    + " .main or --main" : "the module " + module.name() + " defines no type " + mainClass);
        }
        String wanted = type.qualifiedName() + "::main([reflect.core]reflect.core.String[])";
        MethodDef found = null;
        for (MethodDef method : type.methods()) {
            String spelling = Spelling.method(type.qualifiedName(), method.name(), method.signature(), module.name(),
                    true);
            if ((method.flags() & Flags.PUBLIC) != 0 && (spelling.equals("static void " + wanted)
                    || spelling.equals("static int " + wanted))) {
                if (found != null) {
                    throw new Stop(type.qualifiedName() + " has two main methods, which return void and int");
                }
                found = method;
            }
        }
        if (found == null) {
            throw new Stop(type.qualifiedName() + " has no public static main(reflect.core.String[]) that returns void"
                    + " or int");
        }
        return type(module, type).method(found);
    }

    /**
     * The run-time type of a type of the module, which loads at its first use, after its base class, and
     * then builds its vtable (VM-13).
     */
    RuntimeType type(RuntimeModule module, TypeDef definition) {
        RuntimeType type = types.get(definition);
        if (type == null) {
            load(module, definition);
            type = new RuntimeType(module, definition, base(module, definition));
            types.put(definition, type);
            type.link(this);
        }
        return type;
    }

    // The base class (Q96): the type that the base names, or reflect.core.Object for a class or a class
    // record with base 0. reflect.core.Object, an interface, an annotation type and a value type have none.
    // The base is a class that is not final and not a record; the reader checks a base of the module, and
    // the load one of another module.
    private @Nullable RuntimeType base(RuntimeModule module, TypeDef definition) {
        TypeHandle handle = definition.base();
        boolean isClass = definition.kind() == Flags.KIND_CLASS || definition.kind() == Flags.KIND_RECORD;
        boolean isObject = module.name().equals(CORE) && definition.qualifiedName().equals(CORE + ".Object");
        RuntimeType base = handle != null ? type(module, handle) : isClass && !isObject
                ? core("Object", definition.qualifiedName()) : null;
        if (base != null) {
            int kind = base.definition().kind();
            String what = kind == Flags.KIND_RECORD ? "a record" : kind != Flags.KIND_CLASS ? "not a class"
                    : (base.definition().flags() & Flags.TYPE_FINAL) != 0 ? "final" : null;
            if (what != null) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, definition.qualifiedName() + " extends " + base.name()
                        + ", which is " + what + " (Q96)");
            }
        }
        return base;
    }

    // A type of the module reflect.core, which a base of 0 names without a TypeRef (Q96).
    private RuntimeType core(String name, String user) {
        @Nullable Modules graph = modules;
        @Nullable RuntimeModule core = graph == null ? null : graph.named(CORE);
        @Nullable TypeDef definition = core == null ? null : core.type(CORE + "." + name);
        if (core == null || definition == null) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, user + " needs " + CORE + "." + name + ", which the module graph"
                    + " does not hold");
        }
        return type(core, definition);
    }

    /**
     * The run-time type of a value on the stack: the type of an Instance, reflect.core.String for a string,
     * or null for any other value.
     */
    @Nullable RuntimeType typeOf(@Nullable Object value) {
        if (value instanceof String) {
            return core("String", "a string");
        }
        return value instanceof Instance object ? object.type() : null;
    }

    /** The text of an object: what the toString() in the slot of its run-time type gives (Q103). */
    @Nullable Object text(Object value) {
        RuntimeMethod method = toString;
        if (method == null) {
            RuntimeType object = core("Object", "String.valueOf");
            for (MethodDef definition : object.definition().methods()) {
                if (object.method(definition).signature().equals(TO_STRING)) {
                    method = object.method(definition);
                }
            }
            if (method == null) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + CORE + " defines no method " + TO_STRING);
            }
            toString = method;
        }
        return Interpreter.invoke(this, Interpreter.dispatch(this, method, value), new Object[] {value});
    }

    /** The run-time field that a field token of the module names: a Field row, or a MemberRef (Q69). */
    RuntimeField field(RuntimeModule from, FieldHandle handle) {
        if (handle instanceof FieldDef definition) {
            return type(from, from.owner(definition)).field(definition);
        }
        MemberRef ref = (MemberRef) handle;
        RuntimeType parent = type(from, ref.parent());
        TypeSig type = ((FieldSig) ref.signature()).type();
        String wanted = Spelling.type(type, from.name(), true);
        for (FieldDef definition : parent.definition().fields()) {
            if (definition.name().equals(ref.name())
                    && Spelling.type(definition.type(), parent.module().name(), true).equals(wanted)) {
                return parent.field(definition);
            }
        }
        throw new Fault(Fault.TYPE_LOAD_ERROR, "the module " + parent.module().name() + " defines no field "
                + Spelling.field(parent.name(), ref.name(), type, from.name(), false));
    }

    /**
     * The run-time type that a type token or a signature of the module names. Until their stages, VM0 runs a
     * TypeSpec only as the nullable form that a type test names (Q99).
     */
    RuntimeType type(RuntimeModule from, TypeHandle handle) {
        if (handle instanceof TypeDef definition) {
            return type(from, definition);
        }
        if (handle instanceof TypeSpec spec) {
            throw new Stop("VM0 does not run the type " + spec.qualifiedName() + " yet");
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

    /** Counts an instruction that the run starts to execute (README §7.6). */
    void count(int opcode) {
        counts[opcode < 0x100 ? opcode : 0x100 | (opcode & 0xFF)]++;
    }

    /** Writes bytes to standard output (1) or standard error (2), in the order of the calls. */
    void write(int fd, byte[] bytes) {
        out.flush();
        PrintStream stream = fd == 1 ? out : err;
        stream.writeBytes(bytes);
        stream.flush();
    }

    // Loading checks a type (VM-13): each native belongs to a trusted module and has a Java method (LIB-03,
    // VM-08).
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
        }
    }

    private void report(String message) {
        write(2, (message + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
