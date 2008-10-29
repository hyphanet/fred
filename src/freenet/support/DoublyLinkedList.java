package freenet.support;

import java.util.Enumeration;

/**
 * Framework for managing a doubly linked list.
 * @author tavin
 */
public interface DoublyLinkedList<T> extends Iterable<T> {
    public abstract DoublyLinkedList<T> clone();

    /**
     * List element
     */
    public interface Item<T> {
		/**
		 * Get next {@link Item}. May or may not return
		 * <code>null</code> if this is the last <code>Item</code>.
		 * 
		 * @see DoublyLinkedList#hasNext()
		 */
        DoublyLinkedList.Item<T> getNext();
        /** Set next {@link Item} */
        DoublyLinkedList.Item<T> setNext(DoublyLinkedList.Item<T> i);
        /**
	 * Get previous {@link Item}. May or may not return <code>null</code>
	 * if this is the first <code>Item</code>.
	 * 
	 * @see DoublyLinkedList#hasNext()
	 */
        Item<T> getPrev();
        /** Get previous {@link Item} */
        Item<T> setPrev(DoublyLinkedList.Item<T> i);
        
        /** Return the contained list. <strong>For sanity checking only.</strong> */
        DoublyLinkedList<T> getParent();
        /** Set the contained list. <strong>For sanity checking only.</strong>*/
        DoublyLinkedList<T> setParent(DoublyLinkedList<T> l);
    }
    
    /** Clear this list */
    void clear();
    /** Return the size of this list */
    int size();
    /** Check if this list is empty. @return <code>true</code> if this list is empty, <code>false</code> otherwise. */
    boolean isEmpty();
    /** Get a {@link Enumeration} of {@link DoublyLinkedList.Item}. */
    Enumeration elements();   // for consistency w/ typical Java API

    /**
     * Returns the first item.
     * @return  the item at the head of the list, or <code>null</code> if empty
     */
    Item head();
    /**
     * Returns the last item.
     * @return  the item at the tail of the list, or <code>null</code> if empty
     */
    Item tail();

    /**
     * Puts the item before the first item.
     */
    void unshift(DoublyLinkedList.Item<T> i);
    /**
     * Removes and returns the first item.
     */
    Item shift();
    /**
     * Remove <tt>n</tt> elements from head and return them as a <code>DoublyLinkedList</code>.
     */
    DoublyLinkedList<T> shift(int n);

    /**
     * Puts the item after the last item.
     */
    void push(DoublyLinkedList.Item<T> i);
    /**
     * Removes and returns the last item.
     */
    Item pop();
    /**
     * Remove <tt>n</tt> elements from tail and return them as a <code>DoublyLinkedList</code>.
     */
    DoublyLinkedList pop(int n);

    /** @return <code>true</code> if <code>i</code> has next item. (ie. not the last item); <code>false</code> otherwise */ 
    boolean hasNext(DoublyLinkedList.Item<T> i);
    /** @return <code>true</code> if <code>i</code> has previous item. (ie. not the first item); <code>false</code> otherwise */
    boolean hasPrev(DoublyLinkedList.Item<T> i);

    /** @return next item of <code>i</code>. If this is the last element, return <code>null</code> */
    Item next(DoublyLinkedList.Item<T> i);
    /** @return previous item of <code>i</code>. If this is the first element, return <code>null</code> */
    Item prev(DoublyLinkedList.Item<T> i);
    /** Remove and return a element 
     * @return  this item, or <code>null</code> if the item was not in the list
     */
    Item remove(DoublyLinkedList.Item<T> i);
    /**
     * Inserts item <code>j</code> before item <code>i</code>.
     */
    void insertPrev(DoublyLinkedList.Item<T> i, DoublyLinkedList.Item<T> j);
    /**
     * Inserts item <code>j</code> after item <code>i</code.
     */
    void insertNext(DoublyLinkedList.Item<T> i, DoublyLinkedList.Item<T> j); 
}



