package freenet.support;

import java.util.Enumeration;

/**
 * Framework for managing a doubly linked list.
 * @author tavin
 */
public interface DoublyLinkedList {

    public abstract Object clone();
    
    public interface Item {
        Item getNext();
        Item setNext(Item i);
        Item getPrev();
        Item setPrev(Item i);
    }
    
    void clear();
    int size();
    boolean isEmpty();
    Enumeration elements();   // for consistency w/ typical Java API

    /**
     * Returns the first item.
     */
    Item head();
    /**
     * Returns the last item.
     */
    Item tail();

    /**
     * Puts the item before the first item.
     */
    void unshift(Item i);
    void unshift(DoublyLinkedList l);
    /**
     * Removes and returns the first item.
     */
    Item shift();
    DoublyLinkedList shift(int n);

    /**
     * Puts the item after the last item.
     */
    void push(Item i);
    void push(DoublyLinkedList l);
    /**
     * Removes and returns the last item.
     */
    Item pop();
    DoublyLinkedList pop(int n);

    boolean hasNext(Item i);
    boolean hasPrev(Item i);

    Item next(Item i);
    Item prev(Item i);

    Item remove(Item i);

    void insertPrev(Item i, Item j);
    void insertPrev(Item i, DoublyLinkedList l);
    void insertNext(Item i, Item j);
    void insertNext(Item i, DoublyLinkedList l);
}



