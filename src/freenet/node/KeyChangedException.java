package freenet.node;

/**
 * Exception thrown when the primary key changes in the middle
 * of acquiring a packet number.
 */
public class KeyChangedException extends Exception {
	private static final long serialVersionUID = -1;
}
