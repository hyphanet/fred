package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK doesn't verify.
 */
public class CHKVerifyException extends Exception {

    /**
     * 
     */
    public CHKVerifyException() {
        super();
        // TODO Auto-generated constructor stub
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
        // TODO Auto-generated constructor stub
    }

    /**
     * @param cause
     */
    public CHKVerifyException(Throwable cause) {
        super(cause);
        // TODO Auto-generated constructor stub
    }

}
