package freenet.support;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * LRU Queue
 * 
 * push()'ing an existing object move it to tail, no duplicated object are ever added.
 */
public class LRUQueue<T> {

    /*
     * I've just converted this to using the DLList and Hashtable
     * this makes it Hashtable time instead of O(N) for push and
     * remove, and Hashtable time instead of O(1) for pop.  Since
     * push is by far the most done operation, this should be an
     * overall improvement.
     */
	private final DoublyLinkedListImpl<QItem<T>> list = new DoublyLinkedListImpl<QItem<T>>();
	private final Map<T, QItem<T>> hash = new HashMap<T, QItem<T>>();
    
    public LRUQueue() {
    }
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     */
	public final synchronized void push(T obj) {
		if (obj == null)
			throw new NullPointerException();

		QItem<T> insert = hash.get(obj);
        if (insert == null) {
			insert = new QItem<T>(obj);
            hash.put(obj,insert);
        } else {
            list.remove(insert);
        }

        list.unshift(insert);
    } 

    /**
     * push to bottom (least recently used position)
     */
	public synchronized void pushLeast(T obj) {
		if (obj == null)
			throw new NullPointerException();

		QItem<T> insert = hash.get(obj);
        if (insert == null) {
			insert = new QItem<T>(obj);
            hash.put(obj,insert);
        } else {
            list.remove(insert);
        }

        list.push(insert);
	}
	
    /**
     *  @return Least recently pushed Object.
     */
	public final synchronized T pop() {
        if ( list.size() > 0 ) {
			return hash.remove(list.pop().obj).obj;
        } else {
            return null;
        }
    }

    public final int size() {
        return list.size();
    }
    
    public final synchronized boolean remove(Object obj) {
		if (obj == null)
			throw new NullPointerException();

		QItem<T> i = hash.remove(obj);
	if(i != null) {
	    list.remove(i);
	    return true;
	} else {
	    return false;
	}
    }
    
    /**
     * Check if this queue contains obj
     * @param obj Object to match
     * @return true if this queue contains obj.
     */
    public final synchronized boolean contains(Object obj) {
        return hash.containsKey(obj);
    }
    
	public Enumeration<T> elements() {
        return new ItemEnumeration();
    }

	private class ItemEnumeration implements Enumeration<T> {

		private Enumeration<QItem<T>> source = list.reverseElements();
       
        @Override
        public boolean hasMoreElements() {
            return source.hasMoreElements();
        }

		@Override
		public T nextElement() {
			return source.nextElement().obj;
        }
    }

	private static class QItem<T> extends DoublyLinkedListImpl.Item<QItem<T>> {
		public T obj;

        public QItem(T obj) {
            this.obj = obj;
        }
    }

    /**
     * Return the objects in the queue as an array in an arbitrary and meaningless
     * order.
     */
	public synchronized Object[] toArray() {
		return hash.keySet().toArray();
	}

    /**
     * Return the objects in the queue as an array in an arbitrary and meaningless
     * order.
	 * @param array The array to fill in. If it is too small a new array of the same type will be allocated.
     */
	public synchronized <E> E[] toArray(E[] array) {
		return hash.keySet().toArray(array);
	}
	
	/**
	 * Return the objects in the queue as an array. The <strong>least</strong>
	 * recently used object is in <tt>[0]</tt>, the <strong>most</strong>
	 * recently used object is in <tt>[array.length-1]</tt>.
	 */

	public synchronized Object[] toArrayOrdered() {
		Object[] array = new Object[list.size()];
		int x = 0;
		for (Enumeration<QItem<T>> e = list.reverseElements(); e.hasMoreElements();) {
			array[x++] = e.nextElement().obj;
		}
		return array;
	}

	/**
	 * Return the objects in the queue as an array. The <strong>least</strong>
	 * recently used object is in <tt>[0]</tt>, the <strong>most</strong>
	 * recently used object is in <tt>[array.length-1]</tt>.
	 * 
	 * @param array
	 *            The array to fill in. If it is too small a new array of the
	 *            same type will be allocated.
	 */

	@SuppressWarnings("unchecked")
	public synchronized <E> E[] toArrayOrdered(E[] array) {
		array = toArray(array);
		int listSize = list.size();
		if(array.length != listSize)
			throw new IllegalStateException("array.length="+array.length+" but list.size="+listSize);
		int x = 0;
		for (Enumeration<QItem<T>> e = list.reverseElements(); e.hasMoreElements();) {
			array[x++] = (E) e.nextElement().obj;
		}
		return array;
	}
	
	public synchronized boolean isEmpty() {
		return hash.isEmpty();
	}
	
	public synchronized void clear() {
		list.clear();
		hash.clear();
	}

	public synchronized T get(T obj) {
		QItem<T> val = hash.get(obj);
		if(val == null) return null;
		return val.obj;
	}
}

