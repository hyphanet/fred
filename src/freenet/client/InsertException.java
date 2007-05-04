/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.support.Logger;

public class InsertException extends Exception {
	private static final long serialVersionUID = -1106716067841151962L;
	
	private final int mode;
	/** For collection errors */
	public FailureCodeTracker errorCodes;
	/** If a non-serious error, the URI */
	public FreenetURI uri;
	
	public final String extra;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public InsertException(int m, String msg, FreenetURI expectedURI) {
		super(getMessage(m)+": "+msg);
		extra = msg;
		mode = m;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+msg, this);
		errorCodes = null;
		this.uri = expectedURI;
	}
	
	public InsertException(int m, FreenetURI expectedURI) {
		super(getMessage(m));
		extra = null;
		mode = m;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
		errorCodes = null;
		this.uri = expectedURI;
	}

	public InsertException(int mode, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+e.getMessage());
		extra = e.getMessage();
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+e, e);
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
	}

	public InsertException(int mode, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
		super(getMessage(mode));
		extra = null;
		this.mode = mode;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
		this.errorCodes = errorCodes;
		this.uri = expectedURI;
	}

	public InsertException(int mode) {
		super(getMessage(mode));
		extra = null;
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
	/** Cancelled by user */
	public static final int CANCELLED = 10;
	/** Meta string used in the key (most probably '/') */
	public static final int META_STRINGS_NOT_SUPPORTED = 11;
	
	public static String getMessage(int mode) {
		String ret = L10n.getString("InsertException.longError."+mode);
		if(ret == null)
			return "Unknown error "+mode;
		else return ret;
	}

	public static String getShortMessage(int mode) {
		String ret = L10n.getString("InsertException.shortError."+mode);
		if(ret == null)
			return "Unknown error "+mode;
		else return ret;
	}
	
	/** Is this error fatal? Non-fatal errors are errors which are likely to go away with
	 * more retries, or at least for which there is some point retrying.
	 */
	public boolean isFatal() {
		return isFatal(mode);
	}
	
	public static boolean isFatal(int mode) {
		switch(mode) {
		case INVALID_URI:
		case FATAL_ERRORS_IN_BLOCKS:
		case COLLISION:
		case CANCELLED:
		case META_STRINGS_NOT_SUPPORTED:
			return true;
		case BUCKET_ERROR: // maybe
		case INTERNAL_ERROR: // maybe
		case REJECTED_OVERLOAD:
		case TOO_MANY_RETRIES_IN_BLOCKS:
		case ROUTE_NOT_FOUND:
		case ROUTE_REALLY_NOT_FOUND:
			return false;
		default:
			Logger.error(InsertException.class, "Error unknown to isFatal(): "+getMessage(mode));
			return false;
		}
	}

	public static InsertException construct(FailureCodeTracker errors) {
		if(errors == null) return null;
		if(errors.isEmpty()) return null;
		if(errors.isOneCodeOnly()) {
			return new InsertException(errors.getFirstCode());
		}
		int mode;
		if(errors.isFatal(true))
			mode = FATAL_ERRORS_IN_BLOCKS;
		else
			mode = TOO_MANY_RETRIES_IN_BLOCKS;
		return new InsertException(mode, errors, null);
	}
}
