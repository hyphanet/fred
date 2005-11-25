package freenet.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Essentially a map of integer to incrementible integer.
 * FIXME maybe move this to support, give it a better name?
 */
public class FailureCodeTracker {

	public final boolean insert;
	
	public FailureCodeTracker(boolean insert) {
		this.insert = insert;
	}
	
	public class Item {
		int x;
	}

	final HashMap map = new HashMap();

	public synchronized void inc(int k) {
		Integer key = new Integer(k);
		Item i = (Item) map.get(key);
		if(i == null)
			map.put(key, i = new Item());
		i.x++;
	}

	public synchronized void inc(Integer key, int val) {
		Item i = (Item) map.get(key);
		if(i == null)
			map.put(key, i = new Item());
		i.x+=val;
	}
	
	public synchronized String toVerboseString() {
		StringBuffer sb = new StringBuffer();
		Collection values = map.keySet();
		Iterator i = values.iterator();
		while(i.hasNext()) {
			Integer x = (Integer) i.next();
			Item val = (Item) map.get(x);
			String s = insert ? InserterException.getMessage(x.intValue()) : FetchException.getMessage(x.intValue());
			sb.append(val.x);
			sb.append('\t');
			sb.append(s);
			sb.append('\n');
		}
		return sb.toString();
	}

	public synchronized FailureCodeTracker merge(FailureCodeTracker accumulatedFatalErrorCodes) {
		Iterator keys = map.keySet().iterator();
		while(keys.hasNext()) {
			Integer k = (Integer) keys.next();
			Item item = (Item) map.get(k);
			inc(k, item.x);
		}
		return this;
	}
	
}
