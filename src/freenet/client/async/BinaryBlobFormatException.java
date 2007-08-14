package freenet.client.async;

import freenet.keys.KeyVerifyException;

public class BinaryBlobFormatException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public BinaryBlobFormatException(String message) {
		super(message);
	}

	public BinaryBlobFormatException(String message, KeyVerifyException e) {
		super(message, e);
	}

}
