package freenet.support;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.SortedSet;


/**
 * Sorted array of int's.
 *
 * This implementation is synchronized on all operations.
 * This class does not properly respect the Set interface, and the value -1 is treated as a special
 * number for some operations.
 *
 * @deprecated Use ArrayList<Integer>, TreeSet<Integer> or int[] depending on performance needs
 */
@Deprecated
public class SortedIntSet extends AbstractCollection<Integer> implements SortedSet<Integer> {

	private final ArrayList<Integer> data;
	
	/**
	 * Default constructor
	 */
	public SortedIntSet() {
		this.data = new ArrayList<Integer>();
	}
	
	public SortedIntSet(int[] input) {
		assertSorted(input);
		data = new ArrayList<Integer>(input.length);
		for (int i : input) {
			data.add(i);
		}
	}
	
	@Override
	public synchronized int size() {
		return data.size();
	}

	/**
	 * Get the smallest item on this set
	 * 
	 * @return the smallest item, or -1 if the set is empty
	 */
	public synchronized int getFirst() {
		return data.isEmpty() ? -1 : data.get(0);
	}

	/**
	 * Get the largest item on this set
	 * 
	 * @return the largest item, or -1 if the set is empty
	 */
	public synchronized int getLast() {
		return data.isEmpty() ? -1 : data.get(data.size() - 1);
	}

	/**
	 * Check if this set is empty.
	 * 
	 * @param num
	 * @return <code>true</code>, if the set is empty.
	 */
	@Override
	public synchronized boolean isEmpty() {
		return data.isEmpty();
	}

	/**
	 * Check if <code>num</code> exist in this set.
	 * 
	 * @param num
	 * @return <code>true</code>, if <code>num</code> exist.
	 */
	public synchronized boolean contains(int num) {
		int x = binarySearch(num);
		return x >= 0;
	}

	/**
	 * Remove an item
	 * 
	 * @param item
	 *            the item to be removed
	 */
	public synchronized boolean remove(int item) {
		int x = binarySearch(item);
		if(x >= 0) {
			return data.remove(x) != null;
		}
		return false;
	}

	private void assertSorted(int[] input) {
		if (input.length <= 1) {
			return;
		}
		for (int i = 1; i < input.length; i++) {
			if (input[i-1] > input[i]) {
				throw new IllegalStateException("Input must be sorted");
			}
		}
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
		if(x >= 0) {
			throw new IllegalArgumentException(); // already exists
		}
		// insertion point
		x = -x-1;
		push(num, x);
	}

	private synchronized void push(int num, int x) {
		data.add(x, num);
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
		data.clear();
	}

	/**
	 * Get a sorted array of all items
	 * 
	 * @return sorted array of all items
	 */
	public synchronized int[] toIntArray() {
		int[] result = new int[data.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = data.get(i);
		}
		return result;
	}

	/**
	 * Get a sorted array of all items
	 * 
	 * @return sorted array of all items
	 */
	public synchronized int[] toArrayRaw() {
		return toIntArray();
	}

	private int binarySearch(int key) {
		return Collections.binarySearch(data, key);
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
		return data.iterator();
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
