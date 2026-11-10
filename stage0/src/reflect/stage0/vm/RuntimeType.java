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
import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.bytecode.TypeSig;
import reflect.stage0.lib.Nullable;

/**
 * A run-time type (VM-03): a type of a module that VM0 has loaded at its first use (VM-13), with its
 * base class (Q96) and its interfaces, the slots of its instance fields, those of the base class first
 * (VM-02), its vtable and interface table (VM-04), the storage of its static fields, whether its
 * initialization has started (VM-07), and the methods and fields that VM0 has linked. An array type is
 * one too, by its element type, with the base class reflect.core.Array (Q106).
 */
final class RuntimeType {
    private final RuntimeModule module;
    private final @Nullable TypeDef definition;
    private final @Nullable Element element;
    private final String name;
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
        element = null;
        name = definition.qualifiedName();
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

    /**
     * The run-time type of the arrays of an element type (VM-03, Q106), which extends reflect.core.Array: it
     * has the vtable of that class, and no fields, interfaces or initializer of its own.
     */
    RuntimeType(RuntimeType array, Element element) {
        module = array.module;
        definition = null;
        this.element = element;
        name = element.name() + "[]";
        base = array;
        zeros = new Object[0];
        statics = new Object[0];
        initializer = null;
        vtable = array.vtable;
        started = true;
    }

    RuntimeModule module() {
        return module;
    }

    /** The TypeDef row of the type, or null for an array type. */
    @Nullable TypeDef definition() {
        return definition;
    }

    /** The name of the type as GEN-12 spells it, such as demo.zoo.Dog or reflect.core.Int32[]. */
    String name() {
        return name;
    }

    /** The base class, or null for reflect.core.Object, an interface and a value type (Q96). */
    @Nullable RuntimeType base() {
        return base;
    }

    /** Whether the type is a struct, a ref struct, a record struct or an enum (TYP-01). */
    boolean isValueType() {
        if (definition == null) {
            return false;
        }
        int kind = definition.kind();
        return kind == Flags.KIND_STRUCT || kind == Flags.KIND_REF_STRUCT || kind == Flags.KIND_RECORD_STRUCT
                || kind == Flags.KIND_ENUM;
    }

    /** Whether the type is an interface or an annotation type, which has no instances of its own. */
    boolean isInterface() {
        return definition != null && (definition.kind() == Flags.KIND_INTERFACE
                || definition.kind() == Flags.KIND_ANNOTATION);
    }

    /**
     * Whether the type is this one, a class of its base chain, or an interface that one of them implements,
     * directly or through the interfaces that an interface extends (TYP-02, Q96). An array type is a subtype
     * only of itself and of its base classes, since arrays are invariant (NUL-07).
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

    /** A new array of the array type with this length, with every element at its zero value (Q106). */
    ArrayInstance allocate(int length) {
        return new ArrayInstance(this, element.elements(length));
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
     * the one that it fills. Loading checks each row of the type's methods (BC-12), the return types once the
     * vtable stands, since they may load other types. Value types arrive in v0.0.7.
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
        boolean isClass = !isInterface() && !isValueType();
        List<RuntimeMethod> slots = new ArrayList<>(base == null || !isClass ? List.of() : Arrays.asList(base.vtable));
        // Each row with the method whose return type it keeps or narrows: the one that the slot held, or the
        // interface method that it names.
        var rows = new ArrayList<RuntimeMethod[]>();
        for (MethodDef definition : definition.methods()) {
            RuntimeMethod method = method(definition);
            for (MethodHandle overridden : definition.overrides()) {
                RuntimeMethod replaced = overridable(method, vm.method(module, overridden));
                int slot = replaced.slot();
                if (!isClass || slot < 0) {
                    rows.add(new RuntimeMethod[] {method, replaced});
                } else if (method.slot() < 0) {
                    rows.add(new RuntimeMethod[] {method, slots.get(slot)});
                    slots.set(slot, method);
                    method.setSlot(slot);
                } else if (method.slot() != slot) {
                    throw new Fault(Fault.TYPE_LOAD_ERROR, method.signature() + " fills a second slot, that of "
                            + replaced.signature() + " (Q97)");
                }
            }
            if (isClass && method.hasSlot() && method.slot() < 0) {
                method.setSlot(slots.size());
                slots.add(method);
            }
        }
        vtable = slots.toArray(new RuntimeMethod[0]);
        for (RuntimeMethod[] row : rows) {
            if (!converts(vm, row[0].definition().signature().returnType(), module,
                    row[1].definition().signature().returnType(), row[1].type().module())) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, "the return type of " + row[0].signature() + " does not convert"
                        + " to that of " + row[1].signature() + " (BC-12)");
            }
        }
    }

    // The method that an Override row of the method names (BC-12, Q97): a method of a supertype with a slot, a
    // VIRTUAL or ABSTRACT instance method, which is not FINAL and takes the parameter types of the override
    // (MEM-02). Another one is a TypeLoadError at the load.
    private RuntimeMethod overridable(RuntimeMethod method, RuntimeMethod replaced) {
        String problem = !isSubtypeOf(replaced.type()) ? ", but " + replaced.type().name() + " is no supertype of "
                + name() + " (BC-12)"
                : !replaced.hasSlot() ? ", which is not a virtual or abstract instance method (BC-12)"
                : (replaced.definition().flags() & Flags.METHOD_FINAL) != 0 ? ", which is final (MEM-02)"
                : !method.parameters().equals(replaced.parameters()) ? ", whose parameter types differ (MEM-02)" : null;
        if (problem != null) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, method.signature() + " overrides " + replaced.signature() + problem);
        }
        return replaced;
    }

    // Whether a value of the type from, which a signature of the module source holds, converts without a bridge to
    // the type to of the module target (BC-12, MEM-02): it is that type, or a reference subtype of it (TYP-02), where
    // a type converts to the nullable form of its supertypes, and every reference type to reflect.core.Object.
    private static boolean converts(Vm vm, TypeSig from, RuntimeModule source, TypeSig to, RuntimeModule target) {
        if (Spelling.type(from, source.name(), true).equals(Spelling.type(to, target.name(), true))) {
            return true;
        }
        if (to instanceof TypeSig.NullableRef nullable) {
            TypeSig inner = from instanceof TypeSig.NullableRef wrapped ? wrapped.type() : from;
            return converts(vm, inner, source, nullable.type(), target);
        }
        if (!reference(from) || !(to instanceof TypeSig.Named named) || named.valueType()) {
            return false;
        }
        if (Spelling.type(to, target.name(), true).equals("[reflect.core]reflect.core.Object")) {
            return true;
        }
        return from instanceof TypeSig.Named type && vm.type(source, type.type()).isSubtypeOf(vm.type(target, named.type()));
    }

    // Whether the type is a reference type that is not nullable: a class or an interface, an instantiation of one, an
    // array or an intersection (TYP-01).
    private static boolean reference(TypeSig type) {
        return type instanceof TypeSig.Named named && !named.valueType()
                || type instanceof TypeSig.GenericInst instance && !instance.type().valueType()
                || type instanceof TypeSig.ArrayOf || type instanceof TypeSig.Intersection;
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
            if (type.definition == null) {
                continue;
            }
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

    /**
     * The element type of an array type (Q106): a primitive by its signature code, or else a run-time type and
     * whether the element type is its nullable form (NUL-01). Element types are equal when they are one
     * primitive, or one run-time type with one nullability, so each array type has one run-time type.
     */
    record Element(int primitive, @Nullable RuntimeType type, boolean nullable) {
        /** The element type as GEN-12 spells it. */
        String name() {
            return type == null ? Spelling.struct(primitive) : type.name() + (nullable ? "?" : "");
        }

        /** A Java array of the storage kind of the element type (BC-04), with each element at zero. */
        Object elements(int length) {
            return switch (type == null ? primitive : 0) {
                case SigCode.BOOLEAN, SigCode.I8, SigCode.U8 -> new byte[length];
                case SigCode.I16, SigCode.U16, SigCode.CHAR -> new short[length];
                case SigCode.I32, SigCode.U32 -> new int[length];
                case SigCode.I64, SigCode.U64 -> new long[length];
                case SigCode.F32 -> new float[length];
                case SigCode.F64 -> new double[length];
                default -> new Object[length];
            };
        }
    }
}
