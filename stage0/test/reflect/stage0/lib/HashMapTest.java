package reflect.stage0.lib;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class HashMapTest {
    @Test
    public static void getPutAndContainsKey() {
        var map = new HashMap<String, Integer>();
        assertTrue(map.isEmpty());
        assertEquals(null, map.get("a"));
        assertTrue(!map.containsKey("a"));
        assertEquals(null, map.put("a", 1));
        assertEquals(1, map.put("a", 2));
        assertEquals(2, map.get(new String("a")));
        assertTrue(map.containsKey("a"));
        assertTrue(!map.isEmpty());
        assertEquals(1, map.size());
        assertEquals(2, map.getOrDefault("a", -1));
        assertEquals(-1, map.getOrDefault("b", -1));
    }

    @Test
    public static void keysWithoutEqualsCompareByIdentity() {
        var map = new HashMap<Object, String>();
        Object first = new Object();
        Object second = new Object();
        map.put(first, "first");
        map.put(second, "second");
        assertEquals(2, map.size());
        assertEquals("first", map.get(first));
        assertEquals("second", map.get(second));
        assertEquals(null, map.get(new Object()));
    }
}
