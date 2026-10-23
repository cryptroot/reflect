package reflect.stage0.vm;

/**
 * VM0 cannot start or cannot run the module (README §6.2, Q89): a module that it cannot read, a
 * module graph that does not resolve, no main class or method, or code that it cannot run. VM0
 * prints the message after "vm0: " and exits with 2.
 */
final class Stop extends RuntimeException {
    Stop(String message) {
        super(message);
    }
}
