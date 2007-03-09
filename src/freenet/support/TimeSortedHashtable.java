package freenet.support;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * Variant on LRUHashtable which provides an efficient how-many-since-time-T operation.
 */
public class TimeSortedHashtable {

	private class Element {
		
		Element(long t, Comparable v) {
			time = t;
			value = v;
		}
		
		long time;
		final Comparable value;
		
		public int compareTo(Object o) {
			Element e = (Element) o;
			if(time > e.time) return 1;
			if(time < e.time) return -1;
			return value.compareTo(e.value);
		}
	}

	private class MyComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			if(arg0 instanceof Long && arg1 instanceof Long) return ((Long)arg0).compareTo(arg1);
			if(arg0 instanceof Element && arg1 instanceof Element) return ((Element)arg0).compareTo(arg1);
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
	
    private final TreeSet elements = new TreeSet(new MyComparator());
    private final HashMap valueToElement = new HashMap();

    public final void push(Comparable value) {
    	push(value, System.currentTimeMillis());
    }
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     * @param now 
     */
    public final synchronized void push(Comparable value, long now) {

    	assert(elements.size() == valueToElement.size());
    	
    	Element e = (Element) valueToElement.get(value);
    	
    	if(e == null) {
    		e = new Element(now, value);
    		elements.add(e);
    		valueToElement.put(value, e);
    	} else {
    		elements.remove(e);
    		e.time = now;
    		elements.add(e);
    	}
    	
    	assert(elements.size() == valueToElement.size());
    } 

    /**
     * @return Least recently pushed value.
     */
    public final synchronized Comparable popValue() {
    	assert(elements.size() == valueToElement.size());
    	
    	Element e = (Element) elements.first();
    	valueToElement.remove(e.value);
    	
    	assert(elements.size() == valueToElement.size());
    	return e.value;
    }
    
	public final synchronized Object peekValue() {
    	return ((Element) elements.first()).value;
	}
    
    public final int size() {
        return elements.size();
    }
    
    public final synchronized boolean removeValue(Comparable value) {
    	assert(elements.size() == valueToElement.size());
    	Element e = (Element) valueToElement.remove(value);
    	if(e == null) return false;
    	elements.remove(e);
    	assert(elements.size() == valueToElement.size());
    	return true;
    }
    
    public final synchronized boolean containsValue(Comparable key) {
    	return valueToElement.containsKey(key);
    }
    
    /**
     * Note that this does not automatically promote the key. You have
     * to do that by hand with push(key, value).
     */
    public final synchronized long getTime(Object value) {
    	Element e = (Element) valueToElement.remove(value);
    	if(e == null) return -1;
    	return e.time;
    }
    
    /**
     * @return The set of times after the given time.
     */
    public final synchronized Long[] timesAfter(long t) {
    	Long time = new Long(t);
    	
    	Set s = elements.tailSet(time);
    	
    	Long[] times = new Long[s.size()];
    	int x = 0;
    	for(Iterator i = s.iterator();i.hasNext();) {
    		times[x++] = new Long(((Element)i.next()).time);
    	}
    	
    	return times;
    }
    
    /**
     * @return The set of values after the given time.
     */
    public final synchronized Comparable[] valuesAfter(long t, Comparable[] values) {
    	Long time = new Long(t);
    	
    	Set s = elements.tailSet(time);
    	
    	int x = 0;
    	for(Iterator i = s.iterator();i.hasNext();) {
    		values[x++] = ((Element)i.next()).value;
    	}
    	
    	return values;
    }

	public int countValuesAfter(long t) {
    	Long time = new Long(t);
    	
    	Set s = elements.tailSet(time);
    	
    	return s.size();
	}
    
    /**
     * Remove all entries before the given time.
     */
	public final synchronized void removeBefore(long t) {
    	assert(elements.size() == valueToElement.size());
    	
    	Long time = new Long(t);
    	Set s = elements.headSet(time);
    	
    	for(Iterator i = s.iterator();i.hasNext();) {
    		Element e = (Element) i.next();
    		valueToElement.remove(e.value);
    		i.remove();
    	}
    	
    	assert(elements.size() == valueToElement.size());
	}

	public final synchronized Object[] pairsAfter(long timestamp, Comparable[] valuesArray) {
		return new Object[] { valuesAfter(timestamp, valuesArray), timesAfter(timestamp) };
	}

}
