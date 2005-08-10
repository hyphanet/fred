package freenet.support;

import freenet.support.DoublyLinkedList.Item;

public interface UpdatableSortedLinkedListItem extends DoublyLinkedList.Item, Comparable {

    public abstract Item getNext();

    public abstract Item setNext(Item i);

    public abstract Item getPrev();

    public abstract Item setPrev(Item i);
}