package freenet.support;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

public class IdentityHashSet<T> implements Set<T> {
	
	private final IdentityHashMap<T,Object> map = new IdentityHashMap<T,Object>();
	
	@Override
	public boolean add(T e) {
		return map.put(e, this) == null;
	}
	
	@Override
	public boolean addAll(Collection<? extends T> c) {
		boolean changed = false;
		for(T item : c)
			if(!add(item)) changed = true;
		return changed;
	}
	
	@Override
	public void clear() {
		map.clear();
	}
	
	@Override
	public boolean contains(Object o) {
		return map.containsKey(o);
	}
	
	@Override
	public boolean containsAll(Collection<?> c) {
		return map.keySet().containsAll(c);
	}
	
	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}
	
	@Override
	public Iterator<T> iterator() {
		return map.keySet().iterator();
	}
	
	@Override
	public boolean remove(Object o) {
		return map.remove(o) != null;
	}
	
	@Override
	public boolean removeAll(Collection<?> c) {
		boolean changed = false;
		for(Object o : c) {
			if(remove(o)) changed = true;
		}
		return changed;
	}
	
	@Override
	public boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException(); // FIXME ?
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
	public <TT> TT[] toArray(TT[] a) {
		return map.keySet().toArray(a);
	}

}
