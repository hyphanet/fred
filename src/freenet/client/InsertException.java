/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashMap;

import freenet.client.async.TooManyFilesInsertException;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.LowLevelPutException;
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
	public final InsertExceptionMode mode;
	/** Collect errors when there are multiple failures. The error mode will be FATAL_ERRORS_IN_BLOCKS or
	 * TOO_MANY_RETRIES_IN_BLOCKS i.e. a splitfile failed. */
	public FailureCodeTracker errorCodes;
	/** If a non-serious error, the URI we expect the insert to go to if it had succeeded. */
	public FreenetURI uri;

	/** Extra detail message */
	public final String extra;
	
	/** Get the failure mode. */
	public InsertExceptionMode getMode() {
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

	public InsertException(InsertExceptionMode m, String msg, FreenetURI expectedURI) {
		super(getMessage(m)+": "+msg);
		extra = msg;
		mode = m;
		errorCodes = null;
		this.uri = expectedURI;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+msg, this);
	}
	
	public InsertException(InsertExceptionMode m, FreenetURI expectedURI) {
		super(getMessage(m));
		extra = null;
		mode = m;
		errorCodes = null;
		this.uri = expectedURI;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

	public InsertException(InsertExceptionMode mode, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+e.getMessage());
		extra = e.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+e, this);
	}

	public InsertException(InsertExceptionMode mode, String message, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+message+": "+e.getMessage());
		extra = e.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode)+": "+e, this);
	}

	public InsertException(InsertExceptionMode mode, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
		super(getMessage(mode));
		extra = null;
		this.mode = mode;
		this.errorCodes = errorCodes;
		this.uri = expectedURI;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

    public InsertException(InsertExceptionMode mode, String message, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
        super(message == null ? getMessage(mode) : (getMessage(mode)+": "+message));
        extra = message;
        this.mode = mode;
        this.errorCodes = errorCodes;
        this.uri = expectedURI;
        if(mode == InsertExceptionMode.INTERNAL_ERROR)
            Logger.error(this, "Internal error: "+this);
        else if(logMINOR) 
            Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
    }

	public InsertException(InsertExceptionMode mode) {
		super(getMessage(mode));
		extra = null;
		this.mode = mode;
		this.errorCodes = null;
		this.uri = null;
		if(mode == InsertExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "Creating InsertException: "+getMessage(mode), this);
	}

	public InsertException(InsertException e) {
		super(e.getMessage());
		extra = e.extra;
		mode = e.mode;
		errorCodes = e.errorCodes == null ? null : e.errorCodes.clone();
		uri = e.uri;
	}

	public InsertException(TooManyFilesInsertException e) {
		this(InsertExceptionMode.TOO_MANY_FILES, (String)null, null);
	}

	public static InsertException constructFrom(LowLevelPutException e) {
	    switch(e.code) {
	    case LowLevelPutException.COLLISION:
	        return new InsertException(InsertExceptionMode.COLLISION);
	    case LowLevelPutException.INTERNAL_ERROR:
	        return new InsertException(InsertExceptionMode.INTERNAL_ERROR);
	    case LowLevelPutException.REJECTED_OVERLOAD:
	        return new InsertException(InsertExceptionMode.REJECTED_OVERLOAD);
	    case LowLevelPutException.ROUTE_NOT_FOUND:
	        return new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND);
        case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
            return new InsertException(InsertExceptionMode.ROUTE_REALLY_NOT_FOUND);
	    default:
	        Logger.error(InsertException.class, "Unknown LowLevelPutException: "+e+" code "+e.code, new Exception("error"));
	        return new InsertException(InsertExceptionMode.INTERNAL_ERROR, "Unknown error "+e.code, null);
	    }
    }
	
    private static final HashMap<Integer, InsertExceptionMode> modes = 
        new HashMap<Integer, InsertExceptionMode>();

    public static enum InsertExceptionMode {
        
        /** Caller supplied a URI we cannot use */
        INVALID_URI(1),
        /** Failed to read from or write to a bucket; a kind of internal error */
        BUCKET_ERROR(2),
        /** Internal error of some sort */
        INTERNAL_ERROR(3),
        /** Downstream node was overloaded */
        REJECTED_OVERLOAD(4),
        /** Couldn't find enough nodes to send the data to */
        ROUTE_NOT_FOUND(5),
        /** There were fatal errors in a splitfile insert. */
        FATAL_ERRORS_IN_BLOCKS(6),
        /** Could not insert a splitfile because a block failed too many times */
        TOO_MANY_RETRIES_IN_BLOCKS(7),
        /** Not able to leave the node at all */
        ROUTE_REALLY_NOT_FOUND(8),
        /** Collided with pre-existing content */
        COLLISION(9),
        /** Cancelled by user */
        CANCELLED(10),
        /** Meta string used in the key (most probably '/') */
        META_STRINGS_NOT_SUPPORTED(11),
        /** Invalid binary blob data supplied so cannot insert it */
        BINARY_BLOB_FORMAT_ERROR(12),
        /** Too many files in a directory in a site insert */
        TOO_MANY_FILES(13),
        /** File being uploaded is bigger than maximum supported size */
        TOO_BIG(14);
        
        
        public final int code;
        InsertExceptionMode(int code) {
            this.code = code;
            if(code < 0 || code >= UPPER_LIMIT_ERROR_CODE)
                throw new IllegalArgumentException();
            if(modes.containsKey(code))
                throw new IllegalArgumentException();
            modes.put(code, this);
        }
        public static InsertExceptionMode getByCode(int code) {
            if(modes.get(code) == null) throw new IllegalArgumentException();
            return modes.get(code);
        }

    }

	/** There will never be more error codes than this constant. Must not change, used for some
	 * data structures. */
	public static final int UPPER_LIMIT_ERROR_CODE = 1024;

	/** Get the (localised) short name of this failure mode. */
	public static String getMessage(InsertExceptionMode mode) {
        // FIXME change the l10n to use the keyword not the code
		String ret = NodeL10n.getBase().getString("InsertException.longError."+mode.code);
		if(ret == null)
			return "Unknown error "+mode;
		else return ret;
	}

	/** Get the (localised) long explanation for this failure mode. */
	public static String getShortMessage(InsertExceptionMode mode) {
	    // FIXME change the l10n to use the keyword not the code
		String ret = NodeL10n.getBase().getString("InsertException.shortError."+mode.code);
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
	
	public static boolean isFatal(InsertExceptionMode mode) {
		switch(mode) {
		case INVALID_URI:
		case FATAL_ERRORS_IN_BLOCKS:
		case COLLISION:
		case CANCELLED:
		case META_STRINGS_NOT_SUPPORTED:
		case BINARY_BLOB_FORMAT_ERROR:
		case TOO_BIG:
        case BUCKET_ERROR: // maybe. No point retrying.
        case INTERNAL_ERROR: // maybe. No point retrying.
			return true;
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
			return new InsertException(errors.getFirstCodeInsert());
		}
		InsertExceptionMode mode;
		if(errors.isFatal(true))
			mode = InsertExceptionMode.FATAL_ERRORS_IN_BLOCKS;
		else
			mode = InsertExceptionMode.TOO_MANY_RETRIES_IN_BLOCKS;
		return new InsertException(mode, errors, null);
	}
	
	@Override
	public InsertException clone() {
		// Cloneable shuts up findbugs, but we need to deep copy errorCodes.
		return new InsertException(this);
	}

}
