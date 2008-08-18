package freenet.support;

import java.util.Enumeration;
import java.util.NoSuchElementException;

/**
 * DoublyLinkedList implementation.
 * @author tavin
 *
 * TODO: there are still some unimplemented methods
 *       -- it remains to be seen if they are needed at all
 */
public class DoublyLinkedListImpl<T extends DoublyLinkedListImpl.Item<T>> implements DoublyLinkedList<T> {

    protected int size;
    protected T _headptr, _tailptr;

	@Override
    public final DoublyLinkedListImpl<T> clone() {
        return new DoublyLinkedListImpl<T>(this);
    }
    
    /**
     * A new list with no items.
     */
    public DoublyLinkedListImpl() {
        _headptr = (T) new Item();
		_tailptr = (T) new Item();
        _headptr.setParent(this);
        _tailptr.setParent(this);
        clear();
    }

    protected DoublyLinkedListImpl(T _h, T _t, int size) {
        _headptr  = _h;
        _tailptr  = _t;
        _headptr.setParent(this);
        _tailptr.setParent(this);
        this.size = size;
    }


    /**
     * @param impl
     */
    protected DoublyLinkedListImpl(DoublyLinkedListImpl<T> impl) {
        this();
        Enumeration<T> e = impl.forwardElements();
        boolean checked = false;
        for(;e.hasMoreElements();) {
            T oi =  e.nextElement();
			T i = oi.clone();
            if(!checked) {
                checked = true;
                if(!i.getClass().getName().equals(oi.getClass().getName())) {
                    System.err.println("Clone constructor failed for "+oi+": "+i);
                    new Exception("error").printStackTrace();
                }
            }
            this.push(i);
        }
    }

    //=== DoublyLinkedList implementation ======================================

    /**
     * {@inheritDoc}
     */
    public void clear() {
    	// Help to detect removal after clear().
    	// The check in remove() is enough, strictly,
    	// as long as people don't add elements afterwards.
    	DoublyLinkedList.Item pos = _headptr.next;
		DoublyLinkedList.Item opos = _headptr;
    	while(true) {
    		if(pos == _tailptr) break;
    		if(pos == null) break;
    		pos.setParent(null);
    		pos.setPrev(null);
    		opos = pos;
    		pos = pos.getNext();
    		opos.setNext(null);
    	}
        _headptr.next = _tailptr;
        _tailptr.prev = _headptr;
        size = 0;
    }

    /**
     * {@inheritDoc}
     */
    public final int size() {
        return size;
    }

    /**
     * {@inheritDoc}
     */
    public final boolean isEmpty() {
        return size == 0;
    }

    /**
     * {@inheritDoc}
     * @see #forwardElements()
     */
    public final Enumeration elements() {
        return forwardElements();
    }

    /**
     * {@inheritDoc}
     */
    public final T head() {
        return size == 0 ? null : (T) _headptr.next;
    }

    /**
     * {@inheritDoc}
     */
    public final T tail() {
		return size == 0 ? null : (T) _tailptr.prev;
    }


    //=== methods that add/remove items at the head of the list ================
    /**
     * {@inheritDoc}
     */
    public final void unshift(T i) {
        insertNext((T) _headptr, i);
    }
    
    /**
     * {@inheritDoc}
     *  FIXME: unimplemented
     */
    public void unshift(DoublyLinkedList<T> l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }
    /**
     * {@inheritDoc}
     */
    public final T shift() {
		return size == 0 ? null : (T) remove((T) _headptr.next);
    }
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> shift(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

        T i = _headptr;
        for (int m=0; m<n; ++m)
            i = i.getNext();

        T j = i.getNext();
		T newheadptr = (T) new Item<T>();
		T newtailptr = (T) new Item<T>();

        j.setPrev(newheadptr);
        newheadptr.setNext(j);

        i.setNext(newtailptr);
        newtailptr.setPrev(i);

        DoublyLinkedList<T> newlist = new DoublyLinkedListImpl<T>(_headptr, newtailptr, n);
        _headptr = newheadptr;
        _headptr.setParent(this);
        size -= n;
        
        return newlist;
    }
    
    
    //=== methods that add/remove items at the tail of the list ================
    /**
     * {@inheritDoc}
     */
    public final void push(T i) {
        insertPrev((T) _tailptr, i);
    }
    /**
     * {@inheritDoc}
     * FIXME: unimplemented
     */
    public void push(DoublyLinkedList<T> l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }
    /**
     * {@inheritDoc}
     */
    public final T pop() {
		return size == 0 ? null : (T) remove((T) _tailptr.prev);
    }
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> pop(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

        T i = _tailptr;
        for (int m=0; m<n; ++m)
            i = i.getPrev();

        T j = i.getPrev();
		T newtailptr = (T) new Item<T>();
		T newheadptr = (T) new Item<T>();

        j.setNext(newtailptr);
        newtailptr.setPrev(j);
        newtailptr.setParent(this);

        i.setPrev(newheadptr);
        newheadptr.setNext(i);

        DoublyLinkedList<T> newlist = new DoublyLinkedListImpl<T>(newheadptr, _tailptr, n);
        _tailptr = newtailptr;
        size -= n;
        
        return newlist;
    }

    
    //=== testing/looking at neighbor items ====================================
    /**
     * {@inheritDoc}
     */
    public final boolean hasNext(T i) {
		DoublyLinkedList.Item next = i.getNext();
        return (next != null) && (next != _tailptr);
    }
    /**
     * {@inheritDoc}
     */
    public final boolean hasPrev(T i) {
		DoublyLinkedList.Item prev = i.getPrev();
        return (prev != null) && (prev != _headptr);
    }
    /**
     * {@inheritDoc}
     */
    public final T next(T i) {
		T next = i.getNext();
        return next == _tailptr ? null : (T) next;
    }
    /**
     * {@inheritDoc}
     */
    public final T prev(T i) {
		T prev = i.getPrev();
        return prev == _headptr ? null : prev;
    }


    //=== insertion and removal of items =======================================
    
    /**
     * {@inheritDoc}
     */
    public T remove(T i) {
    	if (i.getParent() == null) return null; // not in list
    	if(isEmpty()) {
    		Logger.error(this, "Illegal ERROR: Removing from an empty list!!");
    		throw new IllegalStateException("Illegal ERROR: Removing from an empty list!!");
    	}
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
        T next = i.getNext(), prev = i.getPrev();
        if ((next == null) && (prev == null)) return null;  // not in the list
        if ((next == null) || (prev == null))
        	throw new NullPointerException("next="+next+", prev="+prev); // partially in the list?!
        if((next.getPrev() != i) || (prev.getNext() != i)) {
        	String msg = "Illegal ERROR: i="+i+", next="+next+", next.prev="+next.getPrev()+", prev="+prev+", prev.next="+prev.getNext();
        	Logger.error(this, msg);
        	throw new IllegalStateException(msg);
        }
        prev.setNext(next);
        next.setPrev(prev);
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
    	if (i.getParent() == null)
    		throw new PromiscuousItemException(i, i.getParent()); // different trace to make easier debugging
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, j.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);
        T prev = i.getPrev();
        if (prev == null)
            throw new VirginItemException(i);
        prev.setNext(j);
        j.setPrev(prev);
        i.setPrev(j);
        j.setNext(i);
        j.setParent(this);
        ++size;
    }

    /**
     * {@inheritDoc}
     * FIXME: unimplemented
     */
    public void insertPrev(T i, DoublyLinkedList<T> l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }

    /**
     * {@inheritDoc}
     */
    public void insertNext(T i, T j) {
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, i.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);
        T next = i.getNext();
        if (next == null)
            throw new VirginItemException(i);
        next.setPrev(j);
        j.setNext(next);
        i.setNext(j);
        j.setPrev(i);
        j.setParent(this);
        ++size;
    }

    /**
     * {@inheritDoc}
     * FIXME: unimplemented
     */
    public void insertNext(T i, DoublyLinkedList<T> l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
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
    protected Enumeration<T> reverseElements() {
		return new ReverseWalker();
    }

    private class ForwardWalker implements Enumeration<T> {
		protected DoublyLinkedList.Item next;
        protected ForwardWalker() {
            next = _headptr.getNext();
        }
        protected ForwardWalker(DoublyLinkedList.Item startAt,
                                boolean inclusive) {
            next = (inclusive ? startAt : startAt.getNext());
        }
        public final boolean hasMoreElements() {
            return next != _tailptr;
        }
        public T nextElement() {
            if (next == _tailptr)
                throw new NoSuchElementException();
            T result = (T) next;
            next = next.getNext();
            return result;
        }
    }

    private class ReverseWalker implements Enumeration<T> {
		protected DoublyLinkedList.Item next;
        protected ReverseWalker() {
            next = _tailptr.getPrev();
        }
        protected ReverseWalker(DoublyLinkedList.Item startAt,
                                boolean inclusive) {
            next = (inclusive ? startAt : startAt.getPrev());
        }
        public final boolean hasMoreElements() {
            return next != _headptr;
        }
        public T nextElement() {
            if (next == _headptr)
                throw new NoSuchElementException();
            T result = (T) next;
	    if(next == null) throw new IllegalStateException("next==null");
            next = next.getPrev();
            return result;
        }
    }


    //=== list element ====================================================

    public static class Item<T extends Item<T>> implements DoublyLinkedList.Item<T> {
		private T prev;
		private T next;
        private DoublyLinkedList list;
		@Override
        public T clone() {
            if(getClass() != Item.class)
                throw new RuntimeException("Must implement clone() for "+getClass());
            return (T) new Item();
        }
        public final T getNext() {
            return next;
        }
        public final T setNext(T i) {
			T old = next;
            next = i;
            return old;
        }
        public final T getPrev() {
            return prev;
        }
        public final T setPrev(T i) {
			T old = prev;
            prev = i;
            return old;
        }
		public DoublyLinkedList<T> getParent() {
			return list;
		}
		public DoublyLinkedList<T> setParent(DoublyLinkedList<T> l) {
			DoublyLinkedList old = list;
			list = l;
			return old;
		}
    }
}