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
public class DoublyLinkedListImpl<T> implements DoublyLinkedList<T>{

    protected int size;
    protected DoublyLinkedListImpl.Item<T> _headptr, _tailptr;

	@Override
    public final DoublyLinkedListImpl<T> clone() {
        return new DoublyLinkedListImpl<T>(this);
    }
    
    /**
     * A new list with no items.
     */
    public DoublyLinkedListImpl() {
        _headptr = new Item<T>();
        _tailptr = new Item<T>();
        _headptr.setParent(this);
        _tailptr.setParent(this);
        clear();
    }

    protected DoublyLinkedListImpl(DoublyLinkedListImpl.Item<T> _h, DoublyLinkedListImpl.Item<T> _t, int size) {
        _headptr  = _h;
        _tailptr  = _t;
        _headptr.setParent(this);
        _tailptr.setParent(this);
        
        DoublyLinkedList.Item i = _headptr;
        while (i != null ) {
        	i.setParent(this);
        	i = i.getNext();
        }
        
        this.size = size;
    }


    /**
     * @param impl
     */
    protected DoublyLinkedListImpl(DoublyLinkedListImpl impl) {
        this();
        Enumeration<DoublyLinkedListImpl.Item<T>> e = impl.forwardElements();
        boolean checked = false;
        for(;e.hasMoreElements();) {
            DoublyLinkedListImpl.Item<T> oi = e.nextElement();
            DoublyLinkedList.Item<T> i = oi.clone();
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
    	DoublyLinkedList.Item<T> pos = _headptr.next;
    	DoublyLinkedList.Item<T> opos = _headptr;
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
    public final Enumeration<T> elements() {
        return forwardElements();
    }

    /**
     * {@inheritDoc}
     */
    public final DoublyLinkedList.Item<T> head() {
        return size == 0 ? null : _headptr.next;
    }

    /**
     * {@inheritDoc}
     */
    public final DoublyLinkedList.Item<T> tail() {
        return size == 0 ? null : _tailptr.prev;
    }


    //=== methods that add/remove items at the head of the list ================
    /**
     * {@inheritDoc}
     */
    public final void unshift(DoublyLinkedList.Item<T> i) {
        insertNext(_headptr, i);
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
    public final DoublyLinkedList.Item<T> shift() {
        return size == 0 ? null : remove(_headptr.next);
    }
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> shift(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

        DoublyLinkedList.Item<T> i = _headptr;
        for (int m=0; m<n; ++m)
            i = i.getNext();

        DoublyLinkedList.Item<T> j = i.getNext();
        Item<T> newheadptr = new Item<T>();
        Item<T> newtailptr = new Item<T>();

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
    public final void push(DoublyLinkedList.Item<T> i) {
        insertPrev(_tailptr, i);
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
    public final DoublyLinkedList.Item<T> pop() {
        return size == 0 ? null : remove(_tailptr.prev);
    }
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList<T> pop(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl<T>();

        DoublyLinkedList.Item<T> i = _tailptr;
        for (int m=0; m<n; ++m)
            i = i.getPrev();

        DoublyLinkedList.Item<T> j = i.getPrev();
        DoublyLinkedListImpl.Item<T> newtailptr = new Item<T>();
        DoublyLinkedListImpl.Item<T> newheadptr = new Item<T>();

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
    public final boolean hasNext(DoublyLinkedList.Item<T> i) {
        DoublyLinkedList.Item<T> next = i.getNext();
        return (next != null) && (next != _tailptr);
    }
    /**
     * {@inheritDoc}
     */
    public final boolean hasPrev(DoublyLinkedList.Item<T> i) {
        DoublyLinkedList.Item<T> prev = i.getPrev();
        return (prev != null) && (prev != _headptr);
    }
    /**
     * {@inheritDoc}
     */
    public final DoublyLinkedList.Item<T> next(DoublyLinkedList.Item<T> i) {
        DoublyLinkedList.Item<T> next = i.getNext();
        return next == _tailptr ? null : next;
    }
    /**
     * {@inheritDoc}
     */
    public final DoublyLinkedList.Item<T> prev(DoublyLinkedList.Item<T> i) {
        DoublyLinkedList.Item<T> prev = i.getPrev();
        return prev == _headptr ? null : prev;
    }


    //=== insertion and removal of items =======================================
    
    /**
     * {@inheritDoc}
     */
    public DoublyLinkedList.Item<T> remove(DoublyLinkedList.Item<T> i) {
    	if (i.getParent() == null || isEmpty())
			return null; // not in list
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
        DoublyLinkedList.Item<T> next = i.getNext(), prev = i.getPrev();
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
    public void insertPrev(DoublyLinkedList.Item<T> i, DoublyLinkedList.Item<T> j) {
    	if (i.getParent() == null)
    		throw new PromiscuousItemException(i, i.getParent()); // different trace to make easier debugging
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, j.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);
        DoublyLinkedList.Item<T> prev = i.getPrev();
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
    public void insertPrev(DoublyLinkedList.Item<T> i, DoublyLinkedList<T> l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }

    /**
     * {@inheritDoc}
     */
    public void insertNext(DoublyLinkedList.Item<T> i, DoublyLinkedList.Item<T> j) {
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, i.getParent());
        if ((j.getNext() != null) || (j.getPrev() != null))
            throw new PromiscuousItemException(j);
        DoublyLinkedList.Item next = i.getNext();
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
    public void insertNext(DoublyLinkedList.Item<T> i, DoublyLinkedList<T> l) {
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

    private class ForwardWalker<T extends DoublyLinkedListImpl.Item<T>> implements Enumeration<DoublyLinkedList.Item<T>> {
        protected DoublyLinkedList.Item<T> next;
        protected ForwardWalker() {
            next = _headptr.getNext();
        }
        protected ForwardWalker(DoublyLinkedList.Item<T> startAt,
                                boolean inclusive) {
            next = (inclusive ? startAt : startAt.getNext());
        }
        public final boolean hasMoreElements() {
            return next != _tailptr;
        }
        public DoublyLinkedList.Item<T> nextElement() {
            if (next == _tailptr)
                throw new NoSuchElementException();
            DoublyLinkedList.Item<T> result = next;
            next = next.getNext();
            return result;
        }
    }

    private class ReverseWalker<T extends DoublyLinkedList.Item<T>> implements Enumeration<DoublyLinkedList.Item<T>> {
        protected DoublyLinkedList.Item<T> next;
        protected ReverseWalker() {
            next = _tailptr.getPrev();
        }
        protected ReverseWalker(DoublyLinkedList.Item<T> startAt,
                                boolean inclusive) {
            next = (inclusive ? startAt : startAt.getPrev());
        }
        public final boolean hasMoreElements() {
            return next != _headptr;
        }
        public DoublyLinkedList.Item<T> nextElement() {
            if (next == _headptr)
                throw new NoSuchElementException();
            DoublyLinkedList.Item<T> result = next;
	    if(next == null) throw new IllegalStateException("next==null");
            next = next.getPrev();
            return result;
        }
    }


    //=== list element ====================================================

    public static class Item<T> implements DoublyLinkedList.Item<T> {
        private DoublyLinkedList.Item<T> prev;
        private DoublyLinkedList.Item<T> next;
        private DoublyLinkedList list;
		@Override
        public DoublyLinkedList.Item<T> clone() {
            if(getClass() != Item.class)
                throw new RuntimeException("Must implement clone() for "+getClass());
            return new Item<T>();
        }
        public final DoublyLinkedList.Item getNext() {
            return next;
        }
        public final DoublyLinkedList.Item setNext(DoublyLinkedList.Item<T> i) {
            DoublyLinkedList.Item<T> old = next;
            next = i;
            return old;
        }
        public final DoublyLinkedList.Item getPrev() {
            return prev;
        }
        public final DoublyLinkedList.Item setPrev(DoublyLinkedList.Item<T> i) {
            DoublyLinkedList.Item<T> old = prev;
            prev = i;
            return old;
        }
		public DoublyLinkedList getParent() {
			return list;
		}
		public DoublyLinkedList setParent(DoublyLinkedList<T> l) {
			DoublyLinkedList old = list;
			list = l;
			return old;
		}
    }
}