package reflect.stage0.test;

import java.io.IOException;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class AssertionsTest {
    @Test
    public static void truthAndEquality() {
        assertTrue(true);
        assertEquals("reflect", new String("reflect"));
        assertEquals(null, null);
        assertThrows(AssertionError.class, () -> assertTrue(false));
        assertThrows(AssertionError.class, () -> assertEquals("a", "b"));
        assertThrows(AssertionError.class, () -> assertEquals(null, "b"));
        assertThrows(AssertionError.class, () -> assertEquals("a", null));
    }

    @Test
    public static void expectedExceptions() {
        IOException expected = new IOException("fixture");
        assertEquals(expected, assertThrows(Exception.class, () -> {
            throw expected;
        }));
        assertThrows(AssertionError.class, () -> assertThrows(IOException.class, () -> {
        }));
        AssertionError error = assertThrows(AssertionError.class,
                () -> assertThrows(IOException.class, () -> {
                    throw new IllegalStateException("wrong exception");
                }));
        assertTrue(error.getCause() instanceof IllegalStateException);
    }
}
