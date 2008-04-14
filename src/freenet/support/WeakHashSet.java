package freenet.support;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;

public class WeakHashSet extends AbstractSet {
	private final WeakHashMap map;
	
	public WeakHashSet() {
		map = new WeakHashMap();
	}

	public boolean add(Object key) {
		return map.put(key, null) == null;
	}

	public void clear() {
		map.clear();
	}

	public boolean contains(Object key) {
		return map.containsKey(key);
	}

	public boolean containsAll(Collection arg0) {
		return map.keySet().containsAll(arg0);
	}

	public boolean isEmpty() {
		return map.isEmpty();
	}

	public Iterator iterator() {
		return map.keySet().iterator();
	}

	public boolean remove(Object key) {
		return map.remove(key) != null;
	}

	public int size() {
		return map.size();
	}

	public Object[] toArray() {
		return map.keySet().toArray();
	}

	public Object[] toArray(Object[] arg0) {
		return map.keySet().toArray(arg0);
	}
}
