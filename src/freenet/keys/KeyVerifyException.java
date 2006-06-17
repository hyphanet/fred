package freenet.keys;

public class KeyVerifyException extends Exception {
	private static final long serialVersionUID = -1;

	public KeyVerifyException(String message) {
		super(message);
	}

	public KeyVerifyException(String message, Throwable cause) {
		super(message, cause);
	}

	public KeyVerifyException() {
		super();
	}

	public KeyVerifyException(Throwable cause) {
		super(cause);
	}

}
