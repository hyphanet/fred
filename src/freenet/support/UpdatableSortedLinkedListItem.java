package freenet.support;

import freenet.support.DoublyLinkedList.Item;

public interface UpdatableSortedLinkedListItem<T extends UpdatableSortedLinkedListItem<T>> extends
        DoublyLinkedList.Item<T>, Comparable<T> {

	@Override
	public abstract T getNext();

	@Override
	public abstract T setNext(Item<?> i);

	@Override
	public abstract T getPrev();

	@Override
	public abstract T setPrev(Item<?> i);
}