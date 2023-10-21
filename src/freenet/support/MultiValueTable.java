package freenet.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hashtable that can store several values for each entry.
 * <p>
 * This object is thread-safe, it may be read and updated concurrently.
 * Access rules are similar to collections from {@link java.util.concurrent} package.
 *
 * @author oskar
 */
public class MultiValueTable<K, V> {
    private final Map<K, List<V>> table;

    public MultiValueTable() {
        table = new ConcurrentHashMap<>();
    }

    public MultiValueTable(int initialSize) {
        table = new ConcurrentHashMap<>(initialSize);
    }

    /**
     * Deprecated constructor, please use other variant with only one single size parameter
     *
     * @param initialSize table initial size
     * @param unused      this parameter was used as initial value collection size, and it is not unused anymore.
     *                    Values collections grow according to Java collection rules.
     */
    @Deprecated
    public MultiValueTable(int initialSize, int unused) {
        this(initialSize);
    }

    public static <K, V> MultiValueTable<K, V> from(K[] keys, V[] values) {
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
        this.table.compute(key, (k, previousList) -> {
            List<V> result;
            if (previousList == null) {
                // FIXME: replace with List.of(v) when Java version baseline becomes >= 11
                result = new ArrayList<>(1);
            } else {
                result = new ArrayList<>(previousList.size() + 1);
                result.addAll(previousList);
            }
            result.add(value);
            return Collections.unmodifiableList(result);
        });
    }

    public void putAll(K key, Collection<? extends V> elements) {
        this.table.compute(key, (k, previousList) -> {
            List<V> result;
            if (previousList == null) {
                result = new ArrayList<>(elements.size());
            } else {
                result = new ArrayList<>(previousList.size() + elements.size());
                result.addAll(previousList);
            }
            result.addAll(elements);
            return Collections.unmodifiableList(result);
        });
    }

    /**
     * Returns the first element for this key.
     * @deprecated use {@link #getFirst(Object)} instead
     */
    @Deprecated
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
     * Returns mapped value collection as {@link Enumeration}.
     * This enumeration is backed by the immutable collection of mapped values,
     * see more in the description of {@link #getAllAsList(Object)}.
     *
     * @deprecated use other {@link #getAllAsList(Object)} method variant, which provides a {@link List}
     * @param key key mapping
     * @return mapped value collection as {@link Enumeration}
     */
    @Deprecated
    public Enumeration<V> getAll(K key) {
        return Collections.enumeration(getAllAsList(key));
    }

    /**
     * Returns mapped value collection as {@link List}.
     *
     * @param key key mapping
     * @return The immutable collection of mapped values.
     * This {@link List} does not depend on any modifications in the multi-value table.
     */
    public List<V> getAllAsList(K key) {
        return this.table.getOrDefault(key, Collections.emptyList());
    }

    /**
     * To be used in for(x : y).
     *
     * @return {@link Iterable} for the mapped value collection.
     * This {@link Iterable} is backed by the immutable collection of mapped values.
     * Iteration does not depend on any modifications in the multi-value table.
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
     * @param key key mapping
     * @deprecated this method was used in previous {@code synchronized} implementation variant.
     * Use other {@link #getAllAsList(Object)})} method variant, which provides a typed {@link List} collection.     *
     */
    @Deprecated
    public Object getSync(K key) {
        return getAllAsList(key);
    }

    /**
     * Returns mapped value collection as array
     *
     * @param key key mapping
     * @return the new array copy of values mapped to provided key or {@code null} if key is missing
     * @deprecated use {@link #getAllAsList(Object)} with {@link List#toArray()} to obtain the values as object array
     */
    @Deprecated
    public Object[] getArray(K key) {
        List<V> l = this.table.get(key);
        if (l == null) {
            return null;
        }
        return l.toArray();
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
        this.table.computeIfPresent(key, (k, previousList) -> {
            if (!previousList.contains(value)) {
                return previousList;
            }
            List<V> result = new ArrayList<>(previousList.size() - 1);
            for (V v : previousList) {
                if (Objects.equals(v, value) && !removed[0]) {
                    removed[0] = true;
                } else {
                    result.add(v);
                }
            }
            if (result.isEmpty()) {
                // null result removes element from Map
                return null;
            }
            return Collections.unmodifiableList(result);
        });
        return removed[0];
    }

    /**
     * Returns table keys as {@link Enumeration}.
     * This enumeration is backed by the immutable copy of keys, see more in the description of {@link #keySet()}.
     *
     * @return table keys as {@link Enumeration}
     * @deprecated use other {@link #keySet()} method variant, which provides a {@link Set} of keys.
     */
    @Deprecated
    public Enumeration<K> keys() {
        return Collections.enumeration(keySet());
    }

    /**
     * @return The immutable copy of table keys.
     * This collection result cannot be modified, and therefore it cannot affect any keys in the multi-value table.
     * Any following changes in the table keys are not reflected in the returned collection.
     */
    public Set<K> keySet() {
        // FIXME: replace with Set.copyOf() when Java version baseline becomes >= 11
        return Collections.unmodifiableSet(new HashSet<>(this.table.keySet()));
    }

    /**
     * Returns table values as {@link Enumeration}.
     * This iterates over value collections mapped to all existing keys.
     * This enumeration is backed by the immutable copy of values, see more in the description of {@link #values()}
     *
     * @return table values
     * @deprecated use other {@link #values()} method variant, which provides a {@link Collection} of values.     *
     */
    @Deprecated
    public Enumeration<V> elements() {
        return Collections.enumeration(values());
    }

    /**
     * @return The immutable copy of table entries.
     * This collection result cannot be modified, and therefore it cannot affect any entries in the multi-value table.
     * Each {@link Map.Entry} in the returned collection is also unmodifiable,
     * and it cannot be used to update entries in this multi-value table.
     * Any following changes in the table keys are not reflected in the returned collection.
     */
    public Collection<V> values() {
        List<V> allValues = new ArrayList<>();
        for (List<V> entryValues : this.table.values()) {
            allValues.addAll(entryValues);
        }
        return Collections.unmodifiableList(allValues);
    }

    /**
     * @return The immutable copy of table entries.
     * This collection result cannot be modified, and therefore it cannot affect any entries in the multi-value table.
     * Each {@link Map.Entry} in the returned collection is also unmodifiable,
     * and it cannot be used to update entries in this multi-value table.
     * Any following changes of the table keys are not reflected in the returned collection.
     */
    public Set<Map.Entry<K, List<V>>> entrySet() {
        // FIXME: replace with Set.copyOf() when Java version baseline becomes >= 11
        return Collections.unmodifiableSet(
            new HashSet<>(
                Collections.unmodifiableMap(this.table).entrySet()
            )
        );
    }

    @Override
    public String toString() {
        return "[MultiValueTable table=" + table + "]";
    }
}
