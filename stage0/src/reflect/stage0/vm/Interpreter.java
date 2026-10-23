package reflect.stage0.vm;

import java.util.Arrays;

import reflect.stage0.bytecode.Instruction;
import reflect.stage0.bytecode.Opcode;
import reflect.stage0.lib.Nullable;

/**
 * The interpreter of VM0 (BOOT-04): one Java call per Reflect call, and a switch over the opcodes
 * of the stack bytecode. Locals and the operand stack hold Java values: Integer for i32, Long for
 * i64, Float for f32, Double for f64, and String or null for a reference.
 */
final class Interpreter {
    private Interpreter() {
    }

    /** Runs a method on its arguments, this first unless it is static; the result, or null for void. */
    static @Nullable Object invoke(Vm vm, RuntimeMethod method, Object[] arguments) {
        Natives.Native code = method.nativeCode();
        if (code != null) {
            return code.call(vm, arguments);
        }
        Instruction[] instructions = method.code();
        if (instructions == null) {
            throw new Stop("VM0 does not run " + method.name() + ", which has no body and no native");
        }
        return execute(vm, method, instructions, arguments);
    }

    private static @Nullable Object execute(Vm vm, RuntimeMethod method, Instruction[] code, Object[] arguments) {
        Object[] links = method.links();
        Object[] locals = method.zeros().clone();
        Object[] stack = new Object[method.maxStack()];
        int sp = 0;
        int pc = 0;
        try {
            while (true) {
                if (pc == code.length) {
                    throw new Stop(method.name() + ": the code ends without ret");
                }
                Instruction instruction = code[pc];
                switch (instruction.opcode()) {
                    case Opcode.NOP -> {
                    }
                    case Opcode.LDC_I4 -> stack[sp++] = ((Instruction.Value32) instruction).value();
                    case Opcode.LDC_I8 -> stack[sp++] = ((Instruction.Value64) instruction).value();
                    case Opcode.LDC_R4 -> stack[sp++] = Float.intBitsToFloat(((Instruction.Value32) instruction).value());
                    case Opcode.LDC_R8 -> stack[sp++] = Double.longBitsToDouble(((Instruction.Value64) instruction).value());
                    case Opcode.LDSTR -> {
                        if (links[pc] == null) {
                            links[pc] = vm.literal(((Instruction.Literal) instruction).text());
                        }
                        stack[sp++] = links[pc];
                    }
                    case Opcode.DUP -> {
                        stack[sp] = stack[sp - 1];
                        sp++;
                    }
                    case Opcode.POP -> stack[--sp] = null;
                    case Opcode.LDARG -> stack[sp++] = arguments[((Instruction.Index) instruction).index()];
                    case Opcode.STARG -> arguments[((Instruction.Index) instruction).index()] = stack[--sp];
                    case Opcode.LDLOC -> stack[sp++] = locals[((Instruction.Index) instruction).index()];
                    case Opcode.STLOC -> locals[((Instruction.Index) instruction).index()] = stack[--sp];
                    case Opcode.ADD_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) + b;
                    }
                    case Opcode.SUB_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) - b;
                    }
                    case Opcode.MUL_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) * b;
                    }
                    case Opcode.DIV_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) % b;
                    }
                    case Opcode.NEG_I32 -> stack[sp - 1] = -i32(stack[sp - 1]);
                    case Opcode.AND_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) & b;
                    }
                    case Opcode.OR_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) | b;
                    }
                    case Opcode.XOR_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) ^ b;
                    }
                    case Opcode.NOT_I32 -> stack[sp - 1] = ~i32(stack[sp - 1]);
                    // Java masks the count of a shift to its low 5 bits, as BC-04 does.
                    case Opcode.SHL_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) << b;
                    }
                    case Opcode.SHR_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) >> b;
                    }
                    case Opcode.SHR_UN_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) >>> b;
                    }
                    case Opcode.ADD_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) + b;
                    }
                    case Opcode.SUB_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) - b;
                    }
                    case Opcode.MUL_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) * b;
                    }
                    case Opcode.DIV_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) % b;
                    }
                    case Opcode.NEG_I64 -> stack[sp - 1] = -i64(stack[sp - 1]);
                    case Opcode.AND_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) & b;
                    }
                    case Opcode.OR_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) | b;
                    }
                    case Opcode.XOR_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) ^ b;
                    }
                    case Opcode.NOT_I64 -> stack[sp - 1] = ~i64(stack[sp - 1]);
                    // The count of a 64-bit shift is an i32 (BC-04, Q91), which Java masks to its low 6 bits.
                    case Opcode.SHL_I64 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) << b;
                    }
                    case Opcode.SHR_I64 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) >> b;
                    }
                    case Opcode.SHR_UN_I64 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) >>> b;
                    }
                    // Java's float and double arithmetic is IEEE 754 with round to nearest (NUM-04), and its
                    // remainder truncates the quotient, as BC-04 asks.
                    case Opcode.ADD_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) + b;
                    }
                    case Opcode.SUB_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) - b;
                    }
                    case Opcode.MUL_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) * b;
                    }
                    case Opcode.DIV_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) % b;
                    }
                    case Opcode.NEG_F32 -> stack[sp - 1] = -f32(stack[sp - 1]);
                    case Opcode.ADD_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) + b;
                    }
                    case Opcode.SUB_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) - b;
                    }
                    case Opcode.MUL_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) * b;
                    }
                    case Opcode.DIV_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) % b;
                    }
                    case Opcode.NEG_F64 -> stack[sp - 1] = -f64(stack[sp - 1]);
                    case Opcode.CALL -> {
                        if (links[pc] == null) {
                            RuntimeMethod callee = vm.method(method.type().module(), ((Instruction.Method) instruction).method());
                            if (!callee.isStatic()) {
                                throw new Stop(where(method, pc) + ": VM0 does not run instance calls yet");
                            }
                            links[pc] = callee;
                        }
                        RuntimeMethod callee = (RuntimeMethod) links[pc];
                        sp -= callee.arguments();
                        Object result = invoke(vm, callee, Arrays.copyOfRange(stack, sp, sp + callee.arguments()));
                        if (callee.returnsValue()) {
                            stack[sp++] = result;
                        }
                    }
                    case Opcode.RET -> {
                        return method.returnsValue() ? stack[--sp] : null;
                    }
                    default -> throw new Stop(where(method, pc) + ": " + unsupported(instruction.opcode()));
                }
                pc++;
            }
        } catch (ClassCastException | IndexOutOfBoundsException | NullPointerException e) {
            // Code that a verifier would reject (BC-07), such as a value of the wrong type or a stack deeper
            // than maxStack: VM0 has none, so it meets such code only when it runs it.
            throw new Stop(where(method, pc) + ": the code is invalid: " + e);
        }
    }

    // An i32 of the stack; boolean, byte, short and char values are i32 values there too (BC-04).
    private static int i32(Object value) {
        return (Integer) value;
    }

    private static long i64(Object value) {
        return (Long) value;
    }

    private static float f32(Object value) {
        return (Float) value;
    }

    private static double f64(Object value) {
        return (Double) value;
    }

    // The place of an instruction in messages: the method, then its index from 0.
    private static String where(RuntimeMethod method, int pc) {
        return method.name() + ": instruction " + pc;
    }

    private static String unsupported(int opcode) {
        return Opcode.isR0(opcode) ? "VM0 does not run " + Opcode.mnemonic(opcode) + " yet"
                : "VM0 runs the instructions of R0 only, and " + Opcode.mnemonic(opcode) + " is not one";
    }
}
