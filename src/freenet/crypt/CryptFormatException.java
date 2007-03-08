package freenet.crypt;

import java.io.IOException;

public class CryptFormatException extends Exception {

	private static final long serialVersionUID = -796276279268900609L;

	public CryptFormatException(String message) {
		super(message);
	}

	public CryptFormatException(IOException e) {
		super(e.getMessage());
		initCause(e);
	}

}
