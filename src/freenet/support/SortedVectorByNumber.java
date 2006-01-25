package freenet.support;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Map of an integer to an element, based on a sorted Vector.
 * Note that we have to shuffle data around, so this is slowish if it gets big.
 */
public class SortedVectorByNumber {

	private IntNumberedItem[] data;
	private int length;
	private static final Comparator comparator = new SimpleIntNumberedItemComparator(true);
	private static final int MIN_SIZE = 32;
	
	public SortedVectorByNumber() {
		this.data = new IntNumberedItem[MIN_SIZE];
		length = 0;
	}
	
	public synchronized IntNumberedItem getFirst() {
		if(length == 0) return null;
		return data[0];
	}

	public boolean isEmpty() {
		return length == 0;
	}

	public synchronized IntNumberedItem get(int retryCount) {
		int x = Arrays.binarySearch(data, new Integer(retryCount), comparator);
		if(x >= 0)
			return data[x];
		return null;
	}

	public void remove(int item) {
		int x = Arrays.binarySearch(data, new Integer(item), comparator);
		if(x >= 0) {
			if(x < length-1)
				System.arraycopy(data, x+1, data, x, length-x-1);
			data[length--] = null;
		}
		if(length < 4*data.length && length > MIN_SIZE) {
			IntNumberedItem[] newData = new IntNumberedItem[Math.max(length*2, MIN_SIZE)];
			System.arraycopy(data, 0, newData, 0, length);
			data = newData;
		}
	}

	public void add(IntNumberedItem grabber) {
		int x = Arrays.binarySearch(data, new Integer(grabber.getNumber()), comparator);
		if(x >= 0) throw new IllegalArgumentException(); // already exists
		// insertion point
		x = -x-1;
		Logger.minor(this, "Insertion point: "+x);
		// Move the data
		if(length == data.length) {
			Logger.minor(this, "Expanding from "+length+" to "+length*2);
			IntNumberedItem[] newData = new IntNumberedItem[length*2];
			System.arraycopy(data, 0, newData, 0, data.length);
		}
		if(x < length)
			System.arraycopy(data, x, data, x+1, length-x);
		data[x] = grabber;
		length++;
	}

}
