package freenet.support;

import java.util.Arrays;

/**
 * Sorted array of long's.
 */
public class SortedLongSet {

	private long[] data;
	private int length;
	private static final int MIN_SIZE = 32;
	
	public SortedLongSet() {
		this.data = new long[MIN_SIZE];
		for(int i=0;i<data.length;i++)
			data[i] = Long.MAX_VALUE;
		length = 0;
	}
	
	public synchronized long getFirst() {
		if(length == 0) return -1;
		return data[0];
	}

	public synchronized boolean isEmpty() {
		return length == 0;
	}

	public synchronized boolean contains(long num) {
		int x = Arrays.binarySearch(data, num);
		if(x >= 0)
			return true;
		else
			return false;
	}

	public synchronized void remove(long item) {
		int x = Arrays.binarySearch(data, item);
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
		verify();
	}

	private synchronized void verify() {
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
	}

	/**
	 * Add the item, if it (or an item of the same number) is not already present.
	 * @return True if we added the item.
	 */
	public synchronized boolean push(long num) {
		int x = Arrays.binarySearch(data, num);
		if(x >= 0) return false;
		// insertion point
		x = -x-1;
		push(num, x);
		return true;
	}
	
	public synchronized void add(long num) {
		int x = Arrays.binarySearch(data, num);
		if(x >= 0) throw new IllegalArgumentException(); // already exists
		// insertion point
		x = -x-1;
		push(num, x);
	}

	private synchronized void push(long num, int x) {
		Logger.minor(this, "Insertion point: "+x+" length "+length+" data.length "+data.length);
		// Move the data
		if(length == data.length) {
			Logger.minor(this, "Expanding from "+length+" to "+length*2);
			long[] newData = new long[length*2];
			System.arraycopy(data, 0, newData, 0, data.length);
			for(int i=length;i<newData.length;i++)
				newData[i] = Long.MAX_VALUE;
			data = newData;
		}
		if(x < length)
			System.arraycopy(data, x, data, x+1, length-x);
		data[x] = num;
		length++;
		verify();
	}

	public long removeFirst() {
		long val = getFirst();
		remove(val);
		return val;
	}

}
