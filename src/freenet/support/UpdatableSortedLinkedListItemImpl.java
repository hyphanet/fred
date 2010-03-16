package freenet.support;

import freenet.support.DoublyLinkedList.Item;

/**
 * An item that can be put into an UpdatableSortedLinkedList.
 */
public abstract class UpdatableSortedLinkedListItemImpl<T extends UpdatableSortedLinkedListItemImpl<T>> implements
		UpdatableSortedLinkedListItem<T> {

	private T next;
	private T prev;

	public T getNext() {
		return next;
	}

	@SuppressWarnings("unchecked")
	public T setNext(Item<?> i) {
		T old = next;
		next = (T) i;
		return old;
	}

	public T getPrev() {
		return prev;
	}

	@SuppressWarnings("unchecked")
	public T setPrev(Item<?> i) {
		T old = prev;
		prev = (T) i;
		return old;
	}

	/*
	 * FIXME: DoublyLinkedList says that this is only for debugging purposes. Maybe it should be
	 * removed completely?
	 */

	private DoublyLinkedList<? super T> parentList;

	public DoublyLinkedList<? super T> getParent() {
		return parentList;
	}

	public DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l) {
		DoublyLinkedList<? super T> oldParent = parentList;
		parentList = l;
		return oldParent;
	}

}
