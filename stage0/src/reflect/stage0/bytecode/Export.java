package reflect.stage0.bytecode;

/**
 * An Export row (BC-11): a namespace that the module exports or opens, by its Export kind, to the
 * module that the target names, or to every module when the target is empty. A directive with
 * several targets has one row per target (§3.1).
 */
public record Export(String namespace, int kind, String target) {
}
