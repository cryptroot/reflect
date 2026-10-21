package reflect.stage0.lib;

/**
 * The members of reflect.collections.Map that stage0 uses, with Java's names (LIB-04, Q72). It has
 * no iteration, so no result depends on hash order (BOOT-05).
 */
public interface Map<K, V> {
    int size();

    boolean isEmpty();

    boolean containsKey(K key);

    /** The value of the key, or null when the map has none. */
    @Nullable V get(K key);

    /** The value of the key, or the fallback when the map has none; no null for primitive values. */
    V getOrDefault(K key, V fallback);

    /** Sets the value of the key and returns the value it replaced, or null. */
    @Nullable V put(K key, V value);
}
