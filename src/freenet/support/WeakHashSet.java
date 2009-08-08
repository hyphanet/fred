package freenet.support;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;

public class WeakHashSet<E> extends AbstractSet<E> {

	private final WeakHashMap<E, Object> map;
	private final static Object placeholder = new Object();

	public WeakHashSet() {
		map = new WeakHashMap<E, Object>();
	}

	@Override
    public boolean add(E key) {
		return map.put(key, placeholder) == null;
	}

	@Override
    public void clear() {
		map.clear();
	}

	@Override
    public boolean contains(Object key) {
		return map.containsKey(key);
	}

	@Override
    public boolean containsAll(Collection<?> arg0) {
		return map.keySet().containsAll(arg0);
	}

	@Override
    public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override
    public Iterator<E> iterator() {
		return map.keySet().iterator();
	}

	@Override
    public boolean remove(Object key) {
		return map.remove(key) != null;
	}

	@Override
    public int size() {
		return map.size();
	}

	@Override
    public Object[] toArray() {
		return map.keySet().toArray();
	}

	@Override
    public <T> T[] toArray(T[] arg0) {
		return map.keySet().toArray(arg0);
	}
}
