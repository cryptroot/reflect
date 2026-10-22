package reflect.stage0.bytecode;

/** The name of a local of a body, by number, in DEBUG (§6.1). */
public record LocalName(int index, String name) {
}
