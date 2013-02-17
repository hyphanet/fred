package freenet.support;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/** ArrayList variant for Integer's for long-term storage. Uses a resizing
 * array but does not allow any extra space, unlike ArrayList, because we
 * want to absolutely minimise memory usage. Should only be used for
 * structures where the memory usage is more important than the copying.
 * Also, this uses an int[], as ArrayList<Integer> is rather inefficient.
 * @author toad
 */
public class TrivialIntArrayList implements List<Integer> {
	
	private int[] list;

	@Override
	public boolean add(Integer arg0) {
		return insert(arg0.intValue());
	}
	
	public boolean insert(int arg0) {
		int[] newList = Arrays.copyOf(list, list.length+1);
		newList[list.length] = arg0;
		list = newList;
		return true;
	}

	@Override
	public void add(int index, Integer arg1) {
		insert(index, arg1.intValue());
	}
	
	public void insert(int index, int arg1) {
		int[] newList = new int[list.length+1];
		if(index > 0)
			System.arraycopy(list, 0, newList, 0, index);
		newList[index] = arg1;
		if(index < list.length)
			System.arraycopy(list, index, newList, index+1, list.length-index);
	}

	@Override
	public boolean addAll(Collection<? extends Integer> arg0) {
		// FIXME not very efficient.
		for(Integer i : arg0)
			add(i);
		return true;
	}

	@Override
	public boolean addAll(int arg0, Collection<? extends Integer> arg1) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void clear() {
		list = new int[0];
	}

	@Override
	public boolean contains(Object arg0) {
		return indexOf(arg0) >= 0;
	}

	@Override
	public boolean containsAll(Collection<?> arg0) {
		// FIXME inefficient
		for(Object o : arg0) {
			if(!contains(o)) return false;
		}
		return true;
	}

	@Override
	public Integer get(int index) {
		return list[index];
	}
	
	public int fetch(int index) {
		return list[index];
	}

	@Override
	public int indexOf(Object arg0) {
		if(!(arg0 instanceof Integer))
			return -1;
		return search((Integer)arg0);
	}

	public int search(int x) {
		for(int i=0;i<list.length;i++) {
			if(list[i] == x) return i;
		}
		return -1;
	}

	public int backwardsSearch(int x) {
		for(int i=list.length-1;i>=0;i--) {
			if(list[i] == x) return i;
		}
		return -1;
	}

	@Override
	public boolean isEmpty() {
		return list.length == 0;
	}

	@Override
	public Iterator<Integer> iterator() {
		return new Iterator<Integer>() {
			
			int x = 0;

			@Override
			public boolean hasNext() {
				return x < list.length;
			}

			@Override
			public Integer next() {
				if(x >= list.length) throw new NoSuchElementException();
				return list[x++];
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
			
		};
	}

	@Override
	public int lastIndexOf(Object arg0) {
		if(!(arg0 instanceof Integer))
			return -1;
		return backwardsSearch((Integer)arg0);
	}

	@Override
	public ListIterator<Integer> listIterator() {
		return listIterator(0);
	}

	@Override
	public ListIterator<Integer> listIterator(int arg0) {
		return new MyListIterator(arg0);
	}
	
	private class MyListIterator implements ListIterator<Integer> {
		
		MyListIterator(int x) {
			index = x;
		}
			
		/** Points to next, so previous is --index */
		private int index;

		@Override
		public void add(Integer arg0) {
			TrivialIntArrayList.this.add(index, arg0);
		}
		
		@Override
		public boolean hasNext() {
			return index < list.length;
		}
		
		@Override
		public boolean hasPrevious() {
			return index > 0;
		}
		
		@Override
		public Integer next() {
			if(index >= list.length)
				throw new NoSuchElementException();
			return list[index++];
		}
		
		@Override
		public int nextIndex() {
			return index;
		}
		
		@Override
		public Integer previous() {
			if(index <= 0)
				throw new NoSuchElementException();
			// See javadocs for ListIterator. FIXME add unit tests.
			return list[--index];
		}
		
		@Override
		public int previousIndex() {
			return index-1;
		}
		
		@Override
		public void remove() {
			TrivialIntArrayList.this.remove(index);
		}
		
		@Override
		public void set(Integer arg0) {
			if(index >= list.length) throw new IllegalStateException();
			list[index] = arg0;
		}
		
	}

	@Override
	public boolean remove(Object arg0) {
		int index = indexOf(arg0);
		if(index == -1)
			return false;
		remove(index);
		return true;
	}

	@Override
	public Integer remove(int index) {
		return removeAt(index);
	}
	
	public int removeAt(int index) {
		// FIXME add unit tests
		if(index < 0 || index > size())
			throw new IndexOutOfBoundsException();
		if(index == list.length)
			return removeEnd();
		int retval = list[index];
		int[] newList = new int[list.length-1];
		if(index > 0)
			System.arraycopy(list, 0, newList, 0, index);
		// Unconditional thanks to removeEnd() above.
		System.arraycopy(list, index+1, newList, index, list.length-index-1);
		list = newList;
		return retval;
	}
	
	public int removeEnd() {
		int newLength = list.length-1;
		int[] newList = Arrays.copyOf(list, newLength);
		int ret = list[newLength];
		list = newList;
		return ret;
	}

	@Override
	public boolean removeAll(Collection<?> arg0) {
		boolean didSomething = false;
		for(Object arg : arg0) {
			while(remove(arg)) {
				didSomething = true;
			}
		}
		return didSomething;
	}

	@Override
	public boolean retainAll(Collection<?> arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Integer set(int index, Integer arg1) {
		if(index < 0 || index >= size())
			throw new IndexOutOfBoundsException();
		int ret = list[index];
		list[index] = arg1;
		return ret;
	}

	@Override
	public int size() {
		return list.length;
	}

	@Override
	public List<Integer> subList(int arg0, int arg1) {
		// FIXME this is really neat, and it's not optional, so we should implement it.
		// However it will involve significant work!
		throw new UnsupportedOperationException();
	}
	
	public int[] toIntArray() {
		return Arrays.copyOf(list, list.length);
	}

	// FIXME only use this if you have a *really* good reason!!!
	public int[] rawIntArray() {
		return list;
	}

	@Override
	public Object[] toArray() {
		Object[] ret = new Object[list.length];
		for(int i=0;i<list.length;i++)
			ret[i] = list[i];
		return ret;
	}

	@Override
	public <T> T[] toArray(T[] arg0) {
		// FIXME this isn't optional either but it's tricky to implement due to reflection.
		throw new UnsupportedOperationException();
	}

}
