package freenet.node.fcp;

/**
 * Thrown when a persistent request cannot be parsed.
 */
public class PersistenceParseException extends Exception {
	private static final long serialVersionUID = -1;

	public PersistenceParseException(String string) {
		super(string);
	}

}
