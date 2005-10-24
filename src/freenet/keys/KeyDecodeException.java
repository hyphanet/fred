package freenet.keys;

/**
 * Base class for decode exceptions.
 */
public class KeyDecodeException extends Exception {

	public KeyDecodeException(String message) {
		super(message);
	}

	public KeyDecodeException() {
		super();
	}

	public KeyDecodeException(String message, Throwable cause) {
		super(message, cause);
	}

	public KeyDecodeException(Throwable cause) {
		super(cause);
	}

}
