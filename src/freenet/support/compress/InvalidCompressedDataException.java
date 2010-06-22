package freenet.support.compress;

import java.io.IOException;

public class InvalidCompressedDataException extends IOException {

	public InvalidCompressedDataException() {
		super();
	}
	
	public InvalidCompressedDataException(String msg) {
		super(msg);
	}

}
