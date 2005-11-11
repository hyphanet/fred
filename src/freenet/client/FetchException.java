package freenet.client;

import java.io.IOException;

import freenet.support.Logger;

/**
 * Generic exception thrown by a Fetcher. All other exceptions are converted to one of
 * these to tell the client.
 */
public class FetchException extends Exception {

	private static final long serialVersionUID = -1106716067841151962L;
	
	final int mode;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public FetchException(int m) {
		super(getMessage(m));
		mode = m;
		Logger.minor(this, "FetchException("+getMessage(mode)+")", this);
	}

	public FetchException(MetadataParseException e) {
		super(getMessage(INVALID_METADATA)+": "+e.getMessage());
		mode = INVALID_METADATA;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e,e);
	}

	public FetchException(ArchiveFailureException e) {
		super(getMessage(INVALID_METADATA)+": "+e.getMessage());
		mode = ARCHIVE_FAILURE;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e,e);
	}

	public FetchException(int mode, IOException e) {
		super(getMessage(INVALID_METADATA)+": "+e.getMessage());
		this.mode = mode;
		initCause(e);
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+e.getMessage(),e);
	}

	public FetchException(int mode, String msg) {
		super(getMessage(mode)+": "+msg);
		this.mode = mode;
		Logger.minor(this, "FetchException("+getMessage(mode)+"): "+msg,this);
	}

	private static String getMessage(int mode) {
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
			return "Internal error, maybe disk full or permissions problem?";
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
}
