package reflect.stage0.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class NullableTest {
    @Test
    public static void marksTypeUsesAsJSpecifyDoes() {
        ElementType[] targets = Nullable.class.getAnnotation(Target.class).value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.TYPE_USE, targets[0]);
        assertEquals(RetentionPolicy.RUNTIME, Nullable.class.getAnnotation(Retention.class).value());
    }

    @Test
    public static void mapResultsAreMarkedNullable() throws NoSuchMethodException {
        assertTrue(Map.class.getMethod("get", Object.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertTrue(Map.class.getMethod("put", Object.class, Object.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertTrue(!List.class.getMethod("get", int.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
    }
}
