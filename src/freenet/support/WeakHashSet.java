package freenet.support;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

public class WeakHashSet implements Set {
	
	private final WeakHashMap map;
	
	public WeakHashSet() {
		map = new WeakHashMap();
	}

	public boolean add(Object key) {
		return map.put(key, null) == null;
	}

	public boolean addAll(Collection arg0) {
		boolean changed = false;
		for(Iterator i=arg0.iterator();i.hasNext();) {
			changed |= add(i.next());
		}
		return changed;
	}

	public void clear() {
		map.clear();
	}

	public boolean contains(Object key) {
		return map.containsKey(key);
	}

	public boolean containsAll(Collection arg0) {
		for(Iterator i=arg0.iterator();i.hasNext();) {
			if(!map.containsKey(i.next())) return false;
		}
		return true;
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

	public boolean removeAll(Collection arg0) {
		boolean changed = false;
		for(Iterator i=arg0.iterator();i.hasNext();) {
			changed |= remove(i.next());
		}
		return changed;
	}

	public boolean retainAll(Collection arg0) {
		// FIXME
		throw new UnsupportedOperationException();
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
