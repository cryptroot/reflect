package reflect.stage0.test;

import java.util.Objects;

public final class Assertions {
    private Assertions() {
    }

    public static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expected, Action action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return expected.cast(actual);
            }
            throw new AssertionError("Expected " + expected.getName()
                    + " but caught " + actual.getClass().getName(), actual);
        }
        throw new AssertionError("Expected " + expected.getName() + " but nothing was thrown");
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Throwable;
    }
}
