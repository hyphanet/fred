package freenet.support;

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

    PromiscuousItemException(DoublyLinkedList.Item item) {
        super(item.toString());
    }
}
