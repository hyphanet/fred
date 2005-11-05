package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK encoding fails.
 */
public class CHKEncodeException extends Exception {
	static final long serialVersionUID = -1;
    public CHKEncodeException() {
        super();
    }

    public CHKEncodeException(String message) {
        super(message);
    }

    public CHKEncodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public CHKEncodeException(Throwable cause) {
        super(cause);
    }
}
