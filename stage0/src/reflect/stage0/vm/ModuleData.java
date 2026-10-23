package reflect.stage0.vm;

/** The bytes of a module file, and the file, which messages name. */
public record ModuleData(String file, byte[] bytes) {
}
