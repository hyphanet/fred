package freenet.support;

import java.util.Arrays;

import freenet.support.Logger.LogLevel;


/**
 * Sorted array of long's.
 */
public class SortedLongSet {

	private long[] data;
	private int length;
	private static final int MIN_SIZE = 32;
	
	/**
	 * Default constructor
	 */
	public SortedLongSet() {
		this.data = new long[MIN_SIZE];
		for(int i=0;i<data.length;i++)
			data[i] = Long.MAX_VALUE;
		length = 0;
	}
	
	/**
	 * Get the smallest item on this set
	 * 
	 * @return the smallest item
	 */
	public synchronized long getFirst() {
		if(length == 0) return -1;
		return data[0];
	}

	/**
	 * Check if this set is empty.
	 * 
	 * @param num
	 * @return <code>true</code>, if the set is empty.
	 */
	public synchronized boolean isEmpty() {
		return length == 0;
	}

	/**
	 * Check if <code>num</code> exist in this set.
	 * 
	 * @param num
	 * @return <code>true</code>, if <code>num</code> exist.
	 */
	public synchronized boolean contains(long num) {
		int x = binarySearch(num);
		if(x >= 0)
			return true;
		else
			return false;
	}

	/**
	 * Remove an item
	 * 
	 * @param item
	 *            the item to be removed
	 */
	public synchronized void remove(long item) {
		int x = binarySearch(item);
		if(x >= 0) {
			if(x < length-1)
				System.arraycopy(data, x+1, data, x, length-x-1);
			data[--length] = Long.MAX_VALUE;
		}
		if((length*4 < data.length) && (length > MIN_SIZE)) {
			long[] newData = new long[Math.max(data.length/2, MIN_SIZE)];
			System.arraycopy(data, 0, newData, 0, length);
			for(int i=length;i<newData.length;i++)
				newData[i] = Long.MAX_VALUE;
			data = newData;
		}
		
		assert(verify());
	}

	/**
	 * verify internal state. can be removed without ill effect
	 */
	private synchronized boolean verify() { // TODO: Move to a unit test.
		long lastItem = -1;
		for(int i=0;i<length;i++) {
			long item = data[i];
			if(i>0) {
				if(item <= lastItem)
					throw new IllegalStateException("Verify failed!");
			}
			lastItem = item;
		}
		for(int i=length;i<data.length;i++)
			if(data[i] != Long.MAX_VALUE)
				throw new IllegalStateException("length="+length+", data.length="+data.length+" but ["+i+"] != Long.MAX_VALUE");
		
		return true;
	}

	/**
	 * Add the item, if it (or an item of the same number) is not already
	 * present.
	 * 
	 * @return <code>true</code>, if we added the item.
	 */ 
	public synchronized boolean push(long num) {
		int x = binarySearch(num);
		if(x >= 0) return false;
		// insertion point
		x = -x-1;
		push(num, x);
		return true;
	}

	/**
	 * Add the item.
	 * 
	 * @throws {@link IllegalArgumentException}
	 *             if the item already exist
	 * @return <code>true</code>, if we added the item.
	 */ 
	public synchronized void add(long num) {
		int x = binarySearch(num);
		if(x >= 0) throw new IllegalArgumentException(); // already exists
		// insertion point
		x = -x-1;
		push(num, x);
	}

	private synchronized void push(long num, int x) {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR) Logger.minor(this, "Insertion point: "+x+" length "+length+" data.length "+data.length);
		// Move the data
		if(length == data.length) {
			if(logMINOR) Logger.minor(this, "Expanding from "+length+" to "+length*2);
			long[] newData = Arrays.copyOf(data, length*2);
			for(int i=length;i<newData.length;i++)
				newData[i] = Long.MAX_VALUE;
			data = newData;
		}
		if(x < length)
			System.arraycopy(data, x, data, x+1, length-x);
		data[x] = num;
		length++;
		
		assert(verify());
	}

	/**
	 * Remove and return the smallest item
	 * 
	 * @return the smallest item
	 */
	public long removeFirst() {
		long val = getFirst();
		remove(val);
		return val;
	}

	/**
	 * Clear this set
	 */
	public synchronized void clear() {
		data = new long[MIN_SIZE];
		for(int i=0;i<data.length;i++)
			data[i] = Long.MAX_VALUE;
		length = 0;
	}

	/**
	 * Get a sorted array of all items
	 * 
	 * @return sorted array of all items
	 */
	public synchronized long[] toArray() {
		return Arrays.copyOf(data, length);
	}

	private int binarySearch(long key) {
		return Arrays.binarySearch(data, 0, length, key);
	}
}
