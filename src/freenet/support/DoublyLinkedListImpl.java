package freenet.support;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * DoublyLinkedList implementation. See DoublyLinkedList for an explanation
 * when to use this.
 * 
 * @author tavin
 *
 * TODO: there are still some unimplemented methods
 *       -- it remains to be seen if they are needed at all
 */
public class DoublyLinkedListImpl<T extends DoublyLinkedList.Item<? extends T>> implements DoublyLinkedList<T> {

    protected int size;
	protected T _firstItem, _lastItem;

	//@Override
	//public final DoublyLinkedListImpl<T> clone() {
	//    return new DoublyLinkedListImpl<T>(this);
	//}
    
    /**
     * A new list with no items.
     */
    public DoublyLinkedListImpl() {
        clear();
    }

	protected DoublyLinkedListImpl(T _h, T _t, int size) {
		_firstItem = _h;
		_lastItem = _t;
        
        T i = _firstItem;
        while (i != null ) {
        	i.setParent(this);
			i = i.getNext();
        }
        
        this.size = size;
    }

	//	/**
	//	 * 
	//	 * XXX: FIXME this doesn't really work. it use .clone() method
	//	 * 
	//	 * @param impl
	//	 */
	//    protected DoublyLinkedListImpl(DoublyLinkedListImpl<T> impl) {
	//        this();
	//        Enumeration<T> e = impl.forwardElements();
	//        boolean checked = false;
	//        for(;e.hasMoreElements();) {
	//			DoublyLinkedListImpl.Item oi = (DoublyLinkedListImpl.Item) e.nextElement();
	//			T i = (T) oi.clone();
	//            if(!checked) {
	//                checked = true;
	//                if(!i.getClass().getName().equals(oi.getClass().getName())) {
	//                    System.err.println("Clone constructor failed for "+oi+": "+i);
	//                    new Exception("error").printStackTrace();
	//                }
	//            }
	//            this.push(i);
	//        }
	//    }

    //=== DoublyLinkedList implementation ======================================

    /**
     * {@inheritDoc}
     */
    public void clear() {
    	// Help to detect removal after clear().
    	// The check in remove() is enough, strictly,
    	// as long as people don't add elements afterwards.
		if (_firstItem == null)
			return;

		T pos = _firstItem;
		T opos;

    	while(true) {
    		if(pos == null) break;
    		pos.setParent(null);
    		pos.setPrev(null);
    		opos = pos;
			pos = pos.getNext();
    		opos.setNext(null);
    	}

		_firstItem = _lastItem = null;
        size = 0;
    }

    /**
     * {@inheritDoc}
     */
    public final int size() {
		assert size != 0 || (_firstItem == null && _lastItem == null);
        return size;
    }

    /**
     * {@inheritDoc}
     */
    public final boolean isEmpty() {
		assert size != 0 || (_firstItem == null && _lastItem == null);
        return size == 0;
    }

    /**
     * {@inheritDoc}
     * @see #forwardElements()
     */
    public final Enumeration<T> elements() {
        return forwardElements();
    }
    
	public boolean contains(T item) {
    	for(T i : this) {
    		if(i.equals(item))
    			return true;
    	}
    	return false;
    }
    
    /**
     * {@inheritDoc}
     */
	public final T head() {
		return size == 0 ? null : _firstItem;
    }

    /**
     * {@inheritDoc}
     */
	public final T tail() {
		return size == 0 ? null : _lastItem;
    }


    //=== methods that add/remove items at the head of the list ================
    /**
     * {@inheritDoc}
     */
	public final void unshift(T i) {
		insertNext(null, i);
    }
    
    /**
     * {@inheritDoc}
     */
	public final T shift() {
		return size == 0 ? null : remove(_firstItem);
    }
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> shift(int n) {
        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

		T i = _firstItem;
		for (int m = 0; m < n - 1; ++m)
			i = i.getNext();

		T newTailItem = i;
		T newFirstItem = newTailItem.getNext();
		newTailItem.setNext(null);

		DoublyLinkedList<T> newlist = new DoublyLinkedListImpl<T>(_firstItem, newTailItem, n);

		if (newFirstItem != null) {
			newFirstItem.setPrev(null);
			_firstItem = newFirstItem;
		} else {
			_firstItem = _lastItem = null;
		}
        size -= n;
        
        return newlist;
    }
    
    
    //=== methods that add/remove items at the tail of the list ================
    /**
     * {@inheritDoc}
     */
	public final void push(T i) {
		insertPrev(null, i);
    }
    
    /**
     * {@inheritDoc}
     */
	public final T pop() {
		return size == 0 ? null : remove(_lastItem);
    }

    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> pop(int n) {
        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

		T i = _lastItem;
		for (int m = 0; m < n - 1; ++m)
			i = i.getPrev();

		T newFirstItem = i;
		T newLastItem = newFirstItem.getPrev();
		newFirstItem.setPrev(null);

		DoublyLinkedList<T> newlist = new DoublyLinkedListImpl<T>(newFirstItem, _lastItem, n);

		if (newLastItem != null) {
			newLastItem.setNext(null);
			_lastItem = newLastItem;
		} else
			_firstItem = _lastItem = null;
        size -= n;
        
        return newlist;
    }

    
    //=== testing/looking at neighbor items ====================================
    /**
     * {@inheritDoc}
     */
	public final boolean hasNext(T i) {
		T next = i.getNext();
		return next != null;
    }
    /**
     * {@inheritDoc}
     */
	public final boolean hasPrev(T i) {
		T prev = i.getPrev();
		return prev != null;
    }
    /**
     * {@inheritDoc}
     */
	public final T next(T i) {
		T next = i.getNext();
		return next;
    }
    /**
     * {@inheritDoc}
     */
	public final T prev(T i) {
		T prev = i.getPrev();
		return prev;
    }


    //=== insertion and removal of items =======================================
    
    /**
     * {@inheritDoc}
     */
	public T remove(T i) {
    	if (i.getParent() == null || isEmpty())
			return null; // not in list
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());

		T next = i.getNext();
		T prev = i.getPrev();

		if ((next == null) && (prev == null)) // only item in list
			assert size == 1;

		if (next == null) { // last item
			assert _lastItem == i;
			_lastItem = prev;
		} else {
			assert next.getPrev() == i;
			next.setPrev(prev);
		}

		if (prev == null) { // first item
			assert _firstItem == i;
			_firstItem = next;
		} else {
			assert prev.getNext() == i;
			prev.setNext(next);
        }

        i.setNext(null);
        i.setPrev(null);
        --size;
        i.setParent(null);
        return i;
    }

    /**
     * {@inheritDoc}
     */
	public void insertPrev(T i, T j) {
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, j.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);

		if (i == null) {
			// insert as tail
			j.setPrev(_lastItem);
			j.setNext(null);
			j.setParent(this);
			if (_lastItem != null) {
				_lastItem.setNext(j);
				_lastItem = j;
			} else {
				_firstItem = _lastItem = j;
			}

			++size;
		} else {
			// insert in middle
			if (i.getParent() == null)
				throw new PromiscuousItemException(i, i.getParent()); // different trace to make easier debugging
			if (i.getParent() != this)
				throw new PromiscuousItemException(i, i.getParent());
			T prev = i.getPrev();
			if (prev == null) {
				if (i != _firstItem)
					throw new VirginItemException(i);
				_firstItem = j;
			} else
				prev.setNext(j);
			j.setPrev(prev);
			i.setPrev(j);
			j.setNext(i);
			j.setParent(this);

			++size;
		}
    }

    /**
     * {@inheritDoc}
     */
	public void insertNext(T i, T j) {
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, i.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);

		if (i == null) {
			// insert as head
			j.setPrev(null);
			j.setNext(_firstItem);
			j.setParent(this);

			if (_firstItem != null) {
				_firstItem.setPrev(j);
				_firstItem = j;
			} else {
				_firstItem = _lastItem = j;
			}

			++size;
		} else {
			if (i.getParent() != this)
				throw new PromiscuousItemException(i, i.getParent());
			T next = i.getNext();
			if (next == null) {
				if (i != _lastItem)
					throw new VirginItemException(i);
				_lastItem = j;
			} else
				next.setPrev(j);
			j.setNext(next);
			i.setNext(j);
			j.setPrev(i);
			j.setParent(this);

			++size;
		}
    }

    //=== Walkable implementation ==============================================
    
    /**
     * @return  an Enumeration of list elements from head to tail
     */
    private Enumeration<T> forwardElements() {
        return new ForwardWalker();
    }

    /**
     * @return  an Enumeration of list elements from tail to head
     */
    public Enumeration<T> reverseElements() {
        return new ReverseWalker();
    }

	private class ForwardWalker implements Enumeration<T> {
		protected T next;
        protected ForwardWalker() {
			next = _firstItem;
        }
        public final boolean hasMoreElements() {
			return next != null;
        }

		public T nextElement() {
			if (next == null)
                throw new NoSuchElementException();
			T result = next;
            next = next.getNext();
			return result;
        }
    }

	private class ReverseWalker implements Enumeration<T> {
		protected T next;
        protected ReverseWalker() {
			next = _lastItem;
        }
        public final boolean hasMoreElements() {
			return next != null;
        }

		public T nextElement() {
			if (next == null)
                throw new NoSuchElementException();
			T result = next;
	    if(next == null) throw new IllegalStateException("next==null");
            next = next.getPrev();
			return result;
        }
    }


    //=== list element ====================================================

	public static class Item<T extends DoublyLinkedListImpl.Item<?>> implements DoublyLinkedList.Item<T> {
		private T prev;
		private T next;
		private DoublyLinkedList<? super T> list;

		//		@Override
		//		public T clone() {
		//            if(getClass() != Item.class)
		//                throw new RuntimeException("Must implement clone() for "+getClass());
		//			return (T) new Item<T>();
		//        }

		public final T getNext() {
            return next;
        }

		@SuppressWarnings("unchecked")
		public final T setNext(DoublyLinkedList.Item<?> i) {
			T old = next;
			next = (T) i;
            return old;
        }

		public final T getPrev() {
            return prev;
        }

		@SuppressWarnings("unchecked")
		public final T setPrev(DoublyLinkedList.Item<?> i) {
			T old = prev;
			prev = (T) i;
            return old;
        }

		public DoublyLinkedList<? super T> getParent() {
			return list;
		}

		public DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l) {
			DoublyLinkedList<? super T> old = list;
			list = l;
			return old;
		}
    }

	public Iterator<T> iterator() {
		return new Iterator<T>() {
			private Enumeration<T> e = forwardElements();

			public boolean hasNext() {
				return e.hasMoreElements();
			}

			public T next() {
				if(!hasNext())
					throw new NoSuchElementException();
				
				return e.nextElement();
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
			
		};
	}
}