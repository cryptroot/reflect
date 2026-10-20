package reflect.stage0.bytecode;

/** The signature codes of BC-03, as bytecode.md §4 lists them. */
public final class SigCode {
    public static final int VOID = 0x01;
    public static final int BOOLEAN = 0x02;
    public static final int I8 = 0x03;
    public static final int U8 = 0x04;
    public static final int I16 = 0x05;
    public static final int U16 = 0x06;
    public static final int CHAR = 0x07;
    public static final int I32 = 0x08;
    public static final int U32 = 0x09;
    public static final int I64 = 0x0A;
    public static final int U64 = 0x0B;
    public static final int F32 = 0x0C;
    public static final int F64 = 0x0D;
    public static final int CLASS = 0x10;
    public static final int VALUETYPE = 0x11;
    public static final int GENERICINST = 0x12;
    public static final int VAR = 0x13;
    public static final int MVAR = 0x14;
    public static final int NULLABLE = 0x15;
    public static final int ARRAY = 0x16;
    public static final int PTR = 0x17;
    public static final int BYREF = 0x18;
    public static final int WILDCARD = 0x19;
    public static final int INTERSECTION = 0x1A;
    public static final int FIELD = 0x20;
    public static final int METHOD = 0x21;
    public static final int LOCALS = 0x22;
    public static final int INSTANTIATION = 0x23;

    public static final int BYREF_REF = 0x00;
    public static final int BYREF_IN = 0x01;
    public static final int BYREF_OUT = 0x02;

    public static final int WILDCARD_ANY = 0x00;
    public static final int WILDCARD_EXTENDS = 0x01;
    public static final int WILDCARD_SUPER = 0x02;

    public static final int METHOD_STATIC = 0x01;
    public static final int METHOD_GENERIC = 0x02;
    public static final int METHOD_VARIADIC = 0x04;

    private SigCode() {
    }
}
