package reflect.stage0.lib;

/** The comparator of reflect.collections (LIB-04): negative, zero or positive, as in Java. */
@FunctionalInterface
public interface Comparator<T> {
    int compare(T left, T right);
}
