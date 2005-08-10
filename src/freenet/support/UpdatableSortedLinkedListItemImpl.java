package freenet.support;

import freenet.support.DoublyLinkedList.Item;

/**
 * An item that can be put into an UpdatableSortedLinkedList.
 */
public abstract class UpdatableSortedLinkedListItemImpl implements UpdatableSortedLinkedListItem {

    private Item next;
    private Item prev;
    
    public Item getNext() {
        return next;
    }

    public Item setNext(Item i) {
        Item old = next;
        next = i;
        return old;
    }

    public Item getPrev() {
        return prev;
    }

    public Item setPrev(Item i) {
        Item old = prev;
        prev = i;
        return old;
    }
}
