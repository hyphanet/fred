/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.DoublyLinkedList.Item;

/**
 * An item that can be put into an UpdatableSortedLinkedList.
 */
public abstract class UpdatableSortedLinkedListItemImpl<T extends UpdatableSortedLinkedListItemImpl<T>>
        implements UpdatableSortedLinkedListItem<T> {
    private T next;
    private T prev;

    /*
     * FIXME: DoublyLinkedList says that this is only for debugging purposes. Maybe it should be
     * removed completely?
     */
    private DoublyLinkedList<? super T> parentList;

    @Override
    public T getNext() {
        return next;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T setNext(Item<?> i) {
        T old = next;

        next = (T) i;

        return old;
    }

    @Override
    public T getPrev() {
        return prev;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T setPrev(Item<?> i) {
        T old = prev;

        prev = (T) i;

        return old;
    }

    @Override
    public DoublyLinkedList<? super T> getParent() {
        return parentList;
    }

    @Override
    public DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l) {
        DoublyLinkedList<? super T> oldParent = parentList;

        parentList = l;

        return oldParent;
    }
}
