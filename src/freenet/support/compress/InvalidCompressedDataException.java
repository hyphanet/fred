package freenet.support.compress;

import java.io.IOException;

public class InvalidCompressedDataException extends IOException {

	private static final long serialVersionUID = -1L;

	public InvalidCompressedDataException() {
		super();
	}
	
	public InvalidCompressedDataException(String msg) {
		super(msg);
	}

}
