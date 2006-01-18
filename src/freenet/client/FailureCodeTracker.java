package freenet.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import freenet.support.SimpleFieldSet;

/**
 * Essentially a map of integer to incrementible integer.
 * FIXME maybe move this to support, give it a better name?
 */
public class FailureCodeTracker {

	public final boolean insert;
	private int total;
	
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
		total++;
	}

	public synchronized void inc(Integer key, int val) {
		Item i = (Item) map.get(key);
		if(i == null)
			map.put(key, i = new Item());
		i.x+=val;
		total += val;
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

	/**
	 * Merge codes from another tracker into this one.
	 */
	public synchronized FailureCodeTracker merge(FailureCodeTracker source) {
		Iterator keys = source.map.keySet().iterator();
		while(keys.hasNext()) {
			Integer k = (Integer) keys.next();
			Item item = (Item) source.map.get(k);
			inc(k, item.x);
		}
		return this;
	}

	public void merge(FetchException e) {
		if(insert) throw new IllegalStateException("Merging a FetchException in an insert!");
		if(e.errorCodes != null) {
			merge(e.errorCodes);
		}
		// Increment mode anyway, so we get the splitfile error as well.
		inc(e.mode);
	}

	public synchronized int totalCount() {
		return total;
	}

	/** Copy verbosely to a SimpleFieldSet */
	public synchronized void copyToFieldSet(SimpleFieldSet sfs, String prefix) {
		Iterator keys = map.keySet().iterator();
		while(keys.hasNext()) {
			Integer k = (Integer) keys.next();
			Item item = (Item) map.get(k);
			int code = k.intValue();
			// prefix.num.Description=<code description>
			// prefix.num.Count=<count>
			sfs.put(prefix+Integer.toHexString(code)+".Description", 
					insert ? InserterException.getMessage(code) : FetchException.getMessage(code));
			sfs.put(prefix+Integer.toHexString(code)+".Count", Integer.toHexString(item.x));
		}
	}

	public synchronized boolean isOneCodeOnly() {
		return map.size() == 1;
	}

	public synchronized int getFirstCode() {
		return ((Integer) map.keySet().toArray()[0]).intValue();
	}
	
}
