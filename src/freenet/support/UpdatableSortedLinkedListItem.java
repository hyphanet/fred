/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.DoublyLinkedList.Item;

public interface UpdatableSortedLinkedListItem<T extends UpdatableSortedLinkedListItem<T>>
        extends DoublyLinkedList.Item<T>, Comparable<T> {
    @Override
    public abstract T getNext();

    @Override
    public abstract T setNext(Item<?> i);

    @Override
    public abstract T getPrev();

    @Override
    public abstract T setPrev(Item<?> i);
}
