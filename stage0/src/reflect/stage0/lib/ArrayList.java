package reflect.stage0.lib;

/** reflect.collections.ArrayList over java.util.ArrayList (Q72). Equal items make equal lists. */
public final class ArrayList<T> implements List<T> {
    private final java.util.ArrayList<T> items = new java.util.ArrayList<>();

    public ArrayList() {
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public T get(int index) {
        return items.get(index);
    }

    @Override
    public T set(int index, T item) {
        return items.set(index, item);
    }

    @Override
    public boolean add(T item) {
        return items.add(item);
    }

    @Override
    public void sort(Comparator<? super T> comparator) {
        items.sort(comparator::compare);
    }

    @Override
    public java.util.Iterator<T> iterator() {
        return items.iterator();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ArrayList<?> list && items.equals(list.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return items.toString();
    }
}
