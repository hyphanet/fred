package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK encoding fails.
 */
public class CHKEncodeException extends Exception {

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
