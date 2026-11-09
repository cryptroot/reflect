package reflect.stage0.vm;

import java.util.Arrays;

import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.Instruction;
import reflect.stage0.bytecode.Label;
import reflect.stage0.bytecode.MethodHandle;
import reflect.stage0.bytecode.Opcode;
import reflect.stage0.bytecode.TypeHandle;
import reflect.stage0.bytecode.TypeSig;
import reflect.stage0.bytecode.TypeSpec;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * The interpreter of VM0 (BOOT-04): one Java call per Reflect call, and a switch over the opcodes
 * of the stack bytecode. Locals and the operand stack hold Java values: Integer for i32, Long for
 * i64, Float for f32, Double for f64, and an Instance, a String or null for a reference.
 */
final class Interpreter {
    // The canonical NaN of each format (NUM-04, MA-01, Q92): the positive quiet NaN with payload 0. Java's
    // arithmetic gives other NaNs, such as 0xFFF8000000000000 for 0.0 / 0.0 on x86-64 (sketchpad D.6).
    private static final float NAN32 = Float.intBitsToFloat(0x7FC00000);
    private static final double NAN64 = Double.longBitsToDouble(0x7FF8000000000000L);

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
            if (method.isAbstract()) {
                // Until v0.0.8, a fault (Q101).
                throw new Fault(Fault.ABSTRACT_MEMBER_ERROR, "a call reaches the abstract method " + method.signature());
            }
            throw new Stop("VM0 does not run " + method.name() + ", which has no body and no native");
        }
        return execute(vm, method, instructions, arguments);
    }

    /**
     * The method that a call of the target runs on the receiver (VM-04, BC-06): for a method of a class,
     * the method in the target's slot of the receiver's run-time type, and for a method of an interface,
     * the implementation that the interface table of that type gives; the target itself when it has no
     * slot. An abstract method there is an AbstractMemberError (Q101).
     */
    static RuntimeMethod dispatch(Vm vm, RuntimeMethod target, Object receiver) {
        if (!target.hasSlot()) {
            return target;
        }
        RuntimeType type = vm.typeOf(receiver);
        RuntimeMethod found = target.type().isInterface() ? type.implementation(vm, target)
                : type.implementation(target);
        if (found.isAbstract()) {
            throw new Fault(Fault.ABSTRACT_MEMBER_ERROR, type.name() + " does not implement " + found.signature());
        }
        return found;
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
                vm.count(instruction.opcode());
                switch (instruction.opcode()) {
                    case Opcode.NOP -> {
                    }
                    case Opcode.LDNULL -> stack[sp++] = null;
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
                        int b = divisor(i32(stack[--sp]));
                        stack[sp - 1] = i32(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_I32 -> {
                        int b = divisor(i32(stack[--sp]));
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
                        long b = divisor(i64(stack[--sp]));
                        stack[sp - 1] = i64(stack[sp - 1]) / b;
                    }
                    case Opcode.REM_I64 -> {
                        long b = divisor(i64(stack[--sp]));
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
                    // remainder truncates the quotient, as BC-04 asks. A NaN result is the canonical NaN
                    // (MA-01, Q92), and neg changes only the sign bit.
                    case Opcode.ADD_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = canonical(f32(stack[sp - 1]) + b);
                    }
                    case Opcode.SUB_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = canonical(f32(stack[sp - 1]) - b);
                    }
                    case Opcode.MUL_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = canonical(f32(stack[sp - 1]) * b);
                    }
                    case Opcode.DIV_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = canonical(f32(stack[sp - 1]) / b);
                    }
                    case Opcode.REM_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = canonical(f32(stack[sp - 1]) % b);
                    }
                    case Opcode.NEG_F32 -> stack[sp - 1] = Float.intBitsToFloat(Float.floatToRawIntBits(f32(stack[sp - 1]))
                            ^ 0x80000000);
                    case Opcode.ADD_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = canonical(f64(stack[sp - 1]) + b);
                    }
                    case Opcode.SUB_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = canonical(f64(stack[sp - 1]) - b);
                    }
                    case Opcode.MUL_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = canonical(f64(stack[sp - 1]) * b);
                    }
                    case Opcode.DIV_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = canonical(f64(stack[sp - 1]) / b);
                    }
                    case Opcode.REM_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = canonical(f64(stack[sp - 1]) % b);
                    }
                    case Opcode.NEG_F64 -> stack[sp - 1] = Double.longBitsToDouble(Double.doubleToRawLongBits(
                            f64(stack[sp - 1])) ^ 0x8000000000000000L);
                    // A comparison pushes the i32 1 or 0. Java compares floats per IEEE 754, so a NaN is
                    // unordered, and 0.0 equals -0.0 (NUM-04).
                    case Opcode.CEQ_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) == b ? 1 : 0;
                    }
                    case Opcode.CEQ_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) == b ? 1 : 0;
                    }
                    case Opcode.CEQ_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) == b ? 1 : 0;
                    }
                    case Opcode.CEQ_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) == b ? 1 : 0;
                    }
                    case Opcode.CLT_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) < b ? 1 : 0;
                    }
                    case Opcode.CLT_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) < b ? 1 : 0;
                    }
                    case Opcode.CLT_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) < b ? 1 : 0;
                    }
                    case Opcode.CLT_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) < b ? 1 : 0;
                    }
                    case Opcode.CLE_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) <= b ? 1 : 0;
                    }
                    case Opcode.CLE_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) <= b ? 1 : 0;
                    }
                    case Opcode.CLE_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) <= b ? 1 : 0;
                    }
                    case Opcode.CLE_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) <= b ? 1 : 0;
                    }
                    case Opcode.CGT_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) > b ? 1 : 0;
                    }
                    case Opcode.CGT_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) > b ? 1 : 0;
                    }
                    case Opcode.CGT_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) > b ? 1 : 0;
                    }
                    case Opcode.CGT_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) > b ? 1 : 0;
                    }
                    case Opcode.CGE_I32 -> {
                        int b = i32(stack[--sp]);
                        stack[sp - 1] = i32(stack[sp - 1]) >= b ? 1 : 0;
                    }
                    case Opcode.CGE_I64 -> {
                        long b = i64(stack[--sp]);
                        stack[sp - 1] = i64(stack[sp - 1]) >= b ? 1 : 0;
                    }
                    case Opcode.CGE_F32 -> {
                        float b = f32(stack[--sp]);
                        stack[sp - 1] = f32(stack[sp - 1]) >= b ? 1 : 0;
                    }
                    case Opcode.CGE_F64 -> {
                        double b = f64(stack[--sp]);
                        stack[sp - 1] = f64(stack[sp - 1]) >= b ? 1 : 0;
                    }
                    // ceq.ref compares identity (OPR-04); two boxes compare by class and contents from v0.0.7 (DAT-09).
                    case Opcode.CEQ_REF -> {
                        Object b = reference(stack[--sp], method, pc);
                        stack[sp - 1] = reference(stack[sp - 1], method, pc) == b ? 1 : 0;
                    }
                    // Java's casts give the conversions of BC-04: integers wrap, an integer becomes the nearest
                    // float, and a float becomes an integer by truncation, saturated, with NaN as 0 (NUM-03).
                    // Between f32 and f64, a NaN becomes the canonical NaN (MA-01, Q92).
                    case Opcode.CONV_I32_I8 -> stack[sp - 1] = (int) (byte) i32(stack[sp - 1]);
                    case Opcode.CONV_I32_I16 -> stack[sp - 1] = (int) (short) i32(stack[sp - 1]);
                    case Opcode.CONV_I32_U16 -> stack[sp - 1] = (int) (char) i32(stack[sp - 1]);
                    case Opcode.CONV_I32_I64 -> stack[sp - 1] = (long) i32(stack[sp - 1]);
                    case Opcode.CONV_I32_F32 -> stack[sp - 1] = (float) i32(stack[sp - 1]);
                    case Opcode.CONV_I32_F64 -> stack[sp - 1] = (double) i32(stack[sp - 1]);
                    case Opcode.CONV_I64_I32 -> stack[sp - 1] = (int) i64(stack[sp - 1]);
                    case Opcode.CONV_I64_F32 -> stack[sp - 1] = (float) i64(stack[sp - 1]);
                    case Opcode.CONV_I64_F64 -> stack[sp - 1] = (double) i64(stack[sp - 1]);
                    case Opcode.CONV_F32_I32 -> stack[sp - 1] = (int) f32(stack[sp - 1]);
                    case Opcode.CONV_F32_I64 -> stack[sp - 1] = (long) f32(stack[sp - 1]);
                    case Opcode.CONV_F32_F64 -> stack[sp - 1] = canonical((double) f32(stack[sp - 1]));
                    case Opcode.CONV_F64_I32 -> stack[sp - 1] = (int) f64(stack[sp - 1]);
                    case Opcode.CONV_F64_I64 -> stack[sp - 1] = (long) f64(stack[sp - 1]);
                    case Opcode.CONV_F64_F32 -> stack[sp - 1] = canonical((float) f64(stack[sp - 1]));
                    // A branch goes to the instruction that its label marks; the others go on (BC-04).
                    case Opcode.BR -> {
                        pc = target(instruction);
                        continue;
                    }
                    case Opcode.BRTRUE, Opcode.BRFALSE -> {
                        if ((i32(stack[--sp]) != 0) == (instruction.opcode() == Opcode.BRTRUE)) {
                            pc = target(instruction);
                            continue;
                        }
                    }
                    case Opcode.BRNULL, Opcode.BRNONNULL -> {
                        if ((reference(stack[--sp], method, pc) == null) == (instruction.opcode() == Opcode.BRNULL)) {
                            pc = target(instruction);
                            continue;
                        }
                    }
                    case Opcode.BEQ_I32, Opcode.BEQ_I64, Opcode.BEQ_F32, Opcode.BEQ_F64, Opcode.BNE_I32, Opcode.BNE_I64,
                            Opcode.BNE_F32, Opcode.BNE_F64, Opcode.BLT_I32, Opcode.BLT_I64, Opcode.BLT_F32, Opcode.BLT_F64,
                            Opcode.BLE_I32, Opcode.BLE_I64, Opcode.BLE_F32, Opcode.BLE_F64, Opcode.BGT_I32, Opcode.BGT_I64,
                            Opcode.BGT_F32, Opcode.BGT_F64, Opcode.BGE_I32, Opcode.BGE_I64, Opcode.BGE_F32, Opcode.BGE_F64 -> {
                        Object b = stack[--sp];
                        if (taken(instruction.opcode(), stack[--sp], b)) {
                            pc = target(instruction);
                            continue;
                        }
                    }
                    // switch goes to the target of its value, or on when the value has none (BC-04).
                    case Opcode.SWITCH -> {
                        int value = i32(stack[--sp]);
                        List<Label> targets = ((Instruction.Switch) instruction).targets();
                        if (value >= 0 && value < targets.size()) {
                            pc = targets.get(value).index();
                            continue;
                        }
                    }
                    case Opcode.CALL -> {
                        if (links[pc] == null) {
                            links[pc] = vm.method(method.type().module(), ((Instruction.Method) instruction).method());
                        }
                        RuntimeMethod callee = (RuntimeMethod) links[pc];
                        sp -= callee.arguments();
                        Object[] values = Arrays.copyOfRange(stack, sp, sp + callee.arguments());
                        if (callee.isStatic()) {
                            // A static call is an active use of its class (VM-07).
                            callee.type().initialize(vm);
                        } else {
                            // A call of an instance method checks its receiver, as the JVM's invokespecial does (Q100).
                            receiver(vm, values[0], callee.type(), "call " + callee.signature(), method, pc);
                        }
                        Object result = invoke(vm, callee, values);
                        if (callee.returnsValue()) {
                            stack[sp++] = result;
                        }
                    }
                    // callvirt checks its receiver, then calls the method in the slot or the interface table of the
                    // receiver's run-time type, or the method that it names when that has no slot (BC-06, VM-04).
                    case Opcode.CALLVIRT -> {
                        if (links[pc] == null) {
                            links[pc] = virtual(vm, method, ((Instruction.Method) instruction).method());
                        }
                        RuntimeMethod target = (RuntimeMethod) links[pc];
                        sp -= target.arguments();
                        Object[] values = Arrays.copyOfRange(stack, sp, sp + target.arguments());
                        receiver(vm, values[0], target.type(), "callvirt " + target.signature(), method, pc);
                        Object result = invoke(vm, dispatch(vm, target, values[0]), values);
                        if (target.returnsValue()) {
                            stack[sp++] = result;
                        }
                    }
                    // newobj initializes the constructor's class (VM-07), makes an object of it with every field at
                    // zero, and runs the constructor on it, after the parameters (BC-04).
                    case Opcode.NEWOBJ -> {
                        if (links[pc] == null) {
                            links[pc] = constructor(vm, method, pc, ((Instruction.Method) instruction).method());
                        }
                        RuntimeMethod constructor = (RuntimeMethod) links[pc];
                        Object[] values = new Object[constructor.arguments()];
                        sp -= values.length - 1;
                        System.arraycopy(stack, sp, values, 1, values.length - 1);
                        constructor.type().initialize(vm);
                        Instance object = constructor.type().allocate();
                        values[0] = object;
                        invoke(vm, constructor, values);
                        stack[sp++] = object;
                    }
                    case Opcode.LDFLD -> {
                        RuntimeField field = field(vm, method, pc, instruction, false);
                        Instance object = (Instance) receiver(vm, stack[sp - 1], field.owner(), "ldfld " + field.name(),
                                method, pc);
                        stack[sp - 1] = object.fields()[field.slot()];
                    }
                    case Opcode.STFLD -> {
                        RuntimeField field = field(vm, method, pc, instruction, false);
                        Object value = stack[--sp];
                        Instance object = (Instance) receiver(vm, stack[--sp], field.owner(), "stfld " + field.name(),
                                method, pc);
                        object.fields()[field.slot()] = field.stored(value);
                    }
                    // An access to a static field is an active use of its class (VM-07).
                    case Opcode.LDSFLD -> {
                        RuntimeField field = field(vm, method, pc, instruction, true);
                        field.owner().initialize(vm);
                        stack[sp++] = field.owner().statics()[field.slot()];
                    }
                    case Opcode.STSFLD -> {
                        RuntimeField field = field(vm, method, pc, instruction, true);
                        field.owner().initialize(vm);
                        field.owner().statics()[field.slot()] = field.stored(stack[--sp]);
                    }
                    // instanceof is false for null, even against a nullable type (NUL-05).
                    case Opcode.INSTANCEOF -> {
                        Tested tested = tested(vm, method, pc, instruction);
                        Object value = reference(stack[sp - 1], method, pc);
                        stack[sp - 1] = value != null && instance(vm, value, tested.type(), method, pc) ? 1 : 0;
                    }
                    // castclass passes null to a nullable type, and an instance of the type, and throws otherwise
                    // (NUL-05, BC-14).
                    case Opcode.CASTCLASS -> {
                        Tested tested = tested(vm, method, pc, instruction);
                        Object value = reference(stack[sp - 1], method, pc);
                        if (value == null && !tested.nullable()) {
                            throw new Fault(Fault.NULL_POINTER_EXCEPTION, "null cannot be cast to " + tested.name());
                        }
                        if (value != null && !instance(vm, value, tested.type(), method, pc)) {
                            throw new Fault(Fault.CLASS_CAST_EXCEPTION, vm.typeOf(value).name() + " cannot be cast to "
                                    + tested.name());
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

    // Whether a fused branch goes to its target for the values a and b. Floats compare per IEEE 754, so
    // bne is the one that a NaN takes (NUM-04).
    private static boolean taken(int opcode, Object a, Object b) {
        return switch (opcode) {
            case Opcode.BEQ_I32 -> i32(a) == i32(b);
            case Opcode.BEQ_I64 -> i64(a) == i64(b);
            case Opcode.BEQ_F32 -> f32(a) == f32(b);
            case Opcode.BEQ_F64 -> f64(a) == f64(b);
            case Opcode.BNE_I32 -> i32(a) != i32(b);
            case Opcode.BNE_I64 -> i64(a) != i64(b);
            case Opcode.BNE_F32 -> f32(a) != f32(b);
            case Opcode.BNE_F64 -> f64(a) != f64(b);
            case Opcode.BLT_I32 -> i32(a) < i32(b);
            case Opcode.BLT_I64 -> i64(a) < i64(b);
            case Opcode.BLT_F32 -> f32(a) < f32(b);
            case Opcode.BLT_F64 -> f64(a) < f64(b);
            case Opcode.BLE_I32 -> i32(a) <= i32(b);
            case Opcode.BLE_I64 -> i64(a) <= i64(b);
            case Opcode.BLE_F32 -> f32(a) <= f32(b);
            case Opcode.BLE_F64 -> f64(a) <= f64(b);
            case Opcode.BGT_I32 -> i32(a) > i32(b);
            case Opcode.BGT_I64 -> i64(a) > i64(b);
            case Opcode.BGT_F32 -> f32(a) > f32(b);
            case Opcode.BGT_F64 -> f64(a) > f64(b);
            case Opcode.BGE_I32 -> i32(a) >= i32(b);
            case Opcode.BGE_I64 -> i64(a) >= i64(b);
            case Opcode.BGE_F32 -> f32(a) >= f32(b);
            default -> f64(a) >= f64(b);
        };
    }

    private static int target(Instruction instruction) {
        return ((Instruction.Branch) instruction).target().index();
    }

    // A reference of the stack: null, an object or a string. A number there is code that a verifier would
    // reject (BC-07), which VM0 meets only when it runs it (Q101).
    private static @Nullable Object reference(@Nullable Object value, RuntimeMethod method, int pc) {
        if (value instanceof Number) {
            throw new Stop(where(method, pc) + ": the code is invalid: a number stands where a reference does");
        }
        return value;
    }

    // The type that instanceof or castclass names, linked at its first execution (VM-16): a class or an
    // interface, or its nullable form, which a TypeSpec holds (NUL-01, Q99). Value types, whose boxes arrive in
    // v0.0.7, and the other types of TypeSpec rows wait for their stages.
    private static Tested tested(Vm vm, RuntimeMethod method, int pc, Instruction instruction) {
        Object[] links = method.links();
        if (links[pc] == null) {
            TypeHandle handle = ((Instruction.Type) instruction).type();
            boolean nullable = false;
            if (handle instanceof TypeSpec spec && spec.type() instanceof TypeSig.NullableRef wrapped
                    && wrapped.type() instanceof TypeSig.Named named) {
                handle = named.type();
                nullable = true;
            }
            RuntimeType type = vm.type(method.type().module(), handle);
            if (type.isValueType()) {
                throw new Stop(where(method, pc) + ": VM0 does not run value types yet, such as " + type.name());
            }
            links[pc] = new Tested(type, nullable);
        }
        return (Tested) links[pc];
    }

    // Whether a value that is not null is an instance of the type: its run-time type is the type or a subtype
    // of it (TYP-02). Until arrays arrive (v0.0.7), the arguments of main are the only other value.
    private static boolean instance(Vm vm, Object value, RuntimeType type, RuntimeMethod method, int pc) {
        RuntimeType actual = vm.typeOf(value);
        if (actual == null) {
            throw new Stop(where(method, pc) + ": VM0 does not run arrays yet");
        }
        return actual.isSubtypeOf(type);
    }

    // The receiver of an instruction that names a member of the owner. null is a NullPointerException
    // (BC-14, Q100); a value that is no instance of the owner is code that a verifier would reject (Q101).
    private static Object receiver(Vm vm, @Nullable Object value, RuntimeType owner, String what, RuntimeMethod method,
            int pc) {
        if (value == null) {
            throw new Fault(Fault.NULL_POINTER_EXCEPTION, what + ": the receiver is null");
        }
        RuntimeType type = vm.typeOf(value);
        if (type == null || !type.isSubtypeOf(owner)) {
            throw new Stop(where(method, pc) + ": the code is invalid: the receiver of " + what + " is not a "
                    + owner.name());
        }
        return value;
    }

    // The field that a field instruction names, linked at its first execution (VM-16): a static field for
    // ldsfld and stsfld, and an instance field for ldfld and stfld; another one there is a link error (Q101).
    private static RuntimeField field(Vm vm, RuntimeMethod method, int pc, Instruction instruction, boolean statics) {
        Object[] links = method.links();
        if (links[pc] == null) {
            RuntimeField field = vm.field(method.type().module(), ((Instruction.Field) instruction).field());
            if (field.isStatic() != statics) {
                throw new Fault(Fault.TYPE_LOAD_ERROR, Opcode.mnemonic(instruction.opcode()) + " names the "
                        + (statics ? "instance" : "static") + " field " + field.name());
            }
            links[pc] = field;
        }
        return (RuntimeField) links[pc];
    }

    // The method that a callvirt names, linked at its first execution (VM-16): an instance method, or else a
    // link error (Q101).
    private static RuntimeMethod virtual(Vm vm, RuntimeMethod method, MethodHandle handle) {
        RuntimeMethod target = vm.method(method.type().module(), handle);
        if (target.isStatic()) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "callvirt names " + target.signature() + ", which is static");
        }
        return target;
    }

    // The constructor that a newobj names, linked at its first execution (VM-16): a constructor of a class
    // that is not abstract, or else a link error (Q101). Value types arrive in v0.0.7.
    private static RuntimeMethod constructor(Vm vm, RuntimeMethod method, int pc, MethodHandle handle) {
        RuntimeMethod constructor = vm.method(method.type().module(), handle);
        if ((constructor.definition().flags() & Flags.METHOD_CONSTRUCTOR) == 0 || constructor.isStatic()) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "newobj names " + constructor.signature()
                    + ", which is not a constructor");
        }
        RuntimeType type = constructor.type();
        if (type.isValueType()) {
            throw new Stop(where(method, pc) + ": VM0 does not run value types yet, such as " + type.name());
        }
        if (type.isInterface()) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "newobj names a constructor of the interface " + type.name());
        }
        if ((type.definition().flags() & Flags.TYPE_ABSTRACT) != 0) {
            throw new Fault(Fault.TYPE_LOAD_ERROR, "newobj names a constructor of the abstract class " + type.name());
        }
        return constructor;
    }

    // An i32 of the stack; boolean, byte, short and char values are i32 values there too (BC-04).
    private static int i32(Object value) {
        return (Integer) value;
    }

    private static long i64(Object value) {
        return (Long) value;
    }

    // An integer division or remainder by zero throws (BC-04, EXC-05): until v0.0.8, a fault (Q89).
    private static int divisor(int value) {
        if (value == 0) {
            throw new Fault(Fault.ARITHMETIC_EXCEPTION, "division by zero");
        }
        return value;
    }

    private static long divisor(long value) {
        if (value == 0) {
            throw new Fault(Fault.ARITHMETIC_EXCEPTION, "division by zero");
        }
        return value;
    }

    private static float canonical(float value) {
        return value != value ? NAN32 : value;
    }

    private static double canonical(double value) {
        return value != value ? NAN64 : value;
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

    // The type that a type test names, and whether the test names its nullable form (NUL-01).
    private record Tested(RuntimeType type, boolean nullable) {
        // The type as GEN-12 spells it.
        String name() {
            return type.name() + (nullable ? "?" : "");
        }
    }
}
