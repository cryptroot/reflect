package reflect.stage0.bytecode;

/** The flags and kinds of table columns and exception regions, as bytecode.md §3.1 and BC-18 list them. */
public final class Flags {
    public static final int PUBLIC = 0x01;
    public static final int PROTECTED = 0x02;
    public static final int PRIVATE = 0x04;

    public static final int KIND_CLASS = 0x00;
    public static final int KIND_INTERFACE = 0x01;
    public static final int KIND_STRUCT = 0x02;
    public static final int KIND_REF_STRUCT = 0x03;
    public static final int KIND_RECORD = 0x04;
    public static final int KIND_RECORD_STRUCT = 0x05;
    public static final int KIND_ENUM = 0x06;
    public static final int KIND_ANNOTATION = 0x07;

    public static final int TYPE_ABSTRACT = 0x08;
    public static final int TYPE_FINAL = 0x10;
    public static final int TYPE_SEALED = 0x20;
    public static final int TYPE_UNSEALED = 0x40;

    public static final int FIELD_STATIC = 0x08;
    public static final int FIELD_FINAL = 0x10;
    public static final int FIELD_CONST = 0x20;
    public static final int FIELD_BACKING = 0x40;

    public static final int METHOD_STATIC = 0x0008;
    public static final int METHOD_VIRTUAL = 0x0010;
    public static final int METHOD_ABSTRACT = 0x0020;
    public static final int METHOD_FINAL = 0x0040;
    public static final int METHOD_CONSTRUCTOR = 0x0080;
    public static final int METHOD_NATIVE = 0x0100;
    public static final int METHOD_EXTERN = 0x0200;
    public static final int METHOD_UNSAFE = 0x0400;
    public static final int METHOD_MUTATING = 0x0800;
    public static final int METHOD_CONST = 0x1000;
    public static final int METHOD_PRECOMPILE = 0x2000;
    public static final int METHOD_SYNTHESIZED = 0x4000;

    public static final int PARAM_SCOPED = 0x01;
    public static final int PARAM_THIS = 0x02;

    public static final int REQUIRES_TRANSITIVE = 0x01;
    public static final int REQUIRES_STATIC = 0x02;

    public static final int EXPORTS = 0x00;
    public static final int OPENS = 0x01;

    public static final int GENERIC_NONE = 0x00;
    public static final int GENERIC_CLASS = 0x01;
    public static final int GENERIC_STRUCT = 0x02;

    public static final int REGION_CATCH = 0x00;
    public static final int REGION_FINALLY = 0x01;

    private Flags() {
    }
}
