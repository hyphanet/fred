/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
	
	/**
	 * Create a FailureCodeTracker from a SimpleFieldSet.
	 * @param isInsert Whether this is an insert.
	 * @param fs The SimpleFieldSet containing the FieldSet (non-verbose) form of 
	 * the tracker.
	 */
	public FailureCodeTracker(boolean isInsert, SimpleFieldSet fs) {
		this.insert = isInsert;
		Iterator i = fs.directSubsetNameIterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			SimpleFieldSet f = fs.subset(name);
			// We ignore the Description, if there is one; we just want the count
			int num = Integer.parseInt(name);
			int count = Integer.parseInt(f.get("Count"));
			if(count < 0) throw new IllegalArgumentException("Count < 0");
			map.put(new Integer(num), new Item(count));
			total += count;
		}
	}
	
	private static class Item {
		Item(int count) {
			this.x = count;
		}

		Item() {
			this.x = 0;
		}

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
	public synchronized SimpleFieldSet toFieldSet(boolean verbose) {
		SimpleFieldSet sfs = new SimpleFieldSet();
		Iterator keys = map.keySet().iterator();
		while(keys.hasNext()) {
			Integer k = (Integer) keys.next();
			Item item = (Item) map.get(k);
			int code = k.intValue();
			// prefix.num.Description=<code description>
			// prefix.num.Count=<count>
			if(verbose)
				sfs.putSingle(Integer.toString(code)+".Description", 
						insert ? InserterException.getMessage(code) : FetchException.getMessage(code));
			sfs.put(Integer.toString(code)+".Count", item.x);
		}
		return sfs;
	}

	public synchronized boolean isOneCodeOnly() {
		return map.size() == 1;
	}

	public synchronized int getFirstCode() {
		return ((Integer) map.keySet().toArray()[0]).intValue();
	}

	public synchronized boolean isFatal(boolean insert) {
		Iterator i = map.keySet().iterator();
		while(i.hasNext()) {
			Integer code = (Integer) i.next();
			if(((Item)map.get(code)).x == 0) continue;
			if(insert) {
				if(InserterException.isFatal(code.intValue())) return true;
			} else {
				if(FetchException.isFatal(code.intValue())) return true;
			}
		}
		return false;
	}

	public void merge(InserterException e) {
		if(!insert) throw new IllegalArgumentException("This is not an insert yet merge("+e+") called!");
		if(e.errorCodes != null)
			merge(e.errorCodes);
		inc(e.getMode());
	}

	public boolean isEmpty() {
		return map.isEmpty();
	}
	
}
