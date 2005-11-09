package freenet.client;

import java.util.HashMap;

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
	
}
