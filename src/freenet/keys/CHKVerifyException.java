package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK doesn't verify.
 */
public class CHKVerifyException extends KeyVerifyException {
	static final long serialVersionUID = -1;

	/**
     * 
     */
    public CHKVerifyException() {
        super();
    }

    /**
     * @param message
     */
    public CHKVerifyException(String message) {
        super(message);
        // TODO Auto-generated constructor stub
    }

    /**
     * @param message
     * @param cause
     */
    public CHKVerifyException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @param cause
     */
    public CHKVerifyException(Throwable cause) {
        super(cause);
    }

}
