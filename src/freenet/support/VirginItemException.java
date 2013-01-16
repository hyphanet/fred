package freenet.support;

/**
 * Indicates an attempt to link two DoublyLinkedList.Item's,
 * neither of which are a member of a DoublyLinkedList.
 * @author tavin
 */
public class VirginItemException extends RuntimeException {
	private static final long serialVersionUID = -1;
    VirginItemException(DoublyLinkedList.Item<?> item) {
        super(item.toString());
    }
}
