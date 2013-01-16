package freenet.support;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedSet;

import freenet.support.Logger.LogLevel;


/**
 * Sorted array of int's.
 */
public class SortedIntSet extends AbstractCollection<Integer> implements SortedSet<Integer> {

	private int[] data;
	private int length;
	private static final int MIN_SIZE = 32;
	
	/**
	 * Default constructor
	 */
	public SortedIntSet() {
		this.data = new int[MIN_SIZE];
		for(int i=0;i<data.length;i++)
			data[i] = Integer.MAX_VALUE;
		length = 0;
	}
	
	public SortedIntSet(int[] input) {
		this.data = input;
		length = input.length;
		verify();
	}
	
	@Override
	public int size() {
		return length;
	}

	/**
	 * Get the smallest item on this set
	 * 
	 * @return the smallest item
	 */
	public synchronized int getFirst() {
		if(length == 0) return -1;
		return data[0];
	}

	/**
	 * Get the largest item on this set
	 * 
	 * @return the largest item
	 */
	public synchronized int getLast() {
		if(length == 0) return -1;
		return data[length-1];
	}

	/**
	 * Check if this set is empty.
	 * 
	 * @param num
	 * @return <code>true</code>, if the set is empty.
	 */
	@Override
	public synchronized boolean isEmpty() {
		return length == 0;
	}

	/**
	 * Check if <code>num</code> exist in this set.
	 * 
	 * @param num
	 * @return <code>true</code>, if <code>num</code> exist.
	 */
	public synchronized boolean contains(int num) {
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
	public synchronized boolean remove(int item) {
		boolean ret = false;
		int x = binarySearch(item);
		if(x >= 0) {
			if(x < length-1)
				System.arraycopy(data, x+1, data, x, length-x-1);
			data[--length] = Integer.MAX_VALUE;
			ret = true;
		}
		if((length*4 < data.length) && (length > MIN_SIZE)) {
			int[] newData = new int[Math.max(data.length/2, MIN_SIZE)];
			System.arraycopy(data, 0, newData, 0, length);
			for(int i=length;i<newData.length;i++)
				newData[i] = Integer.MAX_VALUE;
			data = newData;
		}
		
		assert(verify());
		return ret;
	}

	/**
	 * verify internal state. can be removed without ill effect
	 */
	private synchronized boolean verify() { // TODO: Move to a unit test.
		int lastItem = -1;
		for(int i=0;i<length;i++) {
			int item = data[i];
			if(i>0) {
				if(item <= lastItem)
					throw new IllegalStateException("Verify failed!");
			}
			lastItem = item;
		}
		for(int i=length;i<data.length;i++)
			if(data[i] != Integer.MAX_VALUE)
				throw new IllegalStateException("length="+length+", data.length="+data.length+" but ["+i+"] != Integer.MAX_VALUE");
		
		return true;
	}

	/**
	 * Add the item, if it (or an item of the same number) is not already
	 * present.
	 * 
	 * @return <code>true</code>, if we added the item.
	 */ 
	public synchronized boolean push(int num) {
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
	public synchronized void add(int num) {
		int x = binarySearch(num);
		if(x >= 0) throw new IllegalArgumentException(); // already exists
		// insertion point
		x = -x-1;
		push(num, x);
	}

	private synchronized void push(int num, int x) {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR) Logger.minor(this, "Insertion point: "+x+" length "+length+" data.length "+data.length);
		// Move the data
		if(length == data.length) {
			int[] newData = Arrays.copyOf(data, Math.max(length*2, 4));
			if(logMINOR) Logger.minor(this, "Expanding from "+length+" to "+newData.length);
			for(int i=length;i<newData.length;i++)
				newData[i] = Integer.MAX_VALUE;
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
	public int removeFirst() {
		int val = getFirst();
		remove(val);
		return val;
	}

	/**
	 * Clear this set
	 */
	@Override
	public synchronized void clear() {
		data = new int[MIN_SIZE];
		for(int i=0;i<data.length;i++)
			data[i] = Integer.MAX_VALUE;
		length = 0;
	}

	/**
	 * Get a sorted array of all items
	 * 
	 * @return sorted array of all items
	 */
	public synchronized int[] toIntArray() {
		return Arrays.copyOf(data, length);
	}

	/**
	 * Get a sorted array of all items
	 * 
	 * @return sorted array of all items
	 */
	public synchronized int[] toArrayRaw() {
		if(length == data.length) return data;
		return toIntArray();
	}

	private int binarySearch(int key) {
		return Arrays.binarySearch(data, 0, length, key);
	}

	@Override
	public Comparator<? super Integer> comparator() {
		return null;
	}

	@Override
	public Integer first() {
		return getFirst();
	}

	@Override
	public SortedSet<Integer> headSet(Integer arg0) {
		throw new UnsupportedOperationException(); // FIXME
	}

	@Override
	public Integer last() {
		return getLast();
	}

	@Override
	public SortedSet<Integer> subSet(Integer arg0, Integer arg1) {
		throw new UnsupportedOperationException(); // FIXME
	}

	@Override
	public SortedSet<Integer> tailSet(Integer arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean add(Integer arg0) {
		return push(arg0.intValue());
	}

	@Override
	public boolean contains(Object arg0) {
		if(arg0 instanceof Integer) {
			int x = (Integer)arg0;
			return contains(x);
		}
		return false;
	}

	@Override
	public Iterator<Integer> iterator() {
		return new Iterator<Integer>() {
			
			int x = 0;
			int last = -1;
			boolean hasLast = false;

			@Override
			public boolean hasNext() {
				return x < length;
			}

			@Override
			public Integer next() {
				if(x >= length) throw new NoSuchElementException();
				hasLast = true;
				last = data[x++];
				return last;
			}

			@Override
			public void remove() {
				if(!hasLast)
					throw new IllegalStateException();
				SortedIntSet.this.remove(last);
				x--;
			}
			
		};
	}

	@Override
	public boolean remove(Object arg0) {
		if(arg0 instanceof Integer) {
			return remove(((Integer)arg0).intValue());
		}
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> arg0) {
		throw new UnsupportedOperationException(); // FIXME
	}

	@Override
	public boolean retainAll(Collection<?> arg0) {
		throw new UnsupportedOperationException(); // FIXME
	}

}
