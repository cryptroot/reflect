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
 * base class (Q96), the slots of its instance fields, those of the base class first (VM-02), and the
 * methods and fields that VM0 has linked.
 */
final class RuntimeType {
    private final RuntimeModule module;
    private final TypeDef definition;
    private final @Nullable RuntimeType base;
    private final Map<MethodDef, RuntimeMethod> methods = new IdentityHashMap<>();
    private final Map<FieldDef, RuntimeField> fields = new IdentityHashMap<>();
    private final @Nullable Object[] zeros;

    RuntimeType(RuntimeModule module, TypeDef definition, @Nullable RuntimeType base) {
        this.module = module;
        this.definition = definition;
        this.base = base;
        int inherited = base == null ? 0 : base.zeros.length;
        int own = 0;
        for (FieldDef field : definition.fields()) {
            own += (field.flags() & Flags.FIELD_STATIC) == 0 ? 1 : 0;
        }
        zeros = new Object[inherited + own];
        if (base != null) {
            System.arraycopy(base.zeros, 0, zeros, 0, inherited);
        }
        int slot = inherited;
        for (FieldDef field : definition.fields()) {
            if ((field.flags() & Flags.FIELD_STATIC) == 0) {
                if (Values.valueType(field.type())) {
                    throw new Stop(name() + "::" + field.name() + ": VM0 does not run fields of value types yet");
                }
                zeros[slot] = Values.zero(field.type());
                fields.put(field, new RuntimeField(this, field, slot++));
            }
        }
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

    /** The run-time method of a method that the type lists. */
    RuntimeMethod method(MethodDef method) {
        return methods.computeIfAbsent(method, definition -> new RuntimeMethod(this, definition));
    }

    /** The run-time field of a field that the type lists; a static field has no slot in an Instance. */
    RuntimeField field(FieldDef field) {
        return fields.computeIfAbsent(field, definition -> new RuntimeField(this, definition, -1));
    }
}
