package freenet.support;

import java.util.Enumeration;
import java.util.Hashtable;

public class LRUQueue {

    /*
     * I've just converted this to using the DLList and Hashtable
     * this makes it Hashtable time instead of O(N) for push and
     * remove, and Hashtable time instead of O(1) for pop.  Since
     * push is by far the most done operation, this should be an
     * overall improvement.
     */
    private final DoublyLinkedListImpl list = new DoublyLinkedListImpl();
    private final Hashtable hash = new Hashtable();
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     */
    public final synchronized void push(Object obj) {
        QItem insert = (QItem)hash.get(obj);        
        if (insert == null) {
            insert = new QItem(obj);
            hash.put(obj,insert);
        } else {
            list.remove(insert);
        }

        list.unshift(insert);
    } 

    /**
     * push to bottom (least recently used position)
     */
	public synchronized void pushLeast(Object obj) {
        QItem insert = (QItem)hash.get(obj);        
        if (insert == null) {
            insert = new QItem(obj);
            hash.put(obj,insert);
        } else {
            list.remove(insert);
        }

        list.push(insert);
	}
	
    /**
     *  @return Least recently pushed Object.
     */
    public final synchronized Object pop() {
        if ( list.size() > 0 ) {
            return ((QItem)hash.remove(((QItem)list.pop()).obj)).obj;
        } else {
            return null;
        }
    }

    public final int size() {
        return list.size();
    }
    
    public final synchronized boolean remove(Object obj) {
	QItem i = (QItem)(hash.remove(obj));
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
    
    public Enumeration elements() {
        return new ItemEnumeration();
    }

    private class ItemEnumeration implements Enumeration {
        private Enumeration source = list.reverseElements();
       
        public boolean hasMoreElements() {
            return source.hasMoreElements();
        }

        public Object nextElement() {
            return ((QItem) source.nextElement()).obj;
        }
    }

    private static class QItem extends DoublyLinkedListImpl.Item {
        public Object obj;

        public QItem(Object obj) {
            this.obj = obj;
        }
    }

	public synchronized Object[] toArray() {
		return hash.keySet().toArray();
	}

	public Object[] toArray(Object[] array) {
		return hash.keySet().toArray(array);
	}
	
	public synchronized Object[] toArrayOrdered() {
		Object[] array = new Object[list.size()];
		int x = 0;
		for(Enumeration e = list.reverseElements();e.hasMoreElements();) {
			array[x++] = e.nextElement();
		}
		return array;
	}

	/**
	 * @param array The array to fill in. If it is too small a new array of the same type will be allocated.
	 */
	public synchronized Object[] toArrayOrdered(Object[] array) {
		array = toArray(array);
		int listSize = list.size();
		if(array.length != listSize)
			throw new IllegalStateException("array.length="+array.length+" but list.size="+listSize);
		int x = 0;
		for(Enumeration e = list.reverseElements();e.hasMoreElements();) {
			array[x++] = e.nextElement();
		}
		return array;
	}
	
	public synchronized boolean isEmpty() {
		return hash.isEmpty();
	}
}

