package freenet.keys;

public class KeyVerifyException extends Exception {

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
