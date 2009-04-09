package freenet.support;

import java.util.Enumeration;

/**
 * Framework for managing a doubly linked list.
 * The purpose of DoublyLinkedList is simply and solely so that 
 * we can override the entries with our own classes. This makes  
 * removal for example extremely fast: O(1) not O(n).
 * In any other case we can use LinkedList.
 * @author tavin
 */
public interface DoublyLinkedList<T extends DoublyLinkedList.Item<?>> extends Iterable<T> {
	// public abstract DoublyLinkedList<T> clone();

    /**
     * List element
     */
	public interface Item<T extends DoublyLinkedList.Item<?>> {
		/**
		 * Get next {@link Item}. May or may not return
		 * <code>null</code> if this is the last <code>Item</code>.
		 * 
		 * @see DoublyLinkedList#hasNext()
		 */
		T getNext();
        /** Set next {@link Item} */
		T setNext(Item<?> i);
        /**
	 * Get previous {@link Item}. May or may not return <code>null</code>
	 * if this is the first <code>Item</code>.
	 * 
	 * @see DoublyLinkedList#hasNext()
	 */
		T getPrev();
        /** Get previous {@link Item} */
		T setPrev(Item<?> i);
        
        /** Return the contained list. <strong>For sanity checking only.</strong> */
		DoublyLinkedList<? super T> getParent();
        /** Set the contained list. <strong>For sanity checking only.</strong>*/
		DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l);
    }
    
    /** Clear this list */
    void clear();
    /** Return the size of this list */
    int size();
    /** Check if this list is empty. @return <code>true</code> if this list is empty, <code>false</code> otherwise. */
    boolean isEmpty();
    /** Get a {@link Enumeration} of {@link DoublyLinkedList.Item}. */
	Enumeration<T> elements(); // for consistency w/ typical Java API

    /**
     * Returns true if the list contains an item i where <code>i.equals(item)</code> is true.
     */
	public boolean contains(T item);
    
    /**
     * Returns the first item.
     * @return  the item at the head of the list, or <code>null</code> if empty
     */
	T head();
    /**
     * Returns the last item.
     * @return  the item at the tail of the list, or <code>null</code> if empty
     */
	T tail();

    /**
     * Puts the item before the first item.
     */
	void unshift(T i);
    /**
     * Removes and returns the first item.
     */
	T shift();
    /**
     * Remove <tt>n</tt> elements from head and return them as a <code>DoublyLinkedList</code>.
     */
    DoublyLinkedList<T> shift(int n);

    /**
     * Puts the item after the last item.
     */
	void push(T i);
    /**
     * Removes and returns the last item.
     */
	T pop();
    /**
     * Remove <tt>n</tt> elements from tail and return them as a <code>DoublyLinkedList</code>.
     */
	DoublyLinkedList<T> pop(int n);

    /** @return <code>true</code> if <code>i</code> has next item. (ie. not the last item); <code>false</code> otherwise */ 
	boolean hasNext(T i);
    /** @return <code>true</code> if <code>i</code> has previous item. (ie. not the first item); <code>false</code> otherwise */
	boolean hasPrev(T i);

    /** @return next item of <code>i</code>. If this is the last element, return <code>null</code> */
	T next(T i);
    /** @return previous item of <code>i</code>. If this is the first element, return <code>null</code> */
	T prev(T i);
    /** Remove and return a element 
     * @return  this item, or <code>null</code> if the item was not in the list
     */
	T remove(T i);
    /**
     * Inserts item <code>j</code> before item <code>i</code>.
     */
	void insertPrev(T i, T j);
    /**
     * Inserts item <code>j</code> after item <code>i</code.
     */
	void insertNext(T i, T j);
}



