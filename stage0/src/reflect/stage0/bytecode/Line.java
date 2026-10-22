package reflect.stage0.bytecode;

/**
 * A line entry of a body in DEBUG (§6.1): the code from the instruction at its label to the next
 * entry comes from this line of the source file. The line number is a u32 counted from 1.
 */
public record Line(Label at, SourceFile file, int number) {
}
