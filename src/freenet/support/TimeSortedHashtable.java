package freenet.support;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * Variant on LRUHashtable which provides an efficient how-many-since-time-T operation.
 */
public class TimeSortedHashtable<T extends Comparable>  {
	public TimeSortedHashtable() {
		this.elements = new TreeSet<Comparable>(new MyComparator());
		this.valueToElement = new HashMap<T, Element<T>>();
	}
	
	private static class Element<T extends Comparable> implements Comparable<Element<T>> {
		
		Element(long t, T v) {
			time = t;
			value = v;
			if(value == null)
				throw new NullPointerException();
		}
		
		long time;
		final T value;
		
		public int compareTo(Element<T> o) {
			if(time > o.time) return 1;
			if(time < o.time) return -1;
			return value.compareTo(o.value);
		}
	}

	private static class MyComparator implements Comparator /* <Long || Element<T>> */{

		public int compare(Object arg0, Object arg1) {
			if(arg0 instanceof Long && arg1 instanceof Long) return ((Long)arg0).compareTo((Long)arg1);
			if (arg0 instanceof Element && arg1 instanceof Element)
				return ((Element) arg0).compareTo((Element) arg1);
			// Comparing a Long with an Element, because we are searching for an Element by the value of a Long.
			// Hence we do not need to consider the element value.
			if(arg0 instanceof Long) {
				long l = ((Long)arg0).longValue();
				Element e = (Element)arg1;
				if(l > e.time) return 1;
				if(l < e.time) return -1;
				return 0;
			} else {
				// arg1 instanceof Long
				Element e = (Element)arg0;
				long l = ((Long)arg1).longValue();
				if(e.time > l) return 1;
				if(e.time < l) return -1;
				return 0;
			}
		}
		
	}
	
    private final TreeSet<Comparable> /* <Long || Element<T>> */elements;
	private final HashMap<T, Element<T>> valueToElement;
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     * @param now 
     */
    public final synchronized void push(T value, long now) {
    	assert(elements.size() == valueToElement.size());
    	
    	Element<T> e = valueToElement.get(value);
    	
    	if(e == null) {
    		e = new Element<T>(now, value);
    		elements.add(e);
    		valueToElement.put(value, e);
    	} else {
    		elements.remove(e);
    		e.time = now;
    		elements.add(e);
    	}
    	
    	assert(elements.size() == valueToElement.size());
    } 

    public final int size() {
        return elements.size();
    }
    
    public final synchronized boolean removeValue(T value) {
    	assert(elements.size() == valueToElement.size());
    	Element<T> e = valueToElement.remove(value);
    	if(e == null) return false;
    	elements.remove(e);
    	assert(elements.size() == valueToElement.size());
    	return true;
    }
    
    public final synchronized boolean containsValue(T key) {
    	return valueToElement.containsKey(key);
    }
    
    /**
     * Note that this does not automatically promote the key. You have
     * to do that by hand with push(key, value).
     */
    public final synchronized long getTime(T value) {
		Element<T> e = valueToElement.remove(value);
    	if(e == null) return -1;
    	return e.time;
    }
    
    /**
     * @return The set of times after the given time.
     */
    private final synchronized Long[] timesAfter(long t) {
    	Set<Comparable> s = elements.tailSet(t);
    	
    	Long[] times = new Long[s.size()];
    	int x = 0;
    	for(Iterator<Comparable> i = s.iterator();i.hasNext();) {
    		times[x++] = ((Element<T>) i.next()).time;
    	}
    	
    	return times;
    }
    
    /**
     * @return The set of values after the given time.
     */
	// FIXME this is broken if timestamp != -1
    private final synchronized <E extends Comparable> E[] valuesAfter(long t, E[] values) {
    	Set<Comparable> s = elements.tailSet(t);
    	
    	int x = 0;
    	for(Iterator<Comparable> i = s.iterator();i.hasNext();) {
    		values[x++] = (E) ((Element<T>) i.next()).value;
    	}
    	
    	return values;
    }

	public synchronized int countValuesAfter(long t) {
    	Set<Comparable> s = elements.tailSet(t);
    	
    	return s.size();
	}
    
    /**
     * Remove all entries before the given time.
     */
	public final synchronized void removeBefore(long t) {
    	assert(elements.size() == valueToElement.size());
    	Set<Comparable> s = elements.headSet(t);
    	
    	for(Iterator<Comparable> i = s.iterator();i.hasNext();) {
    		Element<T> e = (Element<T>) i.next();
    		valueToElement.remove(e.value);
    		i.remove();
    	}
    	
    	assert(elements.size() == valueToElement.size());
	}

	// FIXME this is broken if timestamp != -1
	public final synchronized <E extends Comparable> Object[] pairsAfter(long timestamp, E[] valuesArray) {
		return new Object[] { valuesAfter(timestamp, valuesArray), timesAfter(timestamp) };
	}
}