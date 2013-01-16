package freenet.support;

import java.util.Arrays;
import java.util.Comparator;

import com.db4o.ObjectContainer;

import freenet.support.Logger.LogLevel;

/**
 * Map of an integer to an element, based on a sorted Vector.
 * Note that we have to shuffle data around, so this is slowish if it gets big.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class SortedVectorByNumber {

	private IntNumberedItem[] data;
	private int length;
	private static final Comparator<Object> comparator = new SimpleIntNumberedItemComparator(true);
	private static final int MIN_SIZE = 4;
	private final boolean persistent;
	
	public SortedVectorByNumber(boolean persistent) {
		this.data = new IntNumberedItem[MIN_SIZE];
		length = 0;
		this.persistent = persistent;
	}
	
	public synchronized IntNumberedItem getFirst() {
		if(length == 0) return null;
		return data[0];
	}

	public synchronized boolean isEmpty() {
		return length == 0;
	}

	public synchronized IntNumberedItem get(int retryCount, ObjectContainer container) {
		if(persistent) {
			for(int i=0;i<length;i++)
				container.activate(data[i], 1);
		}
		int x = Arrays.binarySearch(data, retryCount, comparator);
		if(x >= 0)
			return data[x];
		return null;
	}

	public synchronized void remove(int item, ObjectContainer container) {
		if(persistent) {
			for(int i=0;i<length;i++)
				container.activate(data[i], 1);
		}
		int x = Arrays.binarySearch(data, item, comparator);
		if(x >= 0) {
			if(x < length-1)
				System.arraycopy(data, x+1, data, x, length-x-1);
			data[--length] = null;
		}
		if((length*4 < data.length) && (length > MIN_SIZE)) {
			data = Arrays.copyOf(data, Math.max(length*2, MIN_SIZE));
		}
		if(persistent) container.store(this);
		
		assert(verify(container));
	}

	private synchronized boolean verify(ObjectContainer container) {
		IntNumberedItem lastItem = null;
		for(int i=0;i<length;i++) {
			IntNumberedItem item = data[i];
			if(persistent)
				container.activate(data[i], 1);
			if(i>0) {
				if(item.getNumber() <= lastItem.getNumber())
					throw new IllegalStateException("Verify failed! at "+i+" this="+item.getNumber()+" but last="+lastItem.getNumber());
			}
			lastItem = item;
		}
		for(int i=length;i<data.length;i++)
			if(data[i] != null)
				throw new IllegalStateException("length="+length+", data.length="+data.length+" but ["+i+"] != null");
		
		return true;
	}

	/**
	 * Add the item, if it (or an item of the same number) is not already present.
	 * @return True if we added the item.
	 */
	public synchronized boolean push(IntNumberedItem grabber, ObjectContainer container) {
		if(persistent) {
			for(int i=0;i<length;i++)
				container.activate(data[i], 1);
		}
		int x = Arrays.binarySearch(data, grabber.getNumber(), comparator);
		if(x >= 0) return false;
		// insertion point
		x = -x-1;
		push(grabber, x, container);
		return true;
	}
	
	public synchronized void add(IntNumberedItem grabber, ObjectContainer container) {
		if(persistent) {
			for(int i=0;i<length;i++)
				container.activate(data[i], 1);
		}
		int x = Arrays.binarySearch(data, grabber.getNumber(), comparator);
		if(x >= 0) {
			if(grabber != data[x])
				throw new IllegalArgumentException(); // already exists
			else return;
		}
		// insertion point
		x = -x-1;
		push(grabber, x, container);
	}

	private synchronized void push(IntNumberedItem grabber, int x, ObjectContainer container) {
		if(persistent) {
			for(int i=0;i<length;i++)
				container.activate(data[i], 1);
		}
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR) Logger.minor(this, "Insertion point: "+x);
		// Move the data
		if(length == data.length) {
			if(logMINOR) Logger.minor(this, "Expanding from "+length+" to "+length*2);
			data = Arrays.copyOf(data, length*2);
		}
		if(x < length)
			System.arraycopy(data, x, data, x+1, length-x);
		data[x] = grabber;
		length++;
		if(persistent)
			container.store(this);
		
		assert(verify(container));
	}

	public synchronized int count() {
		return length;
	}

	public synchronized IntNumberedItem getByIndex(int index) {
		if(index > length) return null;
		return data[index];
	}

	public int getNumberByIndex(int idx) {
		if(idx >= length) return Integer.MAX_VALUE;
		return data[idx].getNumber();
	}

	public boolean persistent() {
		return persistent;
	}

	public void removeFrom(ObjectContainer container) {
		for(int i=0;i<data.length;i++) {
			if(data[i] != null) throw new IllegalStateException("Still have contents: "+i);
		}
		container.delete(this);
	}

}
