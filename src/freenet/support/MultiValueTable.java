package freenet.support;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A hashtable that can store several values for each entry.
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

    @SafeVarargs
    public static <K, V> MultiValueTable<K, V> from(K key, V... values) {
      MultiValueTable<K, V> table = new MultiValueTable<>(1);
      table.putAll(key, Arrays.asList(values));
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

    public void putAll(K key, Collection<V> elements) {
        this.table.compute(key, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.addAll(elements);
            return list;
        });
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
     * @param key key
     * @return list of elements mapped to provided key
     */
    public List<V> getAll(K key) {
        List<V> list = this.table.get(key);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    public int countAll(K key) {
        return getAll(key).size();
    }

    public boolean remove(K key) {
        List<V> elements = this.table.remove(key);
        return elements != null;
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

    public Set<K> keys() {
        return Collections.unmodifiableSet(this.table.keySet());
    }

    @Override
    public String toString() {
        return "[MultiValueTable table=" + table + "]";
    }
}
