package freenet.support;

import freenet.support.DoublyLinkedList.Item;

public interface UpdatableSortedLinkedListItem<T extends UpdatableSortedLinkedListItem<T>> extends
		DoublyLinkedList.Item<T>, Comparable<T> {

	public abstract T getNext();

	public abstract T setNext(Item<?> i);

	public abstract T getPrev();

	public abstract T setPrev(Item<?> i);
}