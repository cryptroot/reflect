package reflect.stage0.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import reflect.stage0.bytecode.FieldDef;
import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.MethodHandle;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.lib.Nullable;

/**
 * A run-time type (VM-03): a type of a module that VM0 has loaded at its first use (VM-13), with its
 * base class (Q96) and its interfaces, the slots of its instance fields, those of the base class first
 * (VM-02), its vtable and interface table (VM-04), the storage of its static fields, whether its
 * initialization has started (VM-07), and the methods and fields that VM0 has linked.
 */
final class RuntimeType {
    private final RuntimeModule module;
    private final TypeDef definition;
    private final @Nullable RuntimeType base;
    private final List<RuntimeType> interfaces = new ArrayList<>();
    private final Map<MethodDef, RuntimeMethod> methods = new IdentityHashMap<>();
    private final Map<FieldDef, RuntimeField> fields = new IdentityHashMap<>();
    // The implementation of each interface method that a call on an object of the class has named.
    private final Map<RuntimeMethod, RuntimeMethod> itable = new IdentityHashMap<>();
    private final @Nullable Object[] zeros;
    private final @Nullable Object[] statics;
    private final @Nullable MethodDef initializer;
    private RuntimeMethod[] vtable = new RuntimeMethod[0];
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

    /**
     * Whether the type is this one, a class of its base chain, or an interface that one of them implements,
     * directly or through the interfaces that an interface extends (TYP-02, Q96).
     */
    boolean isSubtypeOf(RuntimeType other) {
        for (RuntimeType type = this; type != null; type = type.base) {
            if (type == other) {
                return true;
            }
            for (RuntimeType implemented : type.interfaces) {
                if (implemented.isSubtypeOf(other)) {
                    return true;
                }
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

    /**
     * Links the type after its base class: the interfaces of its InterfaceImpl rows (Q96), and the checks of its
     * sealed supertypes (Q95), then, for a class, its vtable (VM-04, BC-12, Q97): the slots of the base class,
     * then for each VIRTUAL or ABSTRACT instance method, in row order, the slot of each method of a base class
     * that an Override row of the method names, or else a new slot. A row may name an override, whose slot is
     * the one that it fills. Value types (v0.0.7) and the load checks of the rows come later (README §7.7).
     */
    void link(Vm vm) {
        for (TypeHandle implemented : definition.interfaces()) {
            RuntimeType type = vm.type(module, implemented);
            // The reader checks an interface of the module, and this one of another module (Q96).
            if (!type.isInterface()) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, name() + " implements " + type.name() + ", which is not an"
                        + " interface (Q96)");
            }
            interfaces.add(type);
        }
        checkSealing();
        if (isInterface() || isValueType()) {
            return;
        }
        List<RuntimeMethod> slots = new ArrayList<>(base == null ? List.of() : Arrays.asList(base.vtable));
        for (MethodDef definition : definition.methods()) {
            RuntimeMethod method = method(definition);
            if (!method.hasSlot()) {
                continue;
            }
            for (MethodHandle overridden : definition.overrides()) {
                RuntimeMethod replaced = vm.method(module, overridden);
                if (replaced.slot() >= 0 && isSubtypeOf(replaced.type())) {
                    slots.set(replaced.slot(), method);
                    method.setSlot(replaced.slot());
                }
            }
            if (method.slot() < 0) {
                method.setSlot(slots.size());
                slots.add(method);
            }
        }
        vtable = slots.toArray(new RuntimeMethod[0]);
    }

    // A direct supertype that is SEALED, the base type or an interface, belongs to the module of the type, which is
    // FINAL, SEALED or UNSEALED, or a record or a value type, which nothing extends; and UNSEALED stands only on a
    // type with a sealed direct supertype (DAT-06, Q95). A violation is a TypeLoadError at the load (VM-13).
    private void checkSealing() {
        var direct = new ArrayList<RuntimeType>(interfaces);
        if (base != null) {
            direct.add(0, base);
        }
        boolean sealed = false;
        for (RuntimeType supertype : direct) {
            if ((supertype.definition.flags() & Flags.TYPE_SEALED) == 0) {
                continue;
            }
            sealed = true;
            if (supertype.module != module) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, name() + " has the sealed supertype " + supertype.name() + " of"
                        + " the module " + supertype.module.name() + ", which permits only its own types (DAT-06)");
            }
            int kind = definition.kind();
            int sealing = Flags.TYPE_FINAL | Flags.TYPE_SEALED | Flags.TYPE_UNSEALED;
            if ((definition.flags() & sealing) == 0 && kind != Flags.KIND_RECORD && !isValueType()) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, name() + " has the sealed supertype " + supertype.name()
                        + ", so it is final, sealed or unsealed (DAT-06)");
            }
        }
        if ((definition.flags() & Flags.TYPE_UNSEALED) != 0 && !sealed) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, name() + " is unsealed, but no direct supertype of it is sealed (Q95)");
        }
    }

    /** The method in the slot of a method of the type or of a base class, which the slot of this type holds. */
    RuntimeMethod implementation(RuntimeMethod method) {
        return vtable[method.slot()];
    }

    /**
     * The method that a call of an interface method runs on an object of this class (VM-04, Q97): the
     * method in the slot of the nearest class, this one first, with an Override row for the interface
     * method or for an interface method that overrides it; else the one most specific default of the
     * interfaces of the class, or an abstract method when none is. Two defaults that conflict are a
     * TypeLoadError at the call (Q101). The interface table keeps each result.
     */
    RuntimeMethod implementation(Vm vm, RuntimeMethod method) {
        RuntimeMethod found = itable.get(method);
        if (found == null) {
            found = classImplementation(vm, method);
            if (found == null) {
                found = defaultImplementation(vm, method);
            }
            itable.put(method, found);
        }
        return found;
    }

    private @Nullable RuntimeMethod classImplementation(Vm vm, RuntimeMethod method) {
        for (RuntimeType type = this; type != null; type = type.base) {
            for (MethodDef definition : type.definition.methods()) {
                RuntimeMethod candidate = type.method(definition);
                if (candidate.slot() < 0) {
                    continue;
                }
                for (MethodHandle overridden : definition.overrides()) {
                    if (vm.method(type.module, overridden).overrides(vm, method)) {
                        return vtable[candidate.slot()];
                    }
                }
            }
        }
        return null;
    }

    // The most specific methods are those that no other method of the interfaces overrides; one of them
    // with a body is the default (MEM-03). The method itself is a candidate, so without a default one of
    // them is abstract.
    private RuntimeMethod defaultImplementation(Vm vm, RuntimeMethod method) {
        var candidates = new ArrayList<RuntimeMethod>();
        for (RuntimeType implemented : allInterfaces()) {
            for (MethodDef definition : implemented.definition.methods()) {
                RuntimeMethod candidate = implemented.method(definition);
                if (candidate.hasSlot() && candidate.overrides(vm, method)) {
                    candidates.add(candidate);
                }
            }
        }
        var defaults = new ArrayList<RuntimeMethod>();
        RuntimeMethod abstractMethod = method;
        for (RuntimeMethod candidate : candidates) {
            boolean overridden = false;
            for (RuntimeMethod other : candidates) {
                overridden |= other != candidate && other.overrides(vm, candidate);
            }
            if (!overridden && candidate.isAbstract()) {
                abstractMethod = candidate;
            } else if (!overridden) {
                defaults.add(candidate);
            }
        }
        if (defaults.size() > 1) {
            var names = new ArrayList<String>();
            for (RuntimeMethod found : defaults) {
                names.add(found.signature());
            }
            throw new Fault(Fault.TYPE_LOAD_ERROR, "the defaults " + String.join(" and ", names) + " of "
                    + method.signature() + " conflict in " + name());
        }
        return defaults.isEmpty() ? abstractMethod : defaults.get(0);
    }

    // The interfaces of the class and of its base classes, and those that they extend, each once.
    private List<RuntimeType> allInterfaces() {
        var all = new ArrayList<RuntimeType>();
        for (RuntimeType type = this; type != null; type = type.base) {
            type.addInterfaces(all);
        }
        return all;
    }

    private void addInterfaces(List<RuntimeType> all) {
        for (RuntimeType implemented : interfaces) {
            if (!all.contains(implemented)) {
                all.add(implemented);
                implemented.addInterfaces(all);
            }
        }
    }

    /** The run-time field of a field that the type lists, with its slot among the instance or the static fields. */
    RuntimeField field(FieldDef field) {
        return fields.get(field);
    }
}
