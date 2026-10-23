package reflect.stage0.vm;

import java.util.IdentityHashMap;
import java.util.Map;

import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.TypeDef;

/**
 * A run-time type (VM-03): a type of a module that VM0 has loaded at its first use (VM-13). It
 * grows with the stages; in v0.0.5 it holds the methods that VM0 has linked.
 */
final class RuntimeType {
    private final RuntimeModule module;
    private final TypeDef definition;
    private final Map<MethodDef, RuntimeMethod> methods = new IdentityHashMap<>();

    RuntimeType(RuntimeModule module, TypeDef definition) {
        this.module = module;
        this.definition = definition;
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

    /** The run-time method of a method that the type lists. */
    RuntimeMethod method(MethodDef method) {
        return methods.computeIfAbsent(method, definition -> new RuntimeMethod(this, definition));
    }
}
