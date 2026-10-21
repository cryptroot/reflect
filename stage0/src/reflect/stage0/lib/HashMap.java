package reflect.stage0.lib;

/** reflect.collections.HashMap over java.util.HashMap (Q72). Keys compare with equals. */
public final class HashMap<K, V> implements Map<K, V> {
    private final java.util.HashMap<K, V> entries = new java.util.HashMap<>();

    public HashMap() {
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean containsKey(K key) {
        return entries.containsKey(key);
    }

    @Override
    public @Nullable V get(K key) {
        return entries.get(key);
    }

    @Override
    public V getOrDefault(K key, V fallback) {
        return entries.getOrDefault(key, fallback);
    }

    @Override
    public @Nullable V put(K key, V value) {
        return entries.put(key, value);
    }
}
