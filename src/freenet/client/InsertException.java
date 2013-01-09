/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.TooManyFilesInsertException;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Thrown when a high-level insert fails. For most failures, there will not be a stack trace, or it 
 * will be inaccurate.
 */
public class InsertException extends Exception implements Cloneable {
	private static final long serialVersionUID = -1106716067841151962L;
	
	/** Failure mode, see the constants below. */
	private final int mode;
	/** Collect errors when there are multiple failures. The error mode will be FATAL_ERRORS_IN_BLOCKS or
	 * TOO_MANY_RETRIES_IN_BLOCKS i.e. a splitfile failed. */
	public FailureCodeTracker errorCodes;
	/** If a non-serious error, the URI we expect the insert to go to if it had succeeded. */
	public FreenetURI uri;

	/** Extra detail message */
	public final String extra;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private InsertException() {
		mode = 0;
		extra = null;
	}

	public InsertException(int m, String msg, FreenetURI expectedURI) {
		super(getMessage(m)+": "+msg);
		if(m == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = msg;
		mode = m;
		errorCodes = null;
		this.uri = expectedURI;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+msg, this);
	}
	
	public InsertException(int m, FreenetURI expectedURI) {
		super(getMessage(m));
		if(m == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = null;
		mode = m;
		errorCodes = null;
		this.uri = expectedURI;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

	public InsertException(int mode, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+e.getMessage());
		if(mode == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = e.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+e, this);
	}

	public InsertException(int mode, String message, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+message+": "+e.getMessage());
		if(mode == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = e.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+e, this);
	}

	public InsertException(int mode, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
		super(getMessage(mode));
		if(mode == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = null;
		this.mode = mode;
		this.errorCodes = errorCodes;
		this.uri = expectedURI;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

	public InsertException(int mode) {
		super(getMessage(mode));
		if(mode == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = null;
		this.mode = mode;
		this.errorCodes = null;
		this.uri = null;
		if(mode == INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

	public InsertException(InsertException e) {
		super(e.getMessage());
		if(e.mode == 0)
			Logger.error(this, "Can't increment failure mode 0, not a valid mode", new Exception("error"));
		extra = e.extra;
		mode = e.mode;
		errorCodes = e.errorCodes == null ? null : e.errorCodes.clone();
		if(e.uri == null)
			uri = null;
		else
			uri = e.uri.clone();
	}

	public InsertException(TooManyFilesInsertException e) {
		this(TOO_MANY_FILES, (String)null, null);
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
	/** Invalid binary blob data supplied so cannot insert it */
	public static final int BINARY_BLOB_FORMAT_ERROR = 12;
	/** Too many files in a directory in a site insert */
	public static final int TOO_MANY_FILES = 13;
	
	/** Get the (localised) short name of this failure mode. */
	public static String getMessage(int mode) {
		String ret = NodeL10n.getBase().getString("InsertException.longError."+mode);
		if(ret == null)
			return "Unknown error "+mode;
		else return ret;
	}

	/** Get the (localised) long explanation for this failure mode. */
	public static String getShortMessage(int mode) {
		String ret = NodeL10n.getBase().getString("InsertException.shortError."+mode);
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
		case BINARY_BLOB_FORMAT_ERROR:
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

	/**
	 * Construct an InsertException from a bunch of error codes, typically from a splitfile insert.
	 */
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
	
	@Override
	public InsertException clone() {
		// Cloneable shuts up findbugs, but we need to deep copy errorCodes.
		return new InsertException(this);
	}

	/**
	 * Remove the insert exception from the database.
	 * @param container The database.
	 */
	public void removeFrom(ObjectContainer container) {
		if(errorCodes != null) {
			container.activate(errorCodes, 1);
			errorCodes.removeFrom(container);
		}
		if(uri != null) {
			container.activate(uri, 5);
			uri.removeFrom(container);
		}
		StackTraceElement[] elements = getStackTrace();
		if(elements != null)
			for(StackTraceElement element : elements)
				container.delete(element);
		container.delete(this);
	}
}
