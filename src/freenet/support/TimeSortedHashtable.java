package freenet.support;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * Variant on LRUMap which provides an efficient how-many-since-time-T operation.
 */
public class TimeSortedHashtable<T extends Comparable<T>>  {
	public TimeSortedHashtable() {
		this.elements = new TreeSet<Element<T>>();
		this.valueToElement = new HashMap<T, Element<T>>();
	}
	
	private static class Element<T extends Comparable<T>> implements Comparable<Element<T>> {
		Element(long t, T v) {
			time = t;
			value = v;
		}
		
		long time;
		final T value;
		
		@Override
		public int compareTo(Element<T> o) {
			if(time > o.time) return 1;
			if(time < o.time) return -1;
			if (value == null && o.value == null) return 0;
			if (value == null && o.value != null) return 1;
			if (value != null && o.value == null) return -1;
			return value.compareTo(o.value);
		}
	}

	
    private final TreeSet<Element<T>> elements;
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
    	if (value == null)
    		throw new NullPointerException();
    	
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
     * Count the number of values after specified timestamp
     * @param timestamp
     * @return value count
     */
	public synchronized int countValuesAfter(long t) {
    	Set<Element<T>> s = elements.tailSet(new Element<T>(t, null));
    	
    	return s.size();
	}
    
    /**
     * Remove all entries on or before the given time.
     */
	public final synchronized void removeBefore(long t) {
    	assert(elements.size() == valueToElement.size());
    	Set<Element<T>> s = elements.headSet(new Element<T>(t, null));
    	
    	for(Iterator<Element<T>> i = s.iterator();i.hasNext();) {
    		Element<T> e =  i.next();
    		valueToElement.remove(e.value);
    		i.remove();
    	}
    	
    	assert(elements.size() == valueToElement.size());
	}

	// FIXME this is broken if timestamp != -1
	public final synchronized Object[] pairsAfter(long timestamp, T[] valuesArray) {
    	Set<Element<T>> s = elements.tailSet(new Element<T>(timestamp, null));
    	Long[] timeArray = new Long[s.size()];
    	
    	int i = 0;
    	for (Element<T> e : s) {
    		timeArray[i] = e.time;
    		valuesArray[i] = e.value;
    		i++;
    	}
    	
		return new Object[] { valuesArray, timeArray };
	}
}
