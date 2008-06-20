package freenet.support;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import com.db4o.ObjectContainer;
import com.db4o.types.Db4oMap;

public class Db4oSet implements Set {
	
	private final Db4oMap map;
	private final NullObject object = new NullObject();
	
	Db4oSet(ObjectContainer container, int size) {
		map = container.ext().collections().newHashMap(size);
	}

	public boolean add(Object arg0) {
		// Avoid unnecessary modification.
		if(map.containsKey(arg0)) return false;
		map.put(arg0, object);
		return true;
	}

	public boolean addAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	public void clear() {
		map.clear();
	}

	public boolean contains(Object key) {
		return map.containsKey(key);
	}

	public boolean containsAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	public boolean isEmpty() {
		return map.isEmpty();
	}

	public Iterator iterator() {
		Set keys = map.keySet();
		return keys.iterator();
	}

	public boolean remove(Object key) {
		return map.remove(key) != null;
	}

	public boolean removeAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	public boolean retainAll(Collection arg0) {
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

	public void objectOnActivate(ObjectContainer container) {
		container.activate(map, 1);
	}
}
