package freenet.support;

import java.util.Enumeration;
import java.util.Hashtable;

public class LRUHashtable<K, V> {

    /*
     * I've just converted this to using the DLList and Hashtable
     * this makes it Hashtable time instead of O(N) for push and
     * remove, and Hashtable time instead of O(1) for pop.  Since
     * push is by far the most done operation, this should be an
     * overall improvement.
     */
    private final DoublyLinkedListImpl<V> list = new DoublyLinkedListImpl<V>();
    private final Hashtable<K, QItem<K, V>> hash = new Hashtable<K, QItem<K, V>>();
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     */
    public final synchronized void push(K key, V value) {
        QItem<K,V> insert = hash.get(key);
        if (insert == null) {
            insert = new QItem<K, V>(key, value);
            hash.put(key,insert);
        } else {
        	insert.value = value;
            list.remove(insert);
        }
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Pushed "+insert+" ( "+key+ ' ' +value+" )");

        list.unshift(insert);
    } 

    /**
     *  @return Least recently pushed key.
     */
    public final synchronized Object popKey() {
        if ( list.size() > 0 ) {
            return (	hash.remove(((QItem) list.pop()).obj)).obj;
        } else {
            return null;
        }
    }

    /**
     * @return Least recently pushed value.
     */
    public final synchronized Object popValue() {
        if ( list.size() > 0 ) {
            return (	hash.remove(((QItem) list.pop()).obj)).value;
        } else {
            return null;
        }
    }
    
	public final synchronized Object peekValue() {
        if ( list.size() > 0 ) {
        	if(hash == null) throw new NullPointerException();
        	QItem tail = (QItem) list.tail();
        	Object object = tail.obj;
        	QItem i = (QItem) hash.get(object);
        	if(i == null) {
        		String obToString = "(toString() threw)";
        		try {
        			obToString = object.toString();
        		} catch (Throwable t) {
        			// Ignore
        		}
        		Logger.error(this, "Lookup failed in LRUHashtable for "+obToString+" in LRUHashtable - maybe an object was deactivated or its hash code changed some other way????");
        		return null;
        	}
        	return i.value;
            //return ((QItem)hash.get(((QItem)list.tail()).obj)).value;
        } else {
            return null;
        }
	}

    public final int size() {
        return list.size();
    }
    
    public final synchronized boolean removeKey(K key) {
	QItem<K,V> i = (hash.remove(key));
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
    public final synchronized boolean containsKey(K key) {
        return hash.containsKey(key);
    }
    
    /**
     * Note that this does not automatically promote the key. You have
     * to do that by hand with push(key, value).
     */
    public final synchronized V get(K key) {
    	QItem<K,V> q = hash.get(key);
    	if(q == null) return null;
    	return q.value;
    }
    
    public Enumeration keys() {
        return new ItemEnumeration();
    }
    
    public Enumeration values() {
    	return new ValuesEnumeration();
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

    private class ValuesEnumeration implements Enumeration {
        private Enumeration source = list.reverseElements();
       
        public boolean hasMoreElements() {
            return source.hasMoreElements();
        }

        public Object nextElement() {
            return ((QItem) source.nextElement()).value;
        }
    }

    public static class QItem<K, V> extends DoublyLinkedListImpl.Item<V> {
        public K obj;
        public V value;

        public QItem(K key, V val) {
            this.obj = key;
            this.value = val;
        }
        
		@Override
        public String toString() {
        	return super.toString()+": "+obj+ ' ' +value;
        }
    }

	public boolean isEmpty() {
		return list.isEmpty();
	}

	/**
	 * Note that unlike the java.util versions, this will not reallocate (hence it doesn't return), 
	 * so pass in an appropriately big array, and make sure you hold the lock!
	 * @param entries
	 * @return
	 */
	public synchronized void valuesToArray(V[] entries) {
		Enumeration<V> values = values();
		int i=0;
		while(values.hasMoreElements())
			entries[i++] = values.nextElement();
	}

	public synchronized void clear() {
		list.clear();
		hash.clear();
	}
}
