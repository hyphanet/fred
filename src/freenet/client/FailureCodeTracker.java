/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Essentially a map of integer to incrementible integer.
 * FIXME maybe move this to support, give it a better name? Be careful, it's a persistent object, use the
 * db4o migration tools, or derive it from something in support?
 */
public class FailureCodeTracker implements Cloneable {

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
		Iterator<String> i = fs.directSubsetNameIterator();
		while(i.hasNext()) {
			String name = i.next();
			SimpleFieldSet f = fs.subset(name);
			// We ignore the Description, if there is one; we just want the count
			int num = Integer.parseInt(name);
			int count = Integer.parseInt(f.get("Count"));
			if(count < 0) throw new IllegalArgumentException("Count < 0");
			map.put(Integer.valueOf(num), new Item(count));
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

	private HashMap<Integer, Item> map;

	public synchronized void inc(int k) {
		if(k == 0) {
			Logger.error(this, "Can't increment 0, not a valid failure mode", new Exception("error"));
		}
		if(map == null) map = new HashMap<Integer, Item>();
		Integer key = k;
		Item i = map.get(key);
		if(i == null)
			map.put(key, i = new Item());
		i.x++;
		total++;
	}

	public synchronized void inc(Integer k, int val) {
		if(k == 0) {
			Logger.error(this, "Can't increment 0, not a valid failure mode", new Exception("error"));
		}
		if(map == null) map = new HashMap<Integer, Item>();
		Integer key = k;
		Item i = map.get(key);
		if(i == null)
			map.put(key, i = new Item());
		i.x+=val;
		total += val;
	}
	
	public synchronized String toVerboseString() {
		if(map == null) return super.toString()+":empty";
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Item> e : map.entrySet()) {
			Integer x = e.getKey();
			Item val = e.getValue();
			String s = insert ? InsertException.getMessage(x.intValue()) : FetchException.getMessage(x.intValue());
			sb.append(val.x);
			sb.append('\t');
			sb.append(s);
			sb.append('\n');
		}
		return sb.toString();
	}

	@Override
	public synchronized String toString() {
		if(map == null) return super.toString()+":empty";
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append(':');
		if(map.size() == 0) sb.append("empty");
		else if(map.size() == 1) {
			sb.append("one:");
			Integer code = (Integer) (map.keySet().toArray())[0];
			sb.append(code);
			sb.append('=');
			sb.append((map.get(code)).x);
		} else if(map.size() < 10) {
			boolean needComma = false;
			for(Map.Entry<Integer, Item> entry : map.entrySet()) {
				if(needComma)
					sb.append(',');
				sb.append(entry.getKey()); // code
				sb.append('=');
				sb.append(entry.getValue().x);
				needComma = true;
			}
		} else {
			sb.append(map.size());
		}
		return sb.toString();
	}
	
	/**
	 * Merge codes from another tracker into this one.
	 */
	public synchronized FailureCodeTracker merge(FailureCodeTracker source) {
		if(source.map == null) return this;
		if(map == null) map = new HashMap<Integer, Item>();
		for (Map.Entry<Integer, Item> e : source.map.entrySet()) {
			Integer k = e.getKey();
			Item item = e.getValue();
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
		SimpleFieldSet sfs = new SimpleFieldSet(false);
		if(map != null) {
		for (Map.Entry<Integer, Item> e : map.entrySet()) {
			Integer k = e.getKey();
			Item item = e.getValue();
			int code = k.intValue();
			// prefix.num.Description=<code description>
			// prefix.num.Count=<count>
			if(verbose)
				sfs.putSingle(Integer.toString(code)+".Description", 
						insert ? InsertException.getMessage(code) : FetchException.getMessage(code));
			sfs.put(Integer.toString(code)+".Count", item.x);
		}
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
		if(map == null) return false;
		for (Map.Entry<Integer, Item> e : map.entrySet()) {
			Integer code = e.getKey();
			if(e.getValue().x == 0) continue;
			if(insert) {
				if(InsertException.isFatal(code.intValue())) return true;
			} else {
				if(FetchException.isFatal(code.intValue())) return true;
			}
		}
		return false;
	}

	public void merge(InsertException e) {
		if(!insert) throw new IllegalArgumentException("This is not an insert yet merge("+e+") called!");
		if(e.errorCodes != null)
			merge(e.errorCodes);
		inc(e.getMode());
	}

	public synchronized boolean isEmpty() {
		return map == null || map.isEmpty();
	}

	public void removeFrom(ObjectContainer container) {
		Item[] items;
		Integer[] ints;
		synchronized(this) {
			items = map == null ? null : map.values().toArray(new Item[map.size()]);
			ints = map == null ? null : map.keySet().toArray(new Integer[map.size()]);
			if(map != null) map.clear();
		}
		if(items != null)
			for(int i=0;i<items.length;i++) {
				container.delete(items[i]);
				container.delete(ints[i]);
			}
		if(map != null) {
			container.activate(map, 5);
			container.delete(map);
		}
		container.delete(this);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		if(map != null) container.activate(map, 5);
	}
	
	/** Copy the FailureCodeTracker. We implement Cloneable to shut up findbugs, but Object.clone() won't
	 * work because it's a shallow copy, so we implement it with merge(). */
	@Override
	public FailureCodeTracker clone() {
		FailureCodeTracker tracker = new FailureCodeTracker(insert);
		tracker.merge(this);
		return tracker;
	}

	public void storeTo(ObjectContainer container) {
		// Must store to at least depth 2 because of map.
		container.ext().store(this, 5);
	}

	public synchronized boolean isDataFound() {
		for(Map.Entry<Integer, Item> entry : map.entrySet()) {
			if(entry.getValue().x <= 0) continue;
			if(FetchException.isDataFound(entry.getKey(), null)) return true;
		}
		return false;
	}
}
