package freenet.client;

import java.io.IOException;

public class InserterException extends Exception {
	private static final long serialVersionUID = -1106716067841151962L;
	
	final int mode;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public InserterException(int m) {
		mode = m;
	}

	public InserterException(int mode, IOException e) {
		this.mode = mode;
		initCause(e);
	}

	/** Caller supplied a URI we cannot use */
	public static final int INVALID_URI = 1;
	/** Failed to read from or write to a bucket; a kind of internal error */
	public static final int BUCKET_ERROR = 2;
	/** Internal error of some sort */
	public static final int INTERNAL_ERROR = 3;
	/** Downstream node was overloaded */
	public static final int REJECTED_OVERLOAD = 4;
	/** Couldn't find enough nodes to send the data to */
	public static final int ROUTE_NOT_FOUND = 5;
}
