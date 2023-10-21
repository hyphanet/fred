package freenet.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A hashtable that can store several values for each entry.
 * <p>
 * This object is thread-safe, it may be read and updated concurrently.
 * Access rules are similar to collections from {@link java.util.concurrent} package.
 *
 * @author oskar
 */
public class MultiValueTable<K,V> {
    private final Map<K, List<V>> table;

    public MultiValueTable() {
        table = new ConcurrentHashMap<>();
    }

    public MultiValueTable(int initialSize) {
        table = new ConcurrentHashMap<>(initialSize);
    }

    /**
     * Deprecated constructor, please use other variant with only one single size parameter
     * @param initialSize table initial size
     * @param unused this parameter was used as initial value collection size, and it is not unused anymore.
     *               Values collections grow according to Java collection rules.
     */
    @Deprecated
    public MultiValueTable(int initialSize, int unused) {
        this(initialSize);
    }

    public static <K, V> MultiValueTable<K, V> from (K[] keys, V[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException(String.format(
                "keys and values must contain the same number of values, but there are %d keys and %d values",
                keys.length,
                values.length));
        }
        MultiValueTable<K, V> table = new MultiValueTable<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            table.put(keys[i], values[i]);
        }
        return table;
    }

    @SafeVarargs
    public static <K, V> MultiValueTable<K, V> from(K key, V... values) {
        return from(key, Arrays.asList(values));
    }

    public static <K, V> MultiValueTable<K, V> from(K key, Collection<? extends V> values) {
        MultiValueTable<K, V> table = new MultiValueTable<>(1);
        table.putAll(key, values);
        return table;
    }

    public void put(K key, V value) {
        this.table.compute(key, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.add(value);
            return list;
        });
    }

    public void putAll(K key, Collection<? extends V> elements) {
        this.table.compute(key, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>(elements);
            } else {
                list.addAll(elements);
            }
            return list;
        });
    }

    /**
     * Returns the first element for this key.
     */
    public V get(K key) {
        return getFirst(key);
    }

    /**
     * Returns the first element for this key.
     */
    public V getFirst(K key) {
        List<V> list = this.table.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public boolean containsKey(K key) {
        return this.table.containsKey(key);
    }

    public boolean containsElement(K key, V value) {
        List<V> list = this.table.get(key);
        if (list == null || list.isEmpty()) {
            return false;
        }
        return list.contains(value);
    }

    /**
     * Returns mapped value collection as {@link Enumeration}
     *
     * <b>Note:</b> this method is deprecated
     * please use other {@link #getAllAsList(Object)} method variant, which provides a {@link List}
     *
     * @param key key mapping
     */
    @Deprecated
    public Enumeration<V> getAll(K key) {
        List<V> elements = getAllAsList(key);
        if (elements.isEmpty()) {
            return Collections.emptyEnumeration();
        } else {
            return Collections.enumeration(elements);
        }
    }

    public List<V> getAllAsList(K key) {
        List<V> list = this.table.get(key);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * To be used in for(x : y).
     */
    public Iterable<V> iterateAll(K key) {
        return getAllAsList(key);
    }

    public int countAll(K key) {
        return getAllAsList(key).size();
    }

    /**
     * Returns mapped value collection as raw {@link Object}
     *
     * <b>Note:</b> this method is deprecated, this was used in previous {@code synchronized} implementation variant,
     * please use other {@link #getAllAsList(Object)})} method variant, which provides a typed {@link List} collection.
     *
     * @param key key mapping
     */
    @Deprecated
    public Object getSync(K key) {
        return getAllAsList(key);
    }

    /**
     * Returns mapped value collection as array
     *
     * @deprecated use {@link #getAllAsList(Object)} with {@link List#toArray()} to obtain the values as object array
     *
     * @param key key mapping
     */
    @Deprecated
    public Object[] getArray(K key) {
        return getAllAsList(key).toArray();
    }

    public void remove(K key) {
        this.table.remove(key);
    }

    public boolean isEmpty() {
        return this.table.isEmpty();
    }

    public int size() {
        return this.table.size();
    }

    public void clear() {
        this.table.clear();
    }

    public boolean removeElement(K key, V value) {
        boolean[] removed = new boolean[1];
        this.table.computeIfPresent(key, (k, list) -> {
            removed[0] = list.remove(value);
            if (list.isEmpty()) {
                // null result removes element from Map
                return null;
            }
            return list;
        });
        return removed[0];
    }

    /**
     * Returns table keys as {@link Enumeration}
     *
     * <b>Note:</b> this method is deprecated,
     * please use other {@link #keySet()} method variant, which provides a {@link Set} of keys.
     *
     * @param key key mapping
     */
    @Deprecated
    public Enumeration<K> keys() {
        return Collections.enumeration(keySet());
    }

    public Set<K> keySet() {
        return Collections.unmodifiableSet(this.table.keySet());
    }

    /**
     * Returns table values as {@link Enumeration}.
     * This iterates over value collections mapped to all existing keys.
     *
     * <p>
     * <b>Note:</b> this method is deprecated,
     * please use other {@link #values()} method variant, which provides a {@link Collection} of values.
     *
     * @return table values
     */
    @Deprecated
    public Enumeration<V> elements() {
        return Collections.enumeration(values());
    }

    public Collection<V> values() {
        List<V> allValues = new ArrayList<>();
        for (List<V> entryValues : this.table.values()) {
            allValues.addAll(entryValues);
        }
        return Collections.unmodifiableList(allValues);
    }

    public Set<Map.Entry<K, List<V>>> entrySet() {
        return Collections.unmodifiableMap(this.table).entrySet();
    }

    @Override
    public String toString() {
        return "[MultiValueTable table=" + table + "]";
    }
}
