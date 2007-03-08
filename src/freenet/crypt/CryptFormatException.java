package freenet.crypt;

import java.io.IOException;

public class CryptFormatException extends Exception {

	public CryptFormatException(String message) {
		super(message);
	}

	public CryptFormatException(IOException e) {
		super(e.getMessage());
		initCause(e);
	}

}
