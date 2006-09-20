package freenet.node;

/**
 * Exception thrown when we cannot parse a supplied peers file in
 * SimpleFieldSet format (after it has been turned into a SFS).
 */
public class FSParseException extends Exception {
	private static final long serialVersionUID = -1;
    public FSParseException(Exception e) {
        super(e);
    }
    
    public FSParseException(String msg) {
        super(msg);
    }

}
