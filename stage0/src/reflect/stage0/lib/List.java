package reflect.stage0.lib;

/** The members of reflect.collections.List that stage0 uses, with Java's names (LIB-04, Q72). */
public interface List<T> extends Iterable<T> {
    int size();

    boolean isEmpty();

    T get(int index);

    /** Replaces the item at the index and returns the item it held. */
    T set(int index, T item);

    /** Appends the item and returns true, as Java's List.add does. */
    boolean add(T item);

    /** Sorts the list; callers give a total order, so the result does not depend on stability. */
    void sort(Comparator<? super T> comparator);
}
