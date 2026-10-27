package reflect.stage0.vm;

import reflect.stage0.lib.Nullable;

/**
 * A Reflect object (BOOT-04): its run-time type and an array of slots, one per instance field, the
 * fields of the base class first (VM-02). A string is a Java String instead. Two objects are equal
 * only when they are the same Java object, as ceq.ref compares them.
 */
final class Instance {
    private final RuntimeType type;
    private final @Nullable Object[] fields;

    Instance(RuntimeType type, @Nullable Object[] fields) {
        this.type = type;
        this.fields = fields;
    }

    RuntimeType type() {
        return type;
    }

    /** The slots of the instance fields, which ldfld and stfld read and write in place. */
    @Nullable Object[] fields() {
        return fields;
    }
}
