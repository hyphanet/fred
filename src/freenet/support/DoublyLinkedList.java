package freenet.support;

import java.util.Enumeration;

/**
 * Framework for managing a doubly linked list.
 * @author tavin
 */
public interface DoublyLinkedList {
    public abstract Object clone();

    /**
     * List element
     */
    public interface Item {
		/**
		 * Get next {@link Item}. May or may not return
		 * <code>null</code> if this is the last <code>Item</code>.
		 * 
		 * @see DoublyLinkedList#hasNext()
		 */
        Item getNext();
        /** Set next {@link Item} */
        Item setNext(Item i);
        /**
	 * Get previous {@link Item}. May or may not return <code>null</code>
	 * if this is the first <code>Item</code>.
	 * 
	 * @see DoublyLinkedList#hasNext()
	 */
        Item getPrev();
        /** Get previous {@link Item} */
        Item setPrev(Item i);
        
        /** Return the contained list. <strong>For sanity checking only.</strong> */
        DoublyLinkedList getParent();
        /** Set the contained list. <strong>For sanity checking only.</strong>*/
        DoublyLinkedList setParent(DoublyLinkedList l);
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
    void unshift(Item i);
    /**
     * Put all items in the specified list before the first item.
     */
    void unshift(DoublyLinkedList l);
    /**
     * Removes and returns the first item.
     */
    Item shift();
    /**
     * Remove <tt>n</tt> elements from head and return them as a <code>DoublyLinkedList</code>.
     */
    DoublyLinkedList shift(int n);

    /**
     * Puts the item after the last item.
     */
    void push(Item i);
    /**
     * Puts all items in the specified list after the last item.
     */
    void push(DoublyLinkedList l);
    /**
     * Removes and returns the last item.
     */
    Item pop();
    /**
     * Remove <tt>n</tt> elements from tail and return them as a <code>DoublyLinkedList</code>.
     */
    DoublyLinkedList pop(int n);

    /** @return <code>true</code> if <code>i</code> has next item. (ie. not the last item); <code>false</code> otherwise */ 
    boolean hasNext(Item i);
    /** @return <code>true</code> if <code>i</code> has previous item. (ie. not the first item); <code>false</code> otherwise */
    boolean hasPrev(Item i);

    /** @return next item of <code>i</code>. If this is the last element, return <code>null</code> */
    Item next(Item i);
    /** @return previous item of <code>i</code>. If this is the first element, return <code>null</code> */
    Item prev(Item i);
    /** Remove and return a element 
     * @return  this item, or <code>null</code> if the item was not in the list
     */
    Item remove(Item i);
    /**
     * Inserts item <code>j</code> before item <code>i</code>.
     */
    void insertPrev(Item i, Item j);
    /**
     * Inserts the entire {@link DoublyLinkedList} <code>l</code> before item <code>i</code>.
     */
    void insertPrev(Item i, DoublyLinkedList l);  
    /**
     * Inserts item <code>j</code> after item <code>i</code.
     */
    void insertNext(Item i, Item j);    
    /**
     * Inserts the entire {@link DoublyLinkedList} <code>l</code> after item <code>i</code>.
     */
    void insertNext(Item i, DoublyLinkedList l);
}



