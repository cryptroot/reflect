package reflect.stage0.bytecode;

/**
 * An instruction of a method body (BC-04): its opcode, and the operands that the opcode's operand
 * kind gives (§5.1), one record per kind. A two-byte opcode is 0xFE00 plus its second byte.
 */
public sealed interface Instruction permits Instruction.Plain, Instruction.Value32, Instruction.Value64,
        Instruction.Literal, Instruction.Index, Instruction.Indexes, Instruction.Type, Instruction.Field,
        Instruction.Method, Instruction.Constrained {
    int opcode();

    /** An instruction without operands. */
    record Plain(int opcode) implements Instruction {
    }

    /** The value of an i4 operand, or the IEEE 754 bits of an r4 operand. */
    record Value32(int opcode, int value) implements Instruction {
    }

    /** The value of an i8 operand, or the IEEE 754 bits of an r8 operand. */
    record Value64(int opcode, long value) implements Instruction {
    }

    /** The string of a str operand, as UTF-16 code units. */
    record Literal(int opcode, String text) implements Instruction {
    }

    /** An n operand: an argument or a local, by number. */
    record Index(int opcode, int index) implements Instruction {
    }

    /** Two n operands: the call number and the argument count of syscall. */
    record Indexes(int opcode, int first, int second) implements Instruction {
    }

    /** A tok operand that names a type. */
    record Type(int opcode, TypeHandle type) implements Instruction {
    }

    /** A tok operand that names a field, of ldfld and the other field instructions. */
    record Field(int opcode, FieldHandle field) implements Instruction {
    }

    /** A tok operand that names a method, of newobj and the calls. */
    record Method(int opcode, MethodHandle method) implements Instruction {
    }

    /** The two tok operands of call.constrained: a type, then a method of its bound. */
    record Constrained(int opcode, TypeHandle type, MethodHandle method) implements Instruction {
    }
}
