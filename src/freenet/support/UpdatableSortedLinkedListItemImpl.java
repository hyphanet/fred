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
    
    /*
     * FIXME: DoublyLinkedList says that this is only for debugging purposes.
     * Maybe it should be removed completely?
     */
    
    private DoublyLinkedList parentList;

	public DoublyLinkedList getParent() {
		return parentList;
	}

	public DoublyLinkedList setParent(DoublyLinkedList l) {
		DoublyLinkedList oldParent = parentList;
		parentList = l;
		return oldParent;
	}
    
}
