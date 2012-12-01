package freenet.support;

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
		super(item.toString()+ ':' +parent);
	}
}
