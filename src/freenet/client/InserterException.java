package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class InserterException extends Exception {
	private static final long serialVersionUID = -1106716067841151962L;
	
	private final int mode;
	/** For collection errors */
	public FailureCodeTracker errorCodes;
	/** If a non-serious error, the URI */
	public final FreenetURI uri;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public InserterException(int m, String msg, FreenetURI expectedURI) {
		super(getMessage(m)+": "+msg);
		mode = m;
		Logger.minor(this, "Creating InserterException: "+getMessage(mode)+": "+msg, this);
		errorCodes = null;
		this.uri = expectedURI;
	}
	
	public InserterException(int m, FreenetURI expectedURI) {
		super(getMessage(m));
		mode = m;
		Logger.minor(this, "Creating InserterException: "+getMessage(mode), this);
		errorCodes = null;
		this.uri = expectedURI;
	}

	public InserterException(int mode, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+e.getMessage());
		Logger.minor(this, "Creating InserterException: "+getMessage(mode)+": "+e, e);
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
	}

	public InserterException(int mode, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
		super(getMessage(mode));
		this.mode = mode;
		Logger.minor(this, "Creating InserterException: "+getMessage(mode), this);
		this.errorCodes = errorCodes;
		this.uri = expectedURI;
	}

	public InserterException(int mode) {
		this.mode = mode;
		this.errorCodes = null;
		this.uri = null;
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
	/** There were fatal errors in a splitfile insert. */
	public static final int FATAL_ERRORS_IN_BLOCKS = 6;
	/** Could not insert a splitfile because a block failed too many times */
	public static final int TOO_MANY_RETRIES_IN_BLOCKS = 7;
	/** Not able to leave the node at all */
	public static final int ROUTE_REALLY_NOT_FOUND = 8;
	/** Collided with pre-existing content */
	public static final int COLLISION = 9;
	
	public static String getMessage(int mode) {
		switch(mode) {
		case INVALID_URI:
			return "Caller supplied a URI we cannot use";
		case BUCKET_ERROR:
			return "Internal bucket error: out of disk space/permissions problem?";
		case INTERNAL_ERROR:
			return "Internal error";
		case REJECTED_OVERLOAD:
			return "A downstream node timed out or was severely overloaded";
		case FATAL_ERRORS_IN_BLOCKS:
			return "Fatal errors in a splitfile insert";
		case TOO_MANY_RETRIES_IN_BLOCKS:
			return "Could not insert splitfile: ran out of retries (nonfatal errors)";
		case ROUTE_NOT_FOUND:
			return "Could not propagate the insert to enough nodes (normal on small networks, try fetching it anyway)";
		case ROUTE_REALLY_NOT_FOUND:
			return "Insert could not leave the node at all";
		case COLLISION:
			return "Insert collided with different, pre-existing data at the same key";
		default:
			return "Unknown error "+mode;
		}
	}
}
