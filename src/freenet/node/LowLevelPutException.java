package freenet.node;

public class LowLevelPutException extends Exception {

	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 3;
	/** The request could not go enough hops to store the data properly. */
	public static final int ROUTE_NOT_FOUND = 5;
	/** A downstream node is overloaded, and rejected the insert. We should
	 * reduce our rate of sending inserts. */
	public static final int REJECTED_OVERLOAD = 6;
	
	/** Failure code */
	public final int code;
	
	static final String getMessage(int reason) {
		return "Unknown error code: "+reason;
	}
	
	LowLevelPutException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	LowLevelPutException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
}
