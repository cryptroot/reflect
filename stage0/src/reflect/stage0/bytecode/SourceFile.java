package reflect.stage0.bytecode;

/**
 * A source file in DEBUG (§6.1): its path relative to the module root, with / separators, and the
 * SHA-256 of its bytes, which tells a debugger which source the line entries match (BC-22).
 */
public record SourceFile(String path, byte[] hash) {
}
