package reflect.stage0.vm;

import reflect.stage0.bytecode.Flags;
import reflect.stage0.bytecode.Instruction;
import reflect.stage0.bytecode.MethodBody;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.SigCode;
import reflect.stage0.bytecode.TypeSig;
import reflect.stage0.lib.Nullable;

/**
 * A method that VM0 has linked: its arguments, this first unless it is static (BC-04), whether it
 * returns a value, and its code or its native. Each instruction has a slot for what it resolves on
 * its first execution, such as the method that a call names (VM-16).
 */
final class RuntimeMethod {
    private final RuntimeType type;
    private final MethodDef definition;
    private final int arguments;
    private final boolean returnsValue;
    private final Natives.@Nullable Native nativeCode;
    private final int maxStack;
    private final Instruction @Nullable [] code;
    private final @Nullable Object[] links;

    RuntimeMethod(RuntimeType type, MethodDef definition) {
        this.type = type;
        this.definition = definition;
        boolean isStatic = (definition.signature().flags() & SigCode.METHOD_STATIC) != 0;
        arguments = definition.signature().params().size() + (isStatic ? 0 : 1);
        returnsValue = !(definition.signature().returnType() instanceof TypeSig.Primitive primitive
                && primitive.code() == SigCode.VOID);
        nativeCode = (definition.flags() & Flags.METHOD_NATIVE) == 0 ? null : Natives.find(type.module().name(),
                Spelling.method(type.name(), definition.name(), definition.signature(), type.module().name(), false));
        MethodBody body = definition.body();
        maxStack = body == null ? 0 : body.maxStack();
        if (body == null) {
            code = null;
            links = new Object[0];
        } else {
            code = new Instruction[body.code().size()];
            for (int i = 0; i < code.length; i++) {
                code[i] = body.code().get(i);
            }
            links = new Object[code.length];
        }
    }

    RuntimeType type() {
        return type;
    }

    MethodDef definition() {
        return definition;
    }

    boolean isStatic() {
        return (definition.flags() & Flags.METHOD_STATIC) != 0;
    }

    int arguments() {
        return arguments;
    }

    boolean returnsValue() {
        return returnsValue;
    }

    /** The Java method of a native, which loading the type has checked (VM-08), or null. */
    Natives.@Nullable Native nativeCode() {
        return nativeCode;
    }

    int maxStack() {
        return maxStack;
    }

    /** The instructions, or null for a method without a body. */
    Instruction @Nullable [] code() {
        return code;
    }

    /** What each instruction has resolved, or null before its first execution. */
    @Nullable Object[] links() {
        return links;
    }

    /** The name that messages give: the type, then the method after "::". */
    String name() {
        return type.name() + "::" + definition.name();
    }
}
