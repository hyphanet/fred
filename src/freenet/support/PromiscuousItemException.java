/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.DoublyLinkedList.Item;

/**
 * Indicates an attempt to link a DoublyLinkedList.Item into
 * two or more DoublyLinkedList's simultaneously (or twice
 * into the same list).
 *
 * Or dito for a Heap.Element. // oskar
 *
 * @author tavin
 */
public class PromiscuousItemException extends RuntimeException {
    private static final long serialVersionUID = -1;

    PromiscuousItemException(DoublyLinkedList.Item<?> item) {
        super(item.toString());
    }

    public PromiscuousItemException(Item<?> item, DoublyLinkedList<?> parent) {
        super(item.toString() + ':' + parent);
    }
}
