package freenet.client;

import freenet.support.Logger;

/**
 * Generic exception thrown by a Fetcher. All other exceptions are converted to one of
 * these to tell the client.
 */
public class FetchException extends Exception {

	private static final long serialVersionUID = -1106716067841151962L;
	
	public final int mode;

	/** For collection errors */
	public final FailureCodeTracker errorCodes;
	
	public final String extraMessage;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public FetchException(int m) {
		super(getMessage(m));
		extraMessage = null;
		mode = m;
		errorCodes = null;
		Logger.minor(this, "FetchException("+getMessage(mode)+")", this);
	}

	public FetchException(MetadataParseException e) {
		super(getMessage(INVALID_METADATA)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = INVALID_METADATA;
		errorCodes = null;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e,e);
	}

	public FetchException(ArchiveFailureException e) {
		super(getMessage(ARCHIVE_FAILURE)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = ARCHIVE_FAILURE;
		errorCodes = null;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e,e);
	}

	public FetchException(ArchiveRestartException e) {
		super(getMessage(ARCHIVE_RESTART)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = ARCHIVE_FAILURE;
		errorCodes = null;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e,e);	}

	public FetchException(int mode, Throwable t) {
		super(getMessage(mode)+": "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(t);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+t.getMessage(),t);
	}

	public FetchException(int mode, FailureCodeTracker errorCodes) {
		super(getMessage(mode));
		extraMessage = null;
		this.mode = mode;
		this.errorCodes = errorCodes;
		Logger.minor(this, "FetchException("+getMessage(mode)+")");
		
	}
	
	public FetchException(int mode, String msg) {
		super(getMessage(mode)+": "+msg);
		extraMessage = msg;
		errorCodes = null;
		this.mode = mode;
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+msg,this);
	}

	public static String getMessage(int mode) {
		switch(mode) {
		case TOO_DEEP_ARCHIVE_RECURSION:
			return "Too many levels of recursion into archives";
		case UNKNOWN_SPLITFILE_METADATA:
			return "Don't know what to do with splitfile";
		case TOO_MANY_REDIRECTS:
			return "Too many redirects - loop?";
		case UNKNOWN_METADATA:
			return "Don't know what to do with metadata";
		case INVALID_METADATA:
			return "Failed to parse metadata";
		case ARCHIVE_FAILURE:
			return "Failure in extracting files from an archive";
		case BLOCK_DECODE_ERROR:
			return "Failed to decode a splitfile block";
		case TOO_MANY_METADATA_LEVELS:
			return "Too many levels of split metadata";
		case TOO_MANY_ARCHIVE_RESTARTS:
			return "Request was restarted too many times due to archives changing";
		case TOO_MUCH_RECURSION:
			return "Too many redirects (too much recursion)"; // FIXME: ???
		case NOT_IN_ARCHIVE:
			return "File not in archive";
		case HAS_MORE_METASTRINGS:
			return "Not a manifest";
		case BUCKET_ERROR:
			return "Internal temp files error, maybe disk full or permissions problem?";
		case DATA_NOT_FOUND:
			return "Data not found";
		case ROUTE_NOT_FOUND:
			return "Route not found - could not find enough nodes to be sure the data doesn't exist";
		case REJECTED_OVERLOAD:
			return "A node was overloaded or timed out";
		case INTERNAL_ERROR:
			return "Internal error, probably a bug";
		case TRANSFER_FAILED:
			return "Found the file, but lost it while receiving the data";
		case SPLITFILE_ERROR:
			return "Splitfile error";
		case INVALID_URI:
			return "Invalid URI";
		case TOO_BIG:
			return "Too big";
		case TOO_BIG_METADATA:
			return "Metadata too big";
		case TOO_MANY_BLOCKS_PER_SEGMENT:
			return "Too many blocks per segment";
		case NOT_ENOUGH_METASTRINGS:
			return "No default document; give more metastrings in URI";
		case CANCELLED:
			return "Cancelled by caller";
		case ARCHIVE_RESTART:
			return "Archive restart";
		default:
			return "Unknown fetch error code: "+mode;
		}
	}
	
	// FIXME many of these are not used any more
	
	/** Too many levels of recursion into archives */
	public static final int TOO_DEEP_ARCHIVE_RECURSION = 1;
	/** Don't know what to do with splitfile */
	public static final int UNKNOWN_SPLITFILE_METADATA = 2;
	/** Too many redirects */
	public static final int TOO_MANY_REDIRECTS = 16;
	/** Don't know what to do with metadata */
	public static final int UNKNOWN_METADATA = 3;
	/** Got a MetadataParseException */
	public static final int INVALID_METADATA = 4;
	/** Got an ArchiveFailureException */
	public static final int ARCHIVE_FAILURE = 5;
	/** Failed to decode a block */
	public static final int BLOCK_DECODE_ERROR = 6;
	/** Too many split metadata levels */
	public static final int TOO_MANY_METADATA_LEVELS = 7;
	/** Too many archive restarts */
	public static final int TOO_MANY_ARCHIVE_RESTARTS = 8;
	/** Too deep recursion */
	public static final int TOO_MUCH_RECURSION = 9;
	/** Tried to access an archive file but not in an archive */
	public static final int NOT_IN_ARCHIVE = 10;
	/** Has more metastrings, can't fulfill them */
	public static final int HAS_MORE_METASTRINGS = 11;
	/** Failed to read from or write to a bucket; a kind of internal error */
	public static final int BUCKET_ERROR = 12;
	/** Data not found */
	public static final int DATA_NOT_FOUND = 13;
	/** Route not found */
	public static final int ROUTE_NOT_FOUND = 14;
	/** Downstream overload */
	public static final int REJECTED_OVERLOAD = 15;
	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 17;
	/** The node found the data but the transfer failed */
	public static final int TRANSFER_FAILED = 18;
	/** Splitfile error. This should be a SplitFetchException. */
	public static final int SPLITFILE_ERROR = 19;
	/** Invalid URI. */
	public static final int INVALID_URI = 20;
	/** Too big */
	public static final int TOO_BIG = 21;
	/** Metadata too big */
	public static final int TOO_BIG_METADATA = 22;
	/** Splitfile has too big segments */
	public static final int TOO_MANY_BLOCKS_PER_SEGMENT = 23;
	/** Not enough meta strings in URI given and no default document */
	public static final int NOT_ENOUGH_METASTRINGS = 24;
	/** Explicitly cancelled */
	public static final int CANCELLED = 25;
	/** Archive restart */
	public static final int ARCHIVE_RESTART = 26;

	/** Is an error fatal i.e. is there no point retrying? */
	public boolean isFatal() {
		switch(mode) {
		// Problems with the data as inserted. No point retrying.
		case FetchException.ARCHIVE_FAILURE:
		case FetchException.BLOCK_DECODE_ERROR:
		case FetchException.HAS_MORE_METASTRINGS:
		case FetchException.INVALID_METADATA:
		case FetchException.NOT_IN_ARCHIVE:
		case FetchException.TOO_DEEP_ARCHIVE_RECURSION:
		case FetchException.TOO_MANY_ARCHIVE_RESTARTS:
		case FetchException.TOO_MANY_METADATA_LEVELS:
		case FetchException.TOO_MANY_REDIRECTS:
		case FetchException.TOO_MUCH_RECURSION:
		case FetchException.UNKNOWN_METADATA:
		case FetchException.UNKNOWN_SPLITFILE_METADATA:
		case FetchException.TOO_BIG:
			return true;

		// Low level errors, can be retried
		case FetchException.DATA_NOT_FOUND:
		case FetchException.ROUTE_NOT_FOUND:
		case FetchException.REJECTED_OVERLOAD:
		case FetchException.TRANSFER_FAILED:
			return false;
			
		case FetchException.BUCKET_ERROR:
		case FetchException.INTERNAL_ERROR:
			// Maybe fatal
			return false;
			
		case FetchException.SPLITFILE_ERROR:
			// Fatal, because there are internal retries
			return true;
			
			// Wierd ones
		case FetchException.CANCELLED:
		case FetchException.ARCHIVE_RESTART:
			// Fatal
			return true;
			
		default:
			Logger.error(this, "Do not know if error code is fatal: "+getMessage(mode));
			return false; // assume it isn't
		}
	}
}
