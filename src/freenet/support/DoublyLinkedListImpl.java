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
public class DoublyLinkedListImpl implements DoublyLinkedList {

    protected int size;
    protected Item _headptr, _tailptr;

    public final Object clone() {
        return new DoublyLinkedListImpl(this);
    }
    
    /**
     * A new list with no items.
     */
    public DoublyLinkedListImpl() {
        _headptr = new Item();
        _tailptr = new Item();
        _headptr.setParent(this);
        _tailptr.setParent(this);
        clear();
    }

    protected DoublyLinkedListImpl(Item _h, Item _t, int size) {
        _headptr  = _h;
        _tailptr  = _t;
        _headptr.setParent(this);
        _tailptr.setParent(this);
        this.size = size;
    }


    /**
     * @param impl
     */
    protected DoublyLinkedListImpl(DoublyLinkedListImpl impl) {
        this();
        Enumeration e = impl.forwardElements();
        boolean checked = false;
        for(;e.hasMoreElements();) {
            Item oi = (Item)e.nextElement();
            Item i = (Item) oi.clone();
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
     * Reset the size of the list to zero.
     */
    public void clear() {
    	// Help to detect removal after clear().
    	// The check in remove() is enough, strictly,
    	// as long as people don't add elements afterwards.
    	Enumeration e = forwardElements();
    	DoublyLinkedList.Item pos = _headptr.next;
    	DoublyLinkedList.Item opos = _headptr;
    	while(true) {
    		if(pos == null) break;
    		pos.setParent(null);
    		pos.setPrev(null);
    		opos = pos;
    		pos = pos.getNext();
    		opos.setNext(null);
    		if(pos == _tailptr) break;
    	}
        _headptr.next = _tailptr;
        _tailptr.prev = _headptr;
        size = 0;
    }

    /**
     * @return  the number of items in the list
     */
    public final int size() {
        return size;
    }

    /**
     * @return true  if there are no items in the list
     */
    public final boolean isEmpty() {
        return size == 0;
    }

    /**
     * @see #forwardElements()
     */
    public final Enumeration elements() {
        return forwardElements();
    }

    /**
     * @return  the item at the head of the list, or null if empty
     */
    public final DoublyLinkedList.Item head() {
        return size == 0 ? null : _headptr.next;
    }

    /**
     * @return  the item at the tail of the list, or null if empty
     */
    public final DoublyLinkedList.Item tail() {
        return size == 0 ? null : _tailptr.prev;
    }


    //=== methods that add/remove items at the head of the list ================
    
    public final void unshift(DoublyLinkedList.Item i) {
        insertNext(_headptr, i);
    }

    // FIXME: unimplemented
    public void unshift(DoublyLinkedList l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }

    public final DoublyLinkedList.Item shift() {
        return size == 0 ? null : remove(_headptr.next);
    }

    public DoublyLinkedList shift(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl();

        DoublyLinkedList.Item i = _headptr;
        for (int m=0; m<n; ++m)
            i = i.getNext();

        DoublyLinkedList.Item j = i.getNext();
        Item newheadptr = new Item();
        Item newtailptr = new Item();

        j.setPrev(newheadptr);
        newheadptr.setNext(j);

        i.setNext(newtailptr);
        newtailptr.setPrev(i);

        DoublyLinkedList newlist = new DoublyLinkedListImpl(_headptr, newtailptr, n);
        _headptr = newheadptr;
        _headptr.setParent(this);
        size -= n;
        
        return newlist;
    }
    
    
    //=== methods that add/remove items at the tail of the list ================
    
    public final void push(DoublyLinkedList.Item i) {
        insertPrev(_tailptr, i);
    }

    // FIXME: unimplemented
    public void push(DoublyLinkedList l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }

    public final DoublyLinkedList.Item pop() {
        return size == 0 ? null : remove(_tailptr.prev);
    }

    public DoublyLinkedList pop(int n) {

        if (n > size) n = size;
        if (n < 1) return new DoublyLinkedListImpl();

        DoublyLinkedList.Item i = _tailptr;
        for (int m=0; m<n; ++m)
            i = i.getPrev();

        DoublyLinkedList.Item j = i.getPrev();
        Item newtailptr = new Item();
        Item newheadptr = new Item();

        j.setNext(newtailptr);
        newtailptr.setPrev(j);
        newtailptr.setParent(this);

        i.setPrev(newheadptr);
        newheadptr.setNext(i);

        DoublyLinkedList newlist = new DoublyLinkedListImpl(newheadptr, _tailptr, n);
        _tailptr = newtailptr;
        size -= n;
        
        return newlist;
    }

    
    //=== testing/looking at neighbor items ====================================
    
    public final boolean hasNext(DoublyLinkedList.Item i) {
        DoublyLinkedList.Item next = i.getNext();
        return next != null && next != _tailptr;
    }

    public final boolean hasPrev(DoublyLinkedList.Item i) {
        DoublyLinkedList.Item prev = i.getPrev();
        return prev != null && prev != _headptr;
    }

    public final DoublyLinkedList.Item next(DoublyLinkedList.Item i) {
        DoublyLinkedList.Item next = i.getNext();
        return next == _tailptr ? null : next;
    }

    public final DoublyLinkedList.Item prev(DoublyLinkedList.Item i) {
        DoublyLinkedList.Item prev = i.getPrev();
        return prev == _headptr ? null : prev;
    }


    //=== insertion and removal of items =======================================
    
    /**
     * Remove the given item from the list.
     * @return  this item, or null if the item was not in the list
     */
    public DoublyLinkedList.Item remove(DoublyLinkedList.Item i) {
    	if(isEmpty()) {
    		Logger.error(this, "Illegal ERROR: Removing from an empty list!!");
    		throw new IllegalStateException("Illegal ERROR: Removing from an empty list!!");
    	}
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
        DoublyLinkedList.Item next = i.getNext(), prev = i.getPrev();
        if (next == null && prev == null) return null;  // not in the list
        if (next == null || prev == null)
        	throw new NullPointerException("next="+next+", prev="+prev); // partially in the list?!
        if(next.getPrev() != i || prev.getNext() != i) {
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
     * Inserts item J before item I (going from head to tail).
     */
    public void insertPrev(DoublyLinkedList.Item i, DoublyLinkedList.Item j) {
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, j.getParent());
        if (j.getNext() != null || j.getPrev() != null)
            throw new PromiscuousItemException(j);
        DoublyLinkedList.Item prev = i.getPrev();
        if (prev == null)
            throw new VirginItemException(i);
        prev.setNext(j);
        j.setPrev(prev);
        i.setPrev(j);
        j.setNext(i);
        j.setParent(this);
        ++size;
    }

    // FIXME: unimplemented
    /**
     * Inserts the entire DoublyLinkedList L before item I.
     */
    public void insertPrev(DoublyLinkedList.Item i, DoublyLinkedList l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }

    /**
     * Inserts item J after item I (going from head to tail).
     */
    public void insertNext(DoublyLinkedList.Item i, DoublyLinkedList.Item j) {
    	if (i.getParent() != this)
    		throw new PromiscuousItemException(i, i.getParent());
    	if (j.getParent() != null)
    		throw new PromiscuousItemException(j, i.getParent());
        if (j.getNext() != null || j.getPrev() != null)
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

    // FIXME: unimplemented
    /**
     * Inserts the entire DoublyLinkedList L after item I.
     */
    public void insertNext(DoublyLinkedList.Item i, DoublyLinkedList l) {
        throw new RuntimeException("function currently unimplemented because i am a lazy sod");
    }


    //=== Walkable implementation ==============================================
    
    /**
     * @return  an Enumeration of list elements from head to tail
     */
    public Enumeration forwardElements() {
        return new ForwardWalker();
    }

    /**
     * @throws ClassCastException  if startAt is not a DoublyLinkedList.Item
     */
    public Enumeration forwardElements(Object startAt, boolean inclusive) {
        return forwardElements((DoublyLinkedList.Item) startAt, inclusive);
    }

    /**
     * @return  an Enumeration of list elements from head to tail
     * @param startAt    the item to begin the walk at
     * @param inclusive  whether to include startAt in the Enumeration
     * @throws VirginItemException  if startAt is not a member of a DoublyLinkedList
     *
     * WARNING:  If startAt is an item of another DoublyLinkedList, it
     *           will NOT be detected and the results will be unpredictable.
     */
    protected Enumeration forwardElements(DoublyLinkedList.Item startAt, boolean inclusive)
                            throws VirginItemException, NoSuchElementException {

        if (startAt.getNext() == null || startAt.getPrev() == null)
            throw new VirginItemException(startAt);
        else
            return new ForwardWalker(startAt, inclusive);
    }

    /**
     * @return  an Enumeration of list elements from tail to head
     */
    public Enumeration reverseElements() {
        return new ReverseWalker();
    }

    /**
     * @throws ClassCastException  if startAt is not a DoublyLinkedList.Item
     */
    public Enumeration reverseElements(Object startAt, boolean inclusive) {
        return reverseElements((DoublyLinkedList.Item) startAt, inclusive);
    }

    /**
     * @return  an Enumeration of list elements from tail to head
     * @param startAt    the item to begin the walk at
     * @param inclusive  whether to include startAt in the Enumeration
     * @throws VirginItemException  if startAt is not a member of a DoublyLinkedList
     *
     * WARNING:  If startAt is an item of another DoublyLinkedList, it
     *           will NOT be detected and the results will be unpredictable.
     */
    protected Enumeration reverseElements(DoublyLinkedList.Item startAt, boolean inclusive)
                            throws VirginItemException, NoSuchElementException {

        if (startAt.getNext() == null || startAt.getPrev() == null)
            throw new VirginItemException(startAt);
        else
            return new ReverseWalker(startAt, inclusive);
    }

    protected class ForwardWalker implements Enumeration {
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
        public Object nextElement() {
            if (next == _tailptr)
                throw new NoSuchElementException();
            DoublyLinkedList.Item result = next;
            next = next.getNext();
            return result;
        }
    }

    protected class ReverseWalker implements Enumeration {
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
        public Object nextElement() {
            if (next == _headptr)
                throw new NoSuchElementException();
            DoublyLinkedList.Item result = next;
	    if(next == null) throw new IllegalStateException("next==null");
            next = next.getPrev();
            return result;
        }
    }


    //=== list element ====================================================

    public static class Item implements DoublyLinkedList.Item {
        private DoublyLinkedList.Item next, prev;
        private DoublyLinkedList list;
        public Object clone() {
            if(getClass() != Item.class)
                throw new RuntimeException("Must implement clone() for "+getClass());
            return new Item();
        }
        public final DoublyLinkedList.Item getNext() {
            return next;
        }
        public final DoublyLinkedList.Item setNext(DoublyLinkedList.Item i) {
            DoublyLinkedList.Item old = next;
            next = i;
            return old;
        }
        public final DoublyLinkedList.Item getPrev() {
            return prev;
        }
        public final DoublyLinkedList.Item setPrev(DoublyLinkedList.Item i) {
            DoublyLinkedList.Item old = prev;
            prev = i;
            return old;
        }
		public DoublyLinkedList getParent() {
			return list;
		}
		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = list;
			list = l;
			return old;
		}
    }
}



