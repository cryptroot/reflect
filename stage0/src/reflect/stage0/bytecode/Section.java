package reflect.stage0.bytecode;

/** A section of a module file as bytes: the writer's input, and an unknown ancillary section (BC-20). */
public record Section(int id, byte[] content) {
}
