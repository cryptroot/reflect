package reflect.stage0.lib;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class ArrayListTest {
    @Test
    public static void addGetSetAndSize() {
        var list = new ArrayList<String>();
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        assertTrue(list.add("a"));
        assertTrue(list.add("b"));
        assertTrue(!list.isEmpty());
        assertEquals(2, list.size());
        assertEquals("b", list.get(1));
        assertEquals("a", list.set(0, "c"));
        assertEquals("c", list.get(0));
        assertEquals(2, list.size());
    }

    @Test
    public static void indexesOutsideTheListThrow() {
        var list = new ArrayList<String>();
        list.add("a");
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.set(1, "b"));
    }

    @Test
    public static void iteratesInInsertionOrder() {
        var list = new ArrayList<String>();
        list.add("x");
        list.add("y");
        list.add("z");
        var seen = new StringBuilder();
        for (String item : list) {
            seen.append(item);
        }
        assertEquals("xyz", seen.toString());
    }

    @Test
    public static void sortsByTheComparator() {
        var list = new ArrayList<Integer>();
        for (int value : new int[] {5, 3, 9, 1, 7}) {
            list.add(value);
        }
        list.sort(Integer::compare);
        assertEquals("[1, 3, 5, 7, 9]", list.toString());
        list.sort((left, right) -> Integer.compare(right, left));
        assertEquals("[9, 7, 5, 3, 1]", list.toString());
    }

    @Test
    public static void listsWithEqualItemsAreEqual() {
        var first = new ArrayList<String>();
        var second = new ArrayList<String>();
        var reversed = new ArrayList<String>();
        first.add("a");
        first.add("b");
        second.add(new String("a"));
        second.add("b");
        reversed.add("b");
        reversed.add("a");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(!first.equals(reversed));
        assertTrue(!first.equals(null));
        // Records compare their components with equals, so a record of lists has value equality too.
        assertEquals(new Holder(first), new Holder(second));
    }

    private record Holder(List<String> items) {
    }
}
