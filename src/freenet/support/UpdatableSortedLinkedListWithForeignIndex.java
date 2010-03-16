package freenet.support;

import java.util.HashMap;

/**
 * UpdatableSortedLinkedList plus a hashtable. Each item has
 * an indexItem(), which we use to track them. This is completely
 * independant of their sort order, hence "foreign".
 * Note that this class, unlike its parent, does not permit
 * duplicates.
 */
public class UpdatableSortedLinkedListWithForeignIndex<T extends IndexableUpdatableSortedLinkedListItem<T>> extends UpdatableSortedLinkedList<T> {

	final HashMap<Object, T> map;

	public UpdatableSortedLinkedListWithForeignIndex() {
		super();
		map = new HashMap<Object, T>();
	}

	@Override
	public synchronized void add(T item) throws UpdatableSortedLinkedListKilledException {
		if(killed) throw new UpdatableSortedLinkedListKilledException();
		T i = item;
		if(map.get(i.indexValue()) != null) {
			// Ignore duplicate
			Logger.error(this, "Ignoring duplicate: "+i+" was already present: "+map.get(i.indexValue()));
			return;
		}
		super.add(i);
		map.put(i.indexValue(), item);
		checkList();
	}

	@Override
	public synchronized T remove(T item) throws UpdatableSortedLinkedListKilledException {
		if(killed) throw new UpdatableSortedLinkedListKilledException();
		map.remove(item.indexValue());
		return super.remove(item);
	}

	public synchronized T get(Object key) {
		return map.get(key);
	}

	public synchronized boolean containsKey(Object key) {
		return map.containsKey(key);
	}

	public synchronized boolean contains(IndexableUpdatableSortedLinkedListItem<?> item) {
		return containsKey(item.indexValue());
	}

	/**
	 * Remove an element from the list by its key.
	 * @throws UpdatableSortedLinkedListKilledException
	 */
	public synchronized T removeByKey(Object key) throws UpdatableSortedLinkedListKilledException {
		if(killed) throw new UpdatableSortedLinkedListKilledException();
		T item = map.get(key);
		if(item != null) remove(item);
		checkList();
		return item;
	}

	@Override
	public synchronized void clear() {
		map.clear();
		super.clear();
	}
}
