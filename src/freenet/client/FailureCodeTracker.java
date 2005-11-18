package freenet.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Essentially a map of integer to incrementible integer.
 * FIXME maybe move this to support, give it a better name?
 */
public class FailureCodeTracker {

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
	
	public synchronized String toVerboseString() {
		StringBuffer sb = new StringBuffer();
		Collection values = map.values();
		Iterator i = values.iterator();
		while(i.hasNext()) {
			Integer x = (Integer) i.next();
			Item val = (Item) map.get(x);
			sb.append(x);
			sb.append('=');
			sb.append(val.x);
			sb.append('\n');
		}
		return sb.toString();
	}
	
}
