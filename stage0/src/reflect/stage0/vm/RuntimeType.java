package reflect.stage0.vm;

import java.util.IdentityHashMap;
import java.util.Map;

import reflect.stage0.bytecode.FieldDef;
import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.lib.Nullable;

/**
 * A run-time type (VM-03): a type of a module that VM0 has loaded at its first use (VM-13), with its
 * base class (Q96), the slots of its instance fields, those of the base class first (VM-02), the
 * storage of its static fields, whether its initialization has started (VM-07), and the methods and
 * fields that VM0 has linked.
 */
final class RuntimeType {
    private final RuntimeModule module;
    private final TypeDef definition;
    private final @Nullable RuntimeType base;
    private final Map<MethodDef, RuntimeMethod> methods = new IdentityHashMap<>();
    private final Map<FieldDef, RuntimeField> fields = new IdentityHashMap<>();
    private final @Nullable Object[] zeros;
    private final @Nullable Object[] statics;
    private final @Nullable MethodDef initializer;
    private boolean started;

    RuntimeType(RuntimeModule module, TypeDef definition, @Nullable RuntimeType base) {
        this.module = module;
        this.definition = definition;
        this.base = base;
        int inherited = base == null ? 0 : base.zeros.length;
        int own = 0;
        int shared = 0;
        for (FieldDef field : definition.fields()) {
            if ((field.flags() & Flags.FIELD_STATIC) == 0) {
                own++;
            } else {
                shared++;
            }
        }
        zeros = new Object[inherited + own];
        if (base != null) {
            System.arraycopy(base.zeros, 0, zeros, 0, inherited);
        }
        statics = new Object[shared];
        int slot = inherited;
        int index = 0;
        for (FieldDef field : definition.fields()) {
            if (Values.valueType(field.type())) {
                throw new Stop(name() + "::" + field.name() + ": VM0 does not run fields of value types yet");
            }
            if ((field.flags() & Flags.FIELD_STATIC) == 0) {
                zeros[slot] = Values.zero(field.type());
                fields.put(field, new RuntimeField(this, field, slot++));
            } else {
                statics[index] = Values.zero(field.type());
                fields.put(field, new RuntimeField(this, field, index++));
            }
        }
        MethodDef found = null;
        for (MethodDef method : definition.methods()) {
            if ((method.flags() & (Flags.METHOD_CONSTRUCTOR | Flags.METHOD_STATIC))
                    == (Flags.METHOD_CONSTRUCTOR | Flags.METHOD_STATIC)) {
                found = method;
            }
        }
        initializer = found;
    }

    RuntimeModule module() {
        return module;
    }

    TypeDef definition() {
        return definition;
    }

    String name() {
        return definition.qualifiedName();
    }

    /** The base class, or null for reflect.core.Object, an interface and a value type (Q96). */
    @Nullable RuntimeType base() {
        return base;
    }

    /** Whether the type is a struct, a ref struct, a record struct or an enum (TYP-01). */
    boolean isValueType() {
        int kind = definition.kind();
        return kind == Flags.KIND_STRUCT || kind == Flags.KIND_REF_STRUCT || kind == Flags.KIND_RECORD_STRUCT
                || kind == Flags.KIND_ENUM;
    }

    /** Whether the type is an interface or an annotation type, which has no instances of its own. */
    boolean isInterface() {
        return definition.kind() == Flags.KIND_INTERFACE || definition.kind() == Flags.KIND_ANNOTATION;
    }

    /** Whether the type is this one or a class of its base chain. */
    boolean isSubtypeOf(RuntimeType other) {
        for (RuntimeType type = this; type != null; type = type.base) {
            if (type == other) {
                return true;
            }
        }
        return false;
    }

    /** A new object of the type, with every field at its zero value (BC-17). */
    Instance allocate() {
        return new Instance(this, zeros.clone());
    }

    /** The values of the static fields, one storage per class (MEM-05), each at zero until its first store. */
    @Nullable Object[] statics() {
        return statics;
    }

    /**
     * Initializes the type at its first active use, once (VM-07): its base class first, then its static
     * initializer. Every later request, one from that initializer included, proceeds and sees the values
     * that the fields hold. With one thread, the states running and done behave alike, and until v0.0.8 a
     * failure ends the run, so the type records only that its initialization has started. Until then, a
     * fault that is an exception ends the initializer as ExceptionInInitializerError, with the fault as its
     * message, and an error ends it unchanged (Q102).
     */
    void initialize(Vm vm) {
        if (started) {
            return;
        }
        started = true;
        if (base != null) {
            base.initialize(vm);
        }
        MethodDef code = initializer;
        if (code != null) {
            try {
                Interpreter.invoke(vm, method(code), new Object[0]);
            } catch (Fault fault) {
                if (fault.isError()) {
                    throw fault;
                }
                throw new Fault(Fault.EXCEPTION_IN_INITIALIZER_ERROR, fault.type() + ": " + fault.getMessage());
            }
        }
    }

    /** The run-time method of a method that the type lists. */
    RuntimeMethod method(MethodDef method) {
        return methods.computeIfAbsent(method, definition -> new RuntimeMethod(this, definition));
    }

    /** The run-time field of a field that the type lists, with its slot among the instance or the static fields. */
    RuntimeField field(FieldDef field) {
        return fields.get(field);
    }
}
