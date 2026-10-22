package reflect.stage0.bytecode;

/** The opcodes of BC-04, as bytecode.md §5.1 lists them; a two-byte opcode is 0xFE00 plus its second byte. */
public final class Opcode {
    public static final int OPERAND_NONE = 0;
    public static final int OPERAND_I4 = 1;
    public static final int OPERAND_I8 = 2;
    public static final int OPERAND_R4 = 3;
    public static final int OPERAND_R8 = 4;
    public static final int OPERAND_STR = 5;
    public static final int OPERAND_TOK = 6;
    public static final int OPERAND_N = 7;
    public static final int OPERAND_OFF = 8;
    public static final int OPERAND_SWITCH = 9;
    public static final int OPERAND_TOK_TOK = 10;
    public static final int OPERAND_N_N = 11;

    /**
     * The stack effects that are not numbers (§5.1, Q77): the arguments of the method that the
     * operand names, which are its parameters after this unless its signature is STATIC; a class
     * handle, then those arguments; its parameters; a class handle, then its parameters; 1 when it
     * returns a value and 0 for void; 1 when the method that holds the instruction returns a value;
     * every value on the stack; and the second operand.
     */
    public static final int STACK_ARGS = -1;
    public static final int STACK_CLASS_ARGS = -2;
    public static final int STACK_PARAMS = -3;
    public static final int STACK_CLASS_PARAMS = -4;
    public static final int STACK_RESULT = -5;
    public static final int STACK_RETURN = -6;
    public static final int STACK_ALL = -7;
    public static final int STACK_COUNT = -8;

    public static final int PREFIX = 0xFE;

    public static final int NOP = 0x00;
    public static final int LDNULL = 0x01;
    public static final int LDC_I4 = 0x02;
    public static final int LDC_I8 = 0x03;
    public static final int LDC_R4 = 0x04;
    public static final int LDC_R8 = 0x05;
    public static final int LDSTR = 0x06;
    public static final int LDTYPE = 0x07;
    public static final int LDCONST = 0x08;
    public static final int DUP = 0x09;
    public static final int POP = 0x0A;
    public static final int LDARG = 0x10;
    public static final int STARG = 0x11;
    public static final int LDARGA = 0x12;
    public static final int LDLOC = 0x13;
    public static final int STLOC = 0x14;
    public static final int LDLOCA = 0x15;
    public static final int ADD_I32 = 0x18;
    public static final int ADD_I64 = 0x19;
    public static final int ADD_F32 = 0x1A;
    public static final int ADD_F64 = 0x1B;
    public static final int SUB_I32 = 0x1C;
    public static final int SUB_I64 = 0x1D;
    public static final int SUB_F32 = 0x1E;
    public static final int SUB_F64 = 0x1F;
    public static final int MUL_I32 = 0x20;
    public static final int MUL_I64 = 0x21;
    public static final int MUL_F32 = 0x22;
    public static final int MUL_F64 = 0x23;
    public static final int DIV_I32 = 0x24;
    public static final int DIV_I64 = 0x25;
    public static final int DIV_F32 = 0x26;
    public static final int DIV_F64 = 0x27;
    public static final int DIV_U32 = 0x28;
    public static final int DIV_U64 = 0x29;
    public static final int REM_I32 = 0x2A;
    public static final int REM_I64 = 0x2B;
    public static final int REM_F32 = 0x2C;
    public static final int REM_F64 = 0x2D;
    public static final int REM_U32 = 0x2E;
    public static final int REM_U64 = 0x2F;
    public static final int NEG_I32 = 0x30;
    public static final int NEG_I64 = 0x31;
    public static final int NEG_F32 = 0x32;
    public static final int NEG_F64 = 0x33;
    public static final int AND_I32 = 0x34;
    public static final int AND_I64 = 0x35;
    public static final int OR_I32 = 0x36;
    public static final int OR_I64 = 0x37;
    public static final int XOR_I32 = 0x38;
    public static final int XOR_I64 = 0x39;
    public static final int NOT_I32 = 0x3A;
    public static final int NOT_I64 = 0x3B;
    public static final int SHL_I32 = 0x3C;
    public static final int SHL_I64 = 0x3D;
    public static final int SHR_I32 = 0x3E;
    public static final int SHR_I64 = 0x3F;
    public static final int SHR_UN_I32 = 0x40;
    public static final int SHR_UN_I64 = 0x41;
    public static final int CEQ_I32 = 0x48;
    public static final int CEQ_I64 = 0x49;
    public static final int CEQ_F32 = 0x4A;
    public static final int CEQ_F64 = 0x4B;
    public static final int CLT_I32 = 0x4C;
    public static final int CLT_I64 = 0x4D;
    public static final int CLT_F32 = 0x4E;
    public static final int CLT_F64 = 0x4F;
    public static final int CLT_U32 = 0x50;
    public static final int CLT_U64 = 0x51;
    public static final int CLE_I32 = 0x52;
    public static final int CLE_I64 = 0x53;
    public static final int CLE_F32 = 0x54;
    public static final int CLE_F64 = 0x55;
    public static final int CLE_U32 = 0x56;
    public static final int CLE_U64 = 0x57;
    public static final int CGT_I32 = 0x58;
    public static final int CGT_I64 = 0x59;
    public static final int CGT_F32 = 0x5A;
    public static final int CGT_F64 = 0x5B;
    public static final int CGT_U32 = 0x5C;
    public static final int CGT_U64 = 0x5D;
    public static final int CGE_I32 = 0x5E;
    public static final int CGE_I64 = 0x5F;
    public static final int CGE_F32 = 0x60;
    public static final int CGE_F64 = 0x61;
    public static final int CGE_U32 = 0x62;
    public static final int CGE_U64 = 0x63;
    public static final int CEQ_REF = 0x64;
    public static final int CONV_I32_I8 = 0x68;
    public static final int CONV_I32_U8 = 0x69;
    public static final int CONV_I32_I16 = 0x6A;
    public static final int CONV_I32_U16 = 0x6B;
    public static final int CONV_I32_I64 = 0x6C;
    public static final int CONV_I32_F32 = 0x6D;
    public static final int CONV_I32_F64 = 0x6E;
    public static final int CONV_U32_I64 = 0x6F;
    public static final int CONV_U32_F32 = 0x70;
    public static final int CONV_U32_F64 = 0x71;
    public static final int CONV_I64_I32 = 0x72;
    public static final int CONV_I64_F32 = 0x73;
    public static final int CONV_I64_F64 = 0x74;
    public static final int CONV_U64_F32 = 0x75;
    public static final int CONV_U64_F64 = 0x76;
    public static final int CONV_F32_I32 = 0x77;
    public static final int CONV_F32_U32 = 0x78;
    public static final int CONV_F32_I64 = 0x79;
    public static final int CONV_F32_U64 = 0x7A;
    public static final int CONV_F32_F64 = 0x7B;
    public static final int CONV_F64_I32 = 0x7C;
    public static final int CONV_F64_U32 = 0x7D;
    public static final int CONV_F64_I64 = 0x7E;
    public static final int CONV_F64_U64 = 0x7F;
    public static final int CONV_F64_F32 = 0x80;
    public static final int BR = 0x88;
    public static final int BRTRUE = 0x89;
    public static final int BRFALSE = 0x8A;
    public static final int BRNULL = 0x8B;
    public static final int BRNONNULL = 0x8C;
    public static final int BEQ_I32 = 0x8D;
    public static final int BEQ_I64 = 0x8E;
    public static final int BEQ_F32 = 0x8F;
    public static final int BEQ_F64 = 0x90;
    public static final int BNE_I32 = 0x91;
    public static final int BNE_I64 = 0x92;
    public static final int BNE_F32 = 0x93;
    public static final int BNE_F64 = 0x94;
    public static final int BLT_I32 = 0x95;
    public static final int BLT_I64 = 0x96;
    public static final int BLT_F32 = 0x97;
    public static final int BLT_F64 = 0x98;
    public static final int BLT_U32 = 0x99;
    public static final int BLT_U64 = 0x9A;
    public static final int BLE_I32 = 0x9B;
    public static final int BLE_I64 = 0x9C;
    public static final int BLE_F32 = 0x9D;
    public static final int BLE_F64 = 0x9E;
    public static final int BLE_U32 = 0x9F;
    public static final int BLE_U64 = 0xA0;
    public static final int BGT_I32 = 0xA1;
    public static final int BGT_I64 = 0xA2;
    public static final int BGT_F32 = 0xA3;
    public static final int BGT_F64 = 0xA4;
    public static final int BGT_U32 = 0xA5;
    public static final int BGT_U64 = 0xA6;
    public static final int BGE_I32 = 0xA7;
    public static final int BGE_I64 = 0xA8;
    public static final int BGE_F32 = 0xA9;
    public static final int BGE_F64 = 0xAA;
    public static final int BGE_U32 = 0xAB;
    public static final int BGE_U64 = 0xAC;
    public static final int SWITCH = 0xAD;
    public static final int LEAVE = 0xAE;
    public static final int ENDFINALLY = 0xAF;
    public static final int RET = 0xB0;
    public static final int NEWOBJ = 0xB8;
    public static final int NEWOBJ_SVIRT = 0xB9;
    public static final int LDFLD = 0xBA;
    public static final int STFLD = 0xBB;
    public static final int LDFLDA = 0xBC;
    public static final int LDSFLD = 0xBD;
    public static final int STSFLD = 0xBE;
    public static final int LDSFLDA = 0xBF;
    public static final int GETCLASS = 0xC0;
    public static final int INSTANCEOF = 0xC1;
    public static final int CASTCLASS = 0xC2;
    public static final int CHECKALIVE = 0xC3;
    public static final int BOX = 0xC8;
    public static final int UNBOX = 0xC9;
    public static final int LDOBJ = 0xCA;
    public static final int STOBJ = 0xCB;
    public static final int CPOBJ = 0xCC;
    public static final int INITOBJ = 0xCD;
    public static final int SIZEOF = 0xCE;
    public static final int NEWARR = 0xD0;
    public static final int LDLEN = 0xD1;
    public static final int LDELEM_I8 = 0xD2;
    public static final int LDELEM_U8 = 0xD3;
    public static final int LDELEM_I16 = 0xD4;
    public static final int LDELEM_U16 = 0xD5;
    public static final int LDELEM_I32 = 0xD6;
    public static final int LDELEM_I64 = 0xD7;
    public static final int LDELEM_F32 = 0xD8;
    public static final int LDELEM_F64 = 0xD9;
    public static final int LDELEM_REF = 0xDA;
    public static final int LDELEM = 0xDB;
    public static final int STELEM_I8 = 0xDC;
    public static final int STELEM_I16 = 0xDD;
    public static final int STELEM_I32 = 0xDE;
    public static final int STELEM_I64 = 0xDF;
    public static final int STELEM_F32 = 0xE0;
    public static final int STELEM_F64 = 0xE1;
    public static final int STELEM_REF = 0xE2;
    public static final int STELEM = 0xE3;
    public static final int LDELEMA = 0xE4;
    public static final int CALL = 0xE8;
    public static final int CALL_RECV = 0xE9;
    public static final int CALLVIRT = 0xEA;
    public static final int CALL_SVIRT = 0xEB;
    public static final int CALL_CONSTRAINED = 0xEC;
    public static final int LDRECV = 0xED;
    public static final int THROW = 0xF0;
    public static final int RETHROW = 0xF1;
    public static final int BREAKPOINT = 0xFF;
    public static final int ADD_OVF_I32 = 0xFE00;
    public static final int ADD_OVF_I64 = 0xFE01;
    public static final int ADD_OVF_U32 = 0xFE02;
    public static final int ADD_OVF_U64 = 0xFE03;
    public static final int SUB_OVF_I32 = 0xFE04;
    public static final int SUB_OVF_I64 = 0xFE05;
    public static final int SUB_OVF_U32 = 0xFE06;
    public static final int SUB_OVF_U64 = 0xFE07;
    public static final int MUL_OVF_I32 = 0xFE08;
    public static final int MUL_OVF_I64 = 0xFE09;
    public static final int MUL_OVF_U32 = 0xFE0A;
    public static final int MUL_OVF_U64 = 0xFE0B;
    public static final int DIV_OVF_I32 = 0xFE0C;
    public static final int DIV_OVF_I64 = 0xFE0D;
    public static final int NEG_OVF_I32 = 0xFE0E;
    public static final int NEG_OVF_I64 = 0xFE0F;
    public static final int CONV_OVF_I32_I8 = 0xFE20;
    public static final int CONV_OVF_I32_U8 = 0xFE21;
    public static final int CONV_OVF_I32_I16 = 0xFE22;
    public static final int CONV_OVF_I32_U16 = 0xFE23;
    public static final int CONV_OVF_I32_U32 = 0xFE24;
    public static final int CONV_OVF_U32_I32 = 0xFE25;
    public static final int CONV_OVF_I64_I32 = 0xFE26;
    public static final int CONV_OVF_I64_U32 = 0xFE27;
    public static final int CONV_OVF_I64_U64 = 0xFE28;
    public static final int CONV_OVF_U64_I32 = 0xFE29;
    public static final int CONV_OVF_U64_U32 = 0xFE2A;
    public static final int CONV_OVF_U64_I64 = 0xFE2B;
    public static final int CONV_OVF_F32_I32 = 0xFE2C;
    public static final int CONV_OVF_F32_U32 = 0xFE2D;
    public static final int CONV_OVF_F32_I64 = 0xFE2E;
    public static final int CONV_OVF_F32_U64 = 0xFE2F;
    public static final int CONV_OVF_F64_I32 = 0xFE30;
    public static final int CONV_OVF_F64_U32 = 0xFE31;
    public static final int CONV_OVF_F64_I64 = 0xFE32;
    public static final int CONV_OVF_F64_U64 = 0xFE33;
    public static final int LDIND_I8 = 0xFE40;
    public static final int LDIND_U8 = 0xFE41;
    public static final int LDIND_I16 = 0xFE42;
    public static final int LDIND_U16 = 0xFE43;
    public static final int LDIND_I32 = 0xFE44;
    public static final int LDIND_I64 = 0xFE45;
    public static final int LDIND_F32 = 0xFE46;
    public static final int LDIND_F64 = 0xFE47;
    public static final int LDIND_REF = 0xFE48;
    public static final int LDIND = 0xFE49;
    public static final int STIND_I8 = 0xFE4A;
    public static final int STIND_I16 = 0xFE4B;
    public static final int STIND_I32 = 0xFE4C;
    public static final int STIND_I64 = 0xFE4D;
    public static final int STIND_F32 = 0xFE4E;
    public static final int STIND_F64 = 0xFE4F;
    public static final int STIND_REF = 0xFE50;
    public static final int STIND = 0xFE51;
    public static final int LOCALLOC = 0xFE60;
    public static final int PTRADD = 0xFE61;
    public static final int PTRDIFF = 0xFE62;
    public static final int CONV_PTR_I64 = 0xFE63;
    public static final int CONV_I64_PTR = 0xFE64;
    public static final int SYSCALL = 0xFE65;

    private static final Entry[] ENTRIES = {
        r0(NOP, "nop", OPERAND_NONE, 0, 0),
        r0(LDNULL, "ldnull", OPERAND_NONE, 0, 1),
        r0(LDC_I4, "ldc.i4", OPERAND_I4, 0, 1),
        r0(LDC_I8, "ldc.i8", OPERAND_I8, 0, 1),
        r0(LDC_R4, "ldc.r4", OPERAND_R4, 0, 1),
        r0(LDC_R8, "ldc.r8", OPERAND_R8, 0, 1),
        r0(LDSTR, "ldstr", OPERAND_STR, 0, 1),
        r0(LDTYPE, "ldtype", OPERAND_TOK, 0, 1),
        r0(LDCONST, "ldconst", OPERAND_TOK, 0, 1),
        r0(DUP, "dup", OPERAND_NONE, 1, 2),
        r0(POP, "pop", OPERAND_NONE, 1, 0),
        r0(LDARG, "ldarg", OPERAND_N, 0, 1),
        r0(STARG, "starg", OPERAND_N, 1, 0),
        r0(LDARGA, "ldarga", OPERAND_N, 0, 1),
        r0(LDLOC, "ldloc", OPERAND_N, 0, 1),
        r0(STLOC, "stloc", OPERAND_N, 1, 0),
        r0(LDLOCA, "ldloca", OPERAND_N, 0, 1),
        r0(ADD_I32, "add.i32", OPERAND_NONE, 2, 1),
        r0(ADD_I64, "add.i64", OPERAND_NONE, 2, 1),
        r0(ADD_F32, "add.f32", OPERAND_NONE, 2, 1),
        r0(ADD_F64, "add.f64", OPERAND_NONE, 2, 1),
        r0(SUB_I32, "sub.i32", OPERAND_NONE, 2, 1),
        r0(SUB_I64, "sub.i64", OPERAND_NONE, 2, 1),
        r0(SUB_F32, "sub.f32", OPERAND_NONE, 2, 1),
        r0(SUB_F64, "sub.f64", OPERAND_NONE, 2, 1),
        r0(MUL_I32, "mul.i32", OPERAND_NONE, 2, 1),
        r0(MUL_I64, "mul.i64", OPERAND_NONE, 2, 1),
        r0(MUL_F32, "mul.f32", OPERAND_NONE, 2, 1),
        r0(MUL_F64, "mul.f64", OPERAND_NONE, 2, 1),
        r0(DIV_I32, "div.i32", OPERAND_NONE, 2, 1),
        r0(DIV_I64, "div.i64", OPERAND_NONE, 2, 1),
        r0(DIV_F32, "div.f32", OPERAND_NONE, 2, 1),
        r0(DIV_F64, "div.f64", OPERAND_NONE, 2, 1),
        op(DIV_U32, "div.u32", OPERAND_NONE, 2, 1),
        op(DIV_U64, "div.u64", OPERAND_NONE, 2, 1),
        r0(REM_I32, "rem.i32", OPERAND_NONE, 2, 1),
        r0(REM_I64, "rem.i64", OPERAND_NONE, 2, 1),
        r0(REM_F32, "rem.f32", OPERAND_NONE, 2, 1),
        r0(REM_F64, "rem.f64", OPERAND_NONE, 2, 1),
        op(REM_U32, "rem.u32", OPERAND_NONE, 2, 1),
        op(REM_U64, "rem.u64", OPERAND_NONE, 2, 1),
        r0(NEG_I32, "neg.i32", OPERAND_NONE, 1, 1),
        r0(NEG_I64, "neg.i64", OPERAND_NONE, 1, 1),
        r0(NEG_F32, "neg.f32", OPERAND_NONE, 1, 1),
        r0(NEG_F64, "neg.f64", OPERAND_NONE, 1, 1),
        r0(AND_I32, "and.i32", OPERAND_NONE, 2, 1),
        r0(AND_I64, "and.i64", OPERAND_NONE, 2, 1),
        r0(OR_I32, "or.i32", OPERAND_NONE, 2, 1),
        r0(OR_I64, "or.i64", OPERAND_NONE, 2, 1),
        r0(XOR_I32, "xor.i32", OPERAND_NONE, 2, 1),
        r0(XOR_I64, "xor.i64", OPERAND_NONE, 2, 1),
        r0(NOT_I32, "not.i32", OPERAND_NONE, 1, 1),
        r0(NOT_I64, "not.i64", OPERAND_NONE, 1, 1),
        r0(SHL_I32, "shl.i32", OPERAND_NONE, 2, 1),
        r0(SHL_I64, "shl.i64", OPERAND_NONE, 2, 1),
        r0(SHR_I32, "shr.i32", OPERAND_NONE, 2, 1),
        r0(SHR_I64, "shr.i64", OPERAND_NONE, 2, 1),
        r0(SHR_UN_I32, "shr.un.i32", OPERAND_NONE, 2, 1),
        r0(SHR_UN_I64, "shr.un.i64", OPERAND_NONE, 2, 1),
        r0(CEQ_I32, "ceq.i32", OPERAND_NONE, 2, 1),
        r0(CEQ_I64, "ceq.i64", OPERAND_NONE, 2, 1),
        r0(CEQ_F32, "ceq.f32", OPERAND_NONE, 2, 1),
        r0(CEQ_F64, "ceq.f64", OPERAND_NONE, 2, 1),
        r0(CLT_I32, "clt.i32", OPERAND_NONE, 2, 1),
        r0(CLT_I64, "clt.i64", OPERAND_NONE, 2, 1),
        r0(CLT_F32, "clt.f32", OPERAND_NONE, 2, 1),
        r0(CLT_F64, "clt.f64", OPERAND_NONE, 2, 1),
        op(CLT_U32, "clt.u32", OPERAND_NONE, 2, 1),
        op(CLT_U64, "clt.u64", OPERAND_NONE, 2, 1),
        r0(CLE_I32, "cle.i32", OPERAND_NONE, 2, 1),
        r0(CLE_I64, "cle.i64", OPERAND_NONE, 2, 1),
        r0(CLE_F32, "cle.f32", OPERAND_NONE, 2, 1),
        r0(CLE_F64, "cle.f64", OPERAND_NONE, 2, 1),
        op(CLE_U32, "cle.u32", OPERAND_NONE, 2, 1),
        op(CLE_U64, "cle.u64", OPERAND_NONE, 2, 1),
        r0(CGT_I32, "cgt.i32", OPERAND_NONE, 2, 1),
        r0(CGT_I64, "cgt.i64", OPERAND_NONE, 2, 1),
        r0(CGT_F32, "cgt.f32", OPERAND_NONE, 2, 1),
        r0(CGT_F64, "cgt.f64", OPERAND_NONE, 2, 1),
        op(CGT_U32, "cgt.u32", OPERAND_NONE, 2, 1),
        op(CGT_U64, "cgt.u64", OPERAND_NONE, 2, 1),
        r0(CGE_I32, "cge.i32", OPERAND_NONE, 2, 1),
        r0(CGE_I64, "cge.i64", OPERAND_NONE, 2, 1),
        r0(CGE_F32, "cge.f32", OPERAND_NONE, 2, 1),
        r0(CGE_F64, "cge.f64", OPERAND_NONE, 2, 1),
        op(CGE_U32, "cge.u32", OPERAND_NONE, 2, 1),
        op(CGE_U64, "cge.u64", OPERAND_NONE, 2, 1),
        r0(CEQ_REF, "ceq.ref", OPERAND_NONE, 2, 1),
        r0(CONV_I32_I8, "conv.i32.i8", OPERAND_NONE, 1, 1),
        op(CONV_I32_U8, "conv.i32.u8", OPERAND_NONE, 1, 1),
        r0(CONV_I32_I16, "conv.i32.i16", OPERAND_NONE, 1, 1),
        r0(CONV_I32_U16, "conv.i32.u16", OPERAND_NONE, 1, 1),
        r0(CONV_I32_I64, "conv.i32.i64", OPERAND_NONE, 1, 1),
        r0(CONV_I32_F32, "conv.i32.f32", OPERAND_NONE, 1, 1),
        r0(CONV_I32_F64, "conv.i32.f64", OPERAND_NONE, 1, 1),
        op(CONV_U32_I64, "conv.u32.i64", OPERAND_NONE, 1, 1),
        op(CONV_U32_F32, "conv.u32.f32", OPERAND_NONE, 1, 1),
        op(CONV_U32_F64, "conv.u32.f64", OPERAND_NONE, 1, 1),
        r0(CONV_I64_I32, "conv.i64.i32", OPERAND_NONE, 1, 1),
        r0(CONV_I64_F32, "conv.i64.f32", OPERAND_NONE, 1, 1),
        r0(CONV_I64_F64, "conv.i64.f64", OPERAND_NONE, 1, 1),
        op(CONV_U64_F32, "conv.u64.f32", OPERAND_NONE, 1, 1),
        op(CONV_U64_F64, "conv.u64.f64", OPERAND_NONE, 1, 1),
        r0(CONV_F32_I32, "conv.f32.i32", OPERAND_NONE, 1, 1),
        op(CONV_F32_U32, "conv.f32.u32", OPERAND_NONE, 1, 1),
        r0(CONV_F32_I64, "conv.f32.i64", OPERAND_NONE, 1, 1),
        op(CONV_F32_U64, "conv.f32.u64", OPERAND_NONE, 1, 1),
        r0(CONV_F32_F64, "conv.f32.f64", OPERAND_NONE, 1, 1),
        r0(CONV_F64_I32, "conv.f64.i32", OPERAND_NONE, 1, 1),
        op(CONV_F64_U32, "conv.f64.u32", OPERAND_NONE, 1, 1),
        r0(CONV_F64_I64, "conv.f64.i64", OPERAND_NONE, 1, 1),
        op(CONV_F64_U64, "conv.f64.u64", OPERAND_NONE, 1, 1),
        r0(CONV_F64_F32, "conv.f64.f32", OPERAND_NONE, 1, 1),
        r0(BR, "br", OPERAND_OFF, 0, 0),
        r0(BRTRUE, "brtrue", OPERAND_OFF, 1, 0),
        r0(BRFALSE, "brfalse", OPERAND_OFF, 1, 0),
        r0(BRNULL, "brnull", OPERAND_OFF, 1, 0),
        r0(BRNONNULL, "brnonnull", OPERAND_OFF, 1, 0),
        r0(BEQ_I32, "beq.i32", OPERAND_OFF, 2, 0),
        r0(BEQ_I64, "beq.i64", OPERAND_OFF, 2, 0),
        r0(BEQ_F32, "beq.f32", OPERAND_OFF, 2, 0),
        r0(BEQ_F64, "beq.f64", OPERAND_OFF, 2, 0),
        r0(BNE_I32, "bne.i32", OPERAND_OFF, 2, 0),
        r0(BNE_I64, "bne.i64", OPERAND_OFF, 2, 0),
        r0(BNE_F32, "bne.f32", OPERAND_OFF, 2, 0),
        r0(BNE_F64, "bne.f64", OPERAND_OFF, 2, 0),
        r0(BLT_I32, "blt.i32", OPERAND_OFF, 2, 0),
        r0(BLT_I64, "blt.i64", OPERAND_OFF, 2, 0),
        r0(BLT_F32, "blt.f32", OPERAND_OFF, 2, 0),
        r0(BLT_F64, "blt.f64", OPERAND_OFF, 2, 0),
        op(BLT_U32, "blt.u32", OPERAND_OFF, 2, 0),
        op(BLT_U64, "blt.u64", OPERAND_OFF, 2, 0),
        r0(BLE_I32, "ble.i32", OPERAND_OFF, 2, 0),
        r0(BLE_I64, "ble.i64", OPERAND_OFF, 2, 0),
        r0(BLE_F32, "ble.f32", OPERAND_OFF, 2, 0),
        r0(BLE_F64, "ble.f64", OPERAND_OFF, 2, 0),
        op(BLE_U32, "ble.u32", OPERAND_OFF, 2, 0),
        op(BLE_U64, "ble.u64", OPERAND_OFF, 2, 0),
        r0(BGT_I32, "bgt.i32", OPERAND_OFF, 2, 0),
        r0(BGT_I64, "bgt.i64", OPERAND_OFF, 2, 0),
        r0(BGT_F32, "bgt.f32", OPERAND_OFF, 2, 0),
        r0(BGT_F64, "bgt.f64", OPERAND_OFF, 2, 0),
        op(BGT_U32, "bgt.u32", OPERAND_OFF, 2, 0),
        op(BGT_U64, "bgt.u64", OPERAND_OFF, 2, 0),
        r0(BGE_I32, "bge.i32", OPERAND_OFF, 2, 0),
        r0(BGE_I64, "bge.i64", OPERAND_OFF, 2, 0),
        r0(BGE_F32, "bge.f32", OPERAND_OFF, 2, 0),
        r0(BGE_F64, "bge.f64", OPERAND_OFF, 2, 0),
        op(BGE_U32, "bge.u32", OPERAND_OFF, 2, 0),
        op(BGE_U64, "bge.u64", OPERAND_OFF, 2, 0),
        r0(SWITCH, "switch", OPERAND_SWITCH, 1, 0),
        r0(LEAVE, "leave", OPERAND_OFF, STACK_ALL, 0),
        r0(ENDFINALLY, "endfinally", OPERAND_NONE, STACK_ALL, 0),
        r0(RET, "ret", OPERAND_NONE, STACK_RETURN, 0),
        r0(NEWOBJ, "newobj", OPERAND_TOK, STACK_PARAMS, 1),
        op(NEWOBJ_SVIRT, "newobj.svirt", OPERAND_TOK, STACK_CLASS_PARAMS, 1),
        r0(LDFLD, "ldfld", OPERAND_TOK, 1, 1),
        r0(STFLD, "stfld", OPERAND_TOK, 2, 0),
        op(LDFLDA, "ldflda", OPERAND_TOK, 1, 1),
        r0(LDSFLD, "ldsfld", OPERAND_TOK, 0, 1),
        r0(STSFLD, "stsfld", OPERAND_TOK, 1, 0),
        op(LDSFLDA, "ldsflda", OPERAND_TOK, 0, 1),
        r0(GETCLASS, "getclass", OPERAND_NONE, 1, 1),
        r0(INSTANCEOF, "instanceof", OPERAND_TOK, 1, 1),
        r0(CASTCLASS, "castclass", OPERAND_TOK, 1, 1),
        op(CHECKALIVE, "checkalive", OPERAND_NONE, 1, 1),
        r0(BOX, "box", OPERAND_TOK, 1, 1),
        r0(UNBOX, "unbox", OPERAND_TOK, 1, 1),
        op(LDOBJ, "ldobj", OPERAND_TOK, 1, 1),
        op(STOBJ, "stobj", OPERAND_TOK, 2, 0),
        op(CPOBJ, "cpobj", OPERAND_TOK, 2, 0),
        op(INITOBJ, "initobj", OPERAND_TOK, 1, 0),
        op(SIZEOF, "sizeof", OPERAND_TOK, 0, 1),
        r0(NEWARR, "newarr", OPERAND_TOK, 1, 1),
        r0(LDLEN, "ldlen", OPERAND_NONE, 1, 1),
        r0(LDELEM_I8, "ldelem.i8", OPERAND_NONE, 2, 1),
        r0(LDELEM_U8, "ldelem.u8", OPERAND_NONE, 2, 1),
        r0(LDELEM_I16, "ldelem.i16", OPERAND_NONE, 2, 1),
        r0(LDELEM_U16, "ldelem.u16", OPERAND_NONE, 2, 1),
        r0(LDELEM_I32, "ldelem.i32", OPERAND_NONE, 2, 1),
        r0(LDELEM_I64, "ldelem.i64", OPERAND_NONE, 2, 1),
        r0(LDELEM_F32, "ldelem.f32", OPERAND_NONE, 2, 1),
        r0(LDELEM_F64, "ldelem.f64", OPERAND_NONE, 2, 1),
        r0(LDELEM_REF, "ldelem.ref", OPERAND_NONE, 2, 1),
        r0(LDELEM, "ldelem", OPERAND_TOK, 2, 1),
        r0(STELEM_I8, "stelem.i8", OPERAND_NONE, 3, 0),
        r0(STELEM_I16, "stelem.i16", OPERAND_NONE, 3, 0),
        r0(STELEM_I32, "stelem.i32", OPERAND_NONE, 3, 0),
        r0(STELEM_I64, "stelem.i64", OPERAND_NONE, 3, 0),
        r0(STELEM_F32, "stelem.f32", OPERAND_NONE, 3, 0),
        r0(STELEM_F64, "stelem.f64", OPERAND_NONE, 3, 0),
        r0(STELEM_REF, "stelem.ref", OPERAND_NONE, 3, 0),
        r0(STELEM, "stelem", OPERAND_TOK, 3, 0),
        op(LDELEMA, "ldelema", OPERAND_TOK, 2, 1),
        r0(CALL, "call", OPERAND_TOK, STACK_ARGS, STACK_RESULT),
        op(CALL_RECV, "call.recv", OPERAND_TOK, STACK_CLASS_ARGS, STACK_RESULT),
        r0(CALLVIRT, "callvirt", OPERAND_TOK, STACK_ARGS, STACK_RESULT),
        op(CALL_SVIRT, "call.svirt", OPERAND_TOK, STACK_CLASS_ARGS, STACK_RESULT),
        r0(CALL_CONSTRAINED, "call.constrained", OPERAND_TOK_TOK, STACK_ARGS, STACK_RESULT),
        op(LDRECV, "ldrecv", OPERAND_NONE, 0, 1),
        r0(THROW, "throw", OPERAND_NONE, 1, 0),
        r0(RETHROW, "rethrow", OPERAND_NONE, 0, 0),
        op(BREAKPOINT, "breakpoint", OPERAND_NONE, 0, 0),
        op(ADD_OVF_I32, "add.ovf.i32", OPERAND_NONE, 2, 1),
        op(ADD_OVF_I64, "add.ovf.i64", OPERAND_NONE, 2, 1),
        op(ADD_OVF_U32, "add.ovf.u32", OPERAND_NONE, 2, 1),
        op(ADD_OVF_U64, "add.ovf.u64", OPERAND_NONE, 2, 1),
        op(SUB_OVF_I32, "sub.ovf.i32", OPERAND_NONE, 2, 1),
        op(SUB_OVF_I64, "sub.ovf.i64", OPERAND_NONE, 2, 1),
        op(SUB_OVF_U32, "sub.ovf.u32", OPERAND_NONE, 2, 1),
        op(SUB_OVF_U64, "sub.ovf.u64", OPERAND_NONE, 2, 1),
        op(MUL_OVF_I32, "mul.ovf.i32", OPERAND_NONE, 2, 1),
        op(MUL_OVF_I64, "mul.ovf.i64", OPERAND_NONE, 2, 1),
        op(MUL_OVF_U32, "mul.ovf.u32", OPERAND_NONE, 2, 1),
        op(MUL_OVF_U64, "mul.ovf.u64", OPERAND_NONE, 2, 1),
        op(DIV_OVF_I32, "div.ovf.i32", OPERAND_NONE, 2, 1),
        op(DIV_OVF_I64, "div.ovf.i64", OPERAND_NONE, 2, 1),
        op(NEG_OVF_I32, "neg.ovf.i32", OPERAND_NONE, 1, 1),
        op(NEG_OVF_I64, "neg.ovf.i64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I32_I8, "conv.ovf.i32.i8", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I32_U8, "conv.ovf.i32.u8", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I32_I16, "conv.ovf.i32.i16", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I32_U16, "conv.ovf.i32.u16", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I32_U32, "conv.ovf.i32.u32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_U32_I32, "conv.ovf.u32.i32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I64_I32, "conv.ovf.i64.i32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I64_U32, "conv.ovf.i64.u32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_I64_U64, "conv.ovf.i64.u64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_U64_I32, "conv.ovf.u64.i32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_U64_U32, "conv.ovf.u64.u32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_U64_I64, "conv.ovf.u64.i64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F32_I32, "conv.ovf.f32.i32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F32_U32, "conv.ovf.f32.u32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F32_I64, "conv.ovf.f32.i64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F32_U64, "conv.ovf.f32.u64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F64_I32, "conv.ovf.f64.i32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F64_U32, "conv.ovf.f64.u32", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F64_I64, "conv.ovf.f64.i64", OPERAND_NONE, 1, 1),
        op(CONV_OVF_F64_U64, "conv.ovf.f64.u64", OPERAND_NONE, 1, 1),
        op(LDIND_I8, "ldind.i8", OPERAND_NONE, 1, 1),
        op(LDIND_U8, "ldind.u8", OPERAND_NONE, 1, 1),
        op(LDIND_I16, "ldind.i16", OPERAND_NONE, 1, 1),
        op(LDIND_U16, "ldind.u16", OPERAND_NONE, 1, 1),
        op(LDIND_I32, "ldind.i32", OPERAND_NONE, 1, 1),
        op(LDIND_I64, "ldind.i64", OPERAND_NONE, 1, 1),
        op(LDIND_F32, "ldind.f32", OPERAND_NONE, 1, 1),
        op(LDIND_F64, "ldind.f64", OPERAND_NONE, 1, 1),
        op(LDIND_REF, "ldind.ref", OPERAND_NONE, 1, 1),
        op(LDIND, "ldind", OPERAND_TOK, 1, 1),
        op(STIND_I8, "stind.i8", OPERAND_NONE, 2, 0),
        op(STIND_I16, "stind.i16", OPERAND_NONE, 2, 0),
        op(STIND_I32, "stind.i32", OPERAND_NONE, 2, 0),
        op(STIND_I64, "stind.i64", OPERAND_NONE, 2, 0),
        op(STIND_F32, "stind.f32", OPERAND_NONE, 2, 0),
        op(STIND_F64, "stind.f64", OPERAND_NONE, 2, 0),
        op(STIND_REF, "stind.ref", OPERAND_NONE, 2, 0),
        op(STIND, "stind", OPERAND_TOK, 2, 0),
        op(LOCALLOC, "localloc", OPERAND_NONE, 1, 1),
        op(PTRADD, "ptradd", OPERAND_TOK, 2, 1),
        op(PTRDIFF, "ptrdiff", OPERAND_TOK, 2, 1),
        op(CONV_PTR_I64, "conv.ptr.i64", OPERAND_NONE, 1, 1),
        op(CONV_I64_PTR, "conv.i64.ptr", OPERAND_NONE, 1, 1),
        op(SYSCALL, "syscall", OPERAND_N_N, STACK_COUNT, 1),
    };
    private static final int[] INDEX = index();

    private Opcode() {
    }

    public static boolean isDefined(int code) {
        int slot = slot(code);
        return slot >= 0 && INDEX[slot] != 0;
    }

    public static String mnemonic(int code) {
        return entry(code).mnemonic();
    }

    /** The opcode of the instruction with this mnemonic, or -1 when none has it. */
    public static int code(String mnemonic) {
        for (Entry entry : ENTRIES) {
            if (entry.mnemonic().equals(mnemonic)) {
                return entry.code();
            }
        }
        return -1;
    }

    public static int operands(int code) {
        return entry(code).operands();
    }

    public static boolean isR0(int code) {
        return entry(code).inR0();
    }

    /** The number of values that the instruction pops, or a STACK_ constant. */
    public static int pops(int code) {
        return entry(code).pops();
    }

    /** The number of values that the instruction pushes, or STACK_RESULT. */
    public static int pushes(int code) {
        return entry(code).pushes();
    }

    private static Entry entry(int code) {
        if (!isDefined(code)) {
            throw new IllegalArgumentException("Undefined opcode " + code);
        }
        return ENTRIES[INDEX[slot(code)] - 1];
    }

    // One-byte opcodes take slots 0x000 to 0x0FF, two-byte opcodes 0x100 to 0x1FF.
    private static int slot(int code) {
        if (code >= 0 && code <= 0xFF && code != PREFIX) {
            return code;
        }
        if (code >= (PREFIX << 8) && code <= ((PREFIX << 8) | 0xFF)) {
            return 0x100 | (code & 0xFF);
        }
        return -1;
    }

    private static int[] index() {
        int[] index = new int[0x200];
        for (int i = 0; i < ENTRIES.length; i++) {
            int slot = slot(ENTRIES[i].code());
            if (slot < 0 || index[slot] != 0) {
                throw new IllegalStateException("Invalid or repeated opcode for " + ENTRIES[i].mnemonic());
            }
            index[slot] = i + 1;
        }
        return index;
    }

    private static Entry r0(int code, String mnemonic, int operands, int pops, int pushes) {
        return new Entry(code, mnemonic, operands, pops, pushes, true);
    }

    private static Entry op(int code, String mnemonic, int operands, int pops, int pushes) {
        return new Entry(code, mnemonic, operands, pops, pushes, false);
    }

    private record Entry(int code, String mnemonic, int operands, int pops, int pushes, boolean inR0) {
    }
}
