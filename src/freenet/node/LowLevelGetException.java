package freenet.node;

public class LowLevelGetException extends Exception {

	/** Decode of data failed, probably was bogus at source */
	public static final int DECODE_FAILED = 1;
	/** Data was not in store and request was local-only */
	public static final int DATA_NOT_FOUND_IN_STORE = 2;
	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 3;
	/** The request went to many hops, but could not find the data. Maybe
	 * it doesn't exist. */
	public static final int DATA_NOT_FOUND = 4;
	/** The request could not find enough nodes to visit while looking for
	 * the datum. */
	public static final int ROUTE_NOT_FOUND = 5;
	/** A downstream node is overloaded, and rejected the request. We should
	 * reduce our rate of sending requests.
	 */
	public static final int REJECTED_OVERLOAD = 6;
	/** Transfer of data started, but then failed. */
	public static final int TRANSFER_FAILED = 7;
	/** Data successfully transferred, but was not valid (at the node key level
	 * i.e. before decode) */
	public static final int VERIFY_FAILED = 8;
	
	static final String getMessage(int reason) {
		if(reason == DECODE_FAILED)
			return "Decode of data failed, probably was bogus at source";
		else if(reason == DATA_NOT_FOUND_IN_STORE)
			return "Data was not in store and request was local-only";
		return "Unknown error code: "+reason;
	}
	
	/** Failure code */
	public final int code;
	
	LowLevelGetException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	LowLevelGetException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
}
