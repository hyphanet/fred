/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashMap;

import freenet.client.filter.DataFilterException;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.support.Logger;

/**
 * Thrown when a high-level request (fetch) fails. Indicates why, whether it is worth retrying, and may give a 
 * new URI to try, the expected size of the file, its expected MIME type, and whether these are reliable.
 * For most failure modes, except INTERNAL_ERROR there will be no stack trace, or it will be unhelpful or 
 * inaccurate. 
 */
public class FetchException extends Exception implements Cloneable {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(FetchException.class);
	}

	private static final long serialVersionUID = -1106716067841151962L;
	
	/** Failure mode */
	public final FetchExceptionMode mode;
	
	/** Try this URI instead. If we fetch a USK and there is a more recent version, for example, we will get
	 * a FetchException, but it will give a new URI to try so we can update our links, bookmarks, or convert
	 * it to an HTTP Permanent Redirect. */
	public final FreenetURI newURI;
	
	/** The expected size of the data had the fetch succeeded, or -1. May not be accurate. If retrying 
	 * after TOO_BIG, you need to set the temporary and final data limits to at least this big! */
	public long expectedSize;
	
	/** The expected final MIME type, or null. */
	String expectedMimeType;
	
	/** If true, the expected MIME type and size are probably accurate. */
	boolean finalizedSizeAndMimeType;
	
	/** Do we know the expected MIME type of the data? */
	public String getExpectedMimeType() {
		return expectedMimeType;
	}

	/** Do we have any idea of the final size of the data? */
	public boolean finalizedSize() {
		return finalizedSizeAndMimeType;
	}
	
	/** If there are many failures, usually in a splitfile fetch, tracks the number of failures of each 
	 * type. */
	public final FailureCodeTracker errorCodes;
	
	/** Extra information about the failure. */
	public final String extraMessage;
	
	/** Get the failure mode. */
	public FetchExceptionMode getMode() {
		return mode;
	}
	
	public FetchException(FetchExceptionMode m) {
		super(getMessage(m));
		extraMessage = null;
		mode = m;
		errorCodes = null;
		newURI = null;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode m, long expectedSize, boolean finalizedSize, String expectedMimeType) {
		super(getMessage(m));
		extraMessage = null;
		this.finalizedSizeAndMimeType = finalizedSize;
		mode = m;
		errorCodes = null;
		newURI = null;
		this.expectedSize = expectedSize;
		this.expectedMimeType = expectedMimeType;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}
	
	public FetchException(FetchExceptionMode m, long expectedSize, boolean finalizedSize, String expectedMimeType, FreenetURI uri) {
		super(getMessage(m));
		extraMessage = null;
		this.finalizedSizeAndMimeType = finalizedSize;
		mode = m;
		errorCodes = null;
		newURI = uri;
		this.expectedSize = expectedSize;
		this.expectedMimeType = expectedMimeType;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}
	
	public FetchException(MetadataParseException e) {
		super(getMessage(FetchExceptionMode.INVALID_METADATA)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = FetchExceptionMode.INVALID_METADATA;
		errorCodes = null;
		initCause(e);
		newURI = null;
		expectedSize = -1;
		if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(ArchiveFailureException e) {
		super(getMessage(FetchExceptionMode.ARCHIVE_FAILURE)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = FetchExceptionMode.ARCHIVE_FAILURE;
		errorCodes = null;
		newURI = null;
		initCause(e);
		expectedSize = -1;
		if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(ArchiveRestartException e) {
		super(getMessage(FetchExceptionMode.ARCHIVE_RESTART)+": "+e.getMessage());
		extraMessage = e.getMessage();
		mode = FetchExceptionMode.ARCHIVE_FAILURE;
		errorCodes = null;
		initCause(e);
		newURI = null;
		expectedSize = -1;
		if(logMINOR)
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, Throwable t) {
		super(getMessage(mode)+": "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(t);
		newURI = null;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, String reason, Throwable t) {
		super(reason+" : "+getMessage(mode)+": "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = mode;
		errorCodes = null;
		initCause(t);
		newURI = null;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, long expectedSize, String reason, Throwable t, String expectedMimeType) {
		super(reason+" : "+getMessage(mode)+": "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = mode;
		this.expectedSize = expectedSize;
		this.expectedMimeType = expectedMimeType;
		errorCodes = null;
		initCause(t);
		newURI = null;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(long expectedSize, DataFilterException t, String expectedMimeType) {
		super(getMessage(FetchExceptionMode.CONTENT_VALIDATION_FAILED)+" "+NodeL10n.getBase().getString("FetchException.unsafeContentDetails")+" "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = FetchExceptionMode.CONTENT_VALIDATION_FAILED;
		this.expectedSize = expectedSize;
		this.expectedMimeType = expectedMimeType;
		errorCodes = null;
		initCause(t);
		newURI = null;
		if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, long expectedSize, Throwable t, String expectedMimeType) {
		super(getMessage(mode)+": "+t.getMessage());
		extraMessage = t.getMessage();
		this.mode = mode;
		this.expectedSize = expectedSize;
		this.expectedMimeType = expectedMimeType;
		errorCodes = null;
		initCause(t);
		newURI = null;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, FailureCodeTracker errorCodes) {
		super(getMessage(mode));
		if(errorCodes.isEmpty()) {
		    Logger.error(this, "Failing with no error codes?!", new Exception("error"));
		}
		extraMessage = null;
		this.mode = mode;
		this.errorCodes = errorCodes;
		newURI = null;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}
	
    public FetchException(FetchExceptionMode mode, FailureCodeTracker errorCodes, String msg) {
        super(getMessage(mode)+": "+msg);
        if(errorCodes.isEmpty()) {
            Logger.error(this, "Failing with no error codes?!", new Exception("error"));
        }
        extraMessage = msg;
        this.mode = mode;
        this.errorCodes = errorCodes;
        newURI = null;
        expectedSize = -1;
        if(mode == FetchExceptionMode.INTERNAL_ERROR)
            Logger.error(this, "Internal error: "+this);
        else if(logMINOR) 
            Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
    }
    
	public FetchException(FetchExceptionMode mode, String msg) {
		super(getMessage(mode)+": "+msg);
		extraMessage = msg;
		errorCodes = null;
		this.mode = mode;
		newURI = null;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchExceptionMode mode, FreenetURI newURI) {
		super(getMessage(mode));
		extraMessage = null;
		this.mode = mode;
		errorCodes = null;
		this.newURI = newURI;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}
	
	public FetchException(FetchExceptionMode mode, String msg, FreenetURI uri) {
		super(getMessage(mode)+": "+msg);
		extraMessage = msg;
		errorCodes = null;
		this.mode = mode;
		newURI = uri;
		expectedSize = -1;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchException e, FetchExceptionMode newMode) {
		super(getMessage(newMode)+(e.extraMessage != null ? ": "+e.extraMessage : ""));
		this.mode = newMode;
		this.newURI = e.newURI;
		this.errorCodes = e.errorCodes;
		this.expectedMimeType = e.expectedMimeType;
		this.expectedSize = e.expectedSize;
		this.extraMessage = e.extraMessage;
		this.finalizedSizeAndMimeType = e.finalizedSizeAndMimeType;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchException e, FreenetURI uri) {
		super(e.getMessage());
		if(e.getCause() != null)
			initCause(e.getCause());
		this.mode = e.mode;
		this.newURI = uri;
		this.errorCodes = e.errorCodes;
		this.expectedMimeType = e.expectedMimeType;
		this.expectedSize = e.expectedSize;
		this.extraMessage = e.extraMessage;
		this.finalizedSizeAndMimeType = e.finalizedSizeAndMimeType;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}

	public FetchException(FetchException e) {
		super(e.getMessage());
		initCause(e);
		this.mode = e.mode;
		this.newURI = e.newURI;
		this.errorCodes = e.errorCodes == null ? null : e.errorCodes.clone();
		this.expectedMimeType = e.expectedMimeType;
		this.expectedSize = e.expectedSize;
		this.extraMessage = e.extraMessage;
		this.finalizedSizeAndMimeType = e.finalizedSizeAndMimeType;
		if(mode == FetchExceptionMode.INTERNAL_ERROR)
			Logger.error(this, "Internal error: "+this);
		else if(logMINOR) 
			Logger.minor(this, "FetchException("+getMessage(mode)+ ')', this);
	}
	
	protected FetchException() {
	    // For serialization.
	    mode = null;
	    newURI = null;
	    errorCodes = null;
	    extraMessage = null;
	}

	/** Get the short name of this exception's failure. */
	public String getShortMessage() {
		if (getCause() == null) return getShortMessage(mode);
		else return getCause().toString();
	}

	/** Get the (localised) short name of this failure mode. */
	public static String getShortMessage(FetchExceptionMode mode) {
        // FIXME change the l10n to use the names rather than codes
	    int code = mode.code;
		String ret = NodeL10n.getBase().getString("FetchException.shortError."+code);
		if(ret == null || ret.equals(""))
			return "Unknown code "+mode;
		else return ret;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(200);
		sb.append("FetchException:");
		sb.append(getMessage(mode));
		sb.append(':');
		sb.append(newURI);
		sb.append(':');
		sb.append(expectedSize);
		sb.append(':');
		sb.append(expectedMimeType);
		sb.append(':');
		sb.append(finalizedSizeAndMimeType);
		sb.append(':');
		sb.append(errorCodes);
		sb.append(':');
		sb.append(extraMessage);
		return sb.toString();
	}
	
	public String toUserFriendlyString() {
		if(extraMessage == null)
			return getShortMessage(mode);
		else
			return getShortMessage(mode) + " : " + extraMessage;
	}

	/** Get the (localised) long explanation for this failure mode. */
	public static String getMessage(FetchExceptionMode mode) {
	    if(mode == null) throw new NullPointerException();
        int code = mode.code;
        // FIXME change the l10n to use the names rather than codes
		String ret = NodeL10n.getBase().getString("FetchException.longError."+code);
		if(ret == null)
			return "Unknown fetch error code: "+mode;
		else return ret;
	}
	
    private static final HashMap<Integer, FetchExceptionMode> modes = 
        new HashMap<Integer, FetchExceptionMode>();

    // Modes should stay the same even if we remove some elements.
	public static enum FetchExceptionMode {
	    
	    // FIXME many of these are not used any more
	    
	    /** Too many levels of recursion into archives */
	    @Deprecated // not used
	    TOO_DEEP_ARCHIVE_RECURSION(1),
	    /** Don't know what to do with splitfile */
	    @Deprecated // not used
	    UNKNOWN_SPLITFILE_METADATA(2),
	    /** Don't know what to do with metadata */
        UNKNOWN_METADATA(3),
        /** Got a MetadataParseException */
        INVALID_METADATA(4),
        /** Got an ArchiveFailureException */
        ARCHIVE_FAILURE(5),
        /** Failed to decode a block. But we found it i.e. it is valid on the network level. */
        BLOCK_DECODE_ERROR(6),
        /** Too many split metadata levels */
        @Deprecated // not used
        TOO_MANY_METADATA_LEVELS(7),
        /** Too many archive restarts */
        TOO_MANY_ARCHIVE_RESTARTS(8),
        /** Too deep recursion */
        // FIXME some TOO_MUCH_RECURSION may be TOO_DEEP_ARCHIVE_RECURSION 
        TOO_MUCH_RECURSION(9),
        /** Tried to access an archive file but not in an archive */
        NOT_IN_ARCHIVE(10),
        /** Too many meta strings. E.g. requesting CHK@blah,blah,blah as CHK@blah,blah,blah/filename.ext */
        TOO_MANY_PATH_COMPONENTS(11),
        /** Failed to read from or write to a bucket; a kind of internal error */
        BUCKET_ERROR(12),
        /** Data not found */
        DATA_NOT_FOUND(13),
        /** Route not found */
        ROUTE_NOT_FOUND(14),
        /** Downstream overload */
        REJECTED_OVERLOAD(15),
        /** Too many redirects */
        @Deprecated // not used
        TOO_MANY_REDIRECTS(16),
        /** An internal error occurred */
        INTERNAL_ERROR(17),
        /** The node found the data but the transfer failed */
        TRANSFER_FAILED(18),
        /** Splitfile error. This should be a SplitFetchException. */
        SPLITFILE_ERROR(19),
        /** Invalid URI. */
        INVALID_URI(20),
        /** Too big */
        TOO_BIG(21),
        /** Metadata too big */
        TOO_BIG_METADATA(22),
        /** Splitfile has too big segments */
        TOO_MANY_BLOCKS_PER_SEGMENT(23),
        /** Not enough meta strings in URI given and no default document */
        NOT_ENOUGH_PATH_COMPONENTS(24),
        /** Explicitly cancelled */
        CANCELLED(25),
        /** Archive restart */
        ARCHIVE_RESTART(26),
        /** There is a more recent version of the USK, ~= HTTP 301; FProxy will turn this into a 301 */
        PERMANENT_REDIRECT(27),
        /** Not all data was found; some DNFs but some successes */
        ALL_DATA_NOT_FOUND(28),
        /** Requestor specified a list of allowed MIME types, and the key's type wasn't in the list */
        WRONG_MIME_TYPE(29),
        /** A node killed the request because it had recently been tried and had DNFed */
        RECENTLY_FAILED(30),
        /** Content filtration has generally failed to produce clean data */
        CONTENT_VALIDATION_FAILED(31),
        /** The content filter does not recognize this data type */
        CONTENT_VALIDATION_UNKNOWN_MIME(32),
        /** The content filter knows this data type is dangerous */
        CONTENT_VALIDATION_BAD_MIME(33),
        /** The metadata specified a hash but the data didn't match it. */
        CONTENT_HASH_FAILED(34),
        /** FEC decode produced a block that doesn't match the data in the original splitfile. */
        SPLITFILE_DECODE_ERROR(35),
        /** For a filtered download to disk, the MIME type is incompatible with the 
         * extension, potentially resulting in data on disk filtered with one MIME 
         * type but accessed by the operating system with another MIME type. This 
         * is equivalent to it not being filtered at all i.e. potentially dangerous.
         */
        MIME_INCOMPATIBLE_WITH_EXTENSION(36),
        /** Not enough disk space to start a download or the next stage of a download. */
        NOT_ENOUGH_DISK_SPACE(37);
	    
	    
	    public final int code;
        FetchExceptionMode(int code) {
            this.code = code;
            if(code < 0 || code >= UPPER_LIMIT_ERROR_CODE)
                throw new IllegalArgumentException();
            if(modes.containsKey(code))
                throw new IllegalArgumentException();
            modes.put(code, this);
            if(code > MAX_ERROR_CODE) MAX_ERROR_CODE = code;
        }
        public static FetchExceptionMode getByCode(int code) {
            if(modes.get(code) == null) throw new IllegalArgumentException();
            return modes.get(code);
        }

	}
	
	private static int MAX_ERROR_CODE;
	
	/** There will never be more error codes than this constant. Must not change, used for some
	 * data structures. */
	public static final int UPPER_LIMIT_ERROR_CODE = 1024;

	/** Is an error fatal i.e. is there no point retrying? */
	public boolean isFatal() {
		return isFatal(mode);
	}

	/** Is an error mode fatal i.e. is there no point retrying? */
	public static boolean isFatal(FetchExceptionMode mode) {
		switch(mode) {
		// Problems with the data as inserted, or the URI given. No point retrying.
		case ARCHIVE_FAILURE:
		case BLOCK_DECODE_ERROR:
		case TOO_MANY_PATH_COMPONENTS:
		case NOT_ENOUGH_PATH_COMPONENTS:
		case INVALID_METADATA:
		case NOT_IN_ARCHIVE:
		case TOO_DEEP_ARCHIVE_RECURSION:
		case TOO_MANY_ARCHIVE_RESTARTS:
		case TOO_MANY_METADATA_LEVELS:
		case TOO_MANY_REDIRECTS:
		case TOO_MUCH_RECURSION:
		case UNKNOWN_METADATA:
		case UNKNOWN_SPLITFILE_METADATA:
		case INVALID_URI:
		case TOO_BIG:
		case TOO_BIG_METADATA:
		case TOO_MANY_BLOCKS_PER_SEGMENT:
		case CONTENT_HASH_FAILED:
		case SPLITFILE_DECODE_ERROR:
			return true;

		// Low level errors, can be retried
		case DATA_NOT_FOUND:
		case ROUTE_NOT_FOUND:
		case REJECTED_OVERLOAD:
		case TRANSFER_FAILED:
		case ALL_DATA_NOT_FOUND:
		case RECENTLY_FAILED: // wait a bit, but fine
		// Not usually fatal
		case SPLITFILE_ERROR:
			return false;
			
		case BUCKET_ERROR:
		case INTERNAL_ERROR:
		case NOT_ENOUGH_DISK_SPACE:
			// No point retrying.
			return true;
		
		//The ContentFilter failed to validate the data. Retrying won't fix this.
			case CONTENT_VALIDATION_FAILED:
			case CONTENT_VALIDATION_UNKNOWN_MIME:
			case CONTENT_VALIDATION_BAD_MIME:
			case MIME_INCOMPATIBLE_WITH_EXTENSION:
				return true;

		// Wierd ones
		case CANCELLED:
		case ARCHIVE_RESTART:
		case PERMANENT_REDIRECT:
		case WRONG_MIME_TYPE:
			// Fatal
			return true;
			
		default:
			Logger.error(FetchException.class, "Do not know if error code is fatal: "+getMessage(mode));
			return false; // assume it isn't
		}
	}
	
	public boolean isDefinitelyFatal() {
		return isDefinitelyFatal(mode);
	}
	
	public static boolean isDefinitelyFatal(FetchExceptionMode mode) {
		switch(mode) {
		// Problems with the data as inserted, or the URI given. No point retrying.
		case ARCHIVE_FAILURE:
		case BLOCK_DECODE_ERROR:
		case TOO_MANY_PATH_COMPONENTS:
		case NOT_ENOUGH_PATH_COMPONENTS:
		case INVALID_METADATA:
		case NOT_IN_ARCHIVE:
		case TOO_DEEP_ARCHIVE_RECURSION:
		case TOO_MANY_ARCHIVE_RESTARTS:
		case TOO_MANY_METADATA_LEVELS:
		case TOO_MANY_REDIRECTS:
		case TOO_MUCH_RECURSION:
		case UNKNOWN_METADATA:
		case UNKNOWN_SPLITFILE_METADATA:
		case INVALID_URI:
		case TOO_BIG:
		case TOO_BIG_METADATA:
		case TOO_MANY_BLOCKS_PER_SEGMENT:
		case CONTENT_HASH_FAILED:
		case SPLITFILE_DECODE_ERROR:
			return true;

		// Low level errors, can be retried
		case DATA_NOT_FOUND:
		case ROUTE_NOT_FOUND:
		case REJECTED_OVERLOAD:
		case TRANSFER_FAILED:
		case ALL_DATA_NOT_FOUND:
		case RECENTLY_FAILED: // wait a bit, but fine
		// Not usually fatal
		case SPLITFILE_ERROR:
			return false;
			
		case BUCKET_ERROR:
		case INTERNAL_ERROR:
		case NOT_ENOUGH_DISK_SPACE:
			// No point retrying. 
			// But it's not really fatal. I.e. it's not necessarily a problem with the inserted data.
			return false;
		
		//The ContentFilter failed to validate the data. Retrying won't fix this.
			case CONTENT_VALIDATION_FAILED:
			case CONTENT_VALIDATION_UNKNOWN_MIME:
			case CONTENT_VALIDATION_BAD_MIME:
			case MIME_INCOMPATIBLE_WITH_EXTENSION:
				return true;

		// Wierd ones
		// Not necessarily a problem with the inserted data.
		case CANCELLED:
			return false;
			
		case ARCHIVE_RESTART:
		case PERMANENT_REDIRECT:
		case WRONG_MIME_TYPE:
			// Fatal
			return true;
			
		default:
			Logger.error(FetchException.class, "Do not know if error code is fatal: "+getMessage(mode));
			return false; // assume it isn't
		}
	}

	/** Call to indicate the expected size and MIME type are unreliable. */
	public void setNotFinalizedSize() {
		this.finalizedSizeAndMimeType = false;
	}

	@Override
	public FetchException clone() {
		// Cloneable shuts up findbugs but we need a deep copy.
		return new FetchException(this);
	}

	public boolean isDataFound() {
		return isDataFound(mode, errorCodes);
	}
	
	public static boolean isDataFound(FetchExceptionMode mode, FailureCodeTracker errorCodes) {
		switch(mode) {
		case TOO_DEEP_ARCHIVE_RECURSION:
		case UNKNOWN_SPLITFILE_METADATA:
		case TOO_MANY_REDIRECTS:
		case UNKNOWN_METADATA:
		case INVALID_METADATA:
		case ARCHIVE_FAILURE:
		case BLOCK_DECODE_ERROR:
		case TOO_MANY_METADATA_LEVELS:
		case TOO_MANY_ARCHIVE_RESTARTS:
		case TOO_MUCH_RECURSION:
		case NOT_IN_ARCHIVE:
		case TOO_MANY_PATH_COMPONENTS:
		case TOO_BIG:
		case TOO_BIG_METADATA:
		case TOO_MANY_BLOCKS_PER_SEGMENT:
		case NOT_ENOUGH_PATH_COMPONENTS:
		case ARCHIVE_RESTART:
		case CONTENT_VALIDATION_FAILED:
		case CONTENT_VALIDATION_UNKNOWN_MIME:
		case CONTENT_VALIDATION_BAD_MIME:
		case CONTENT_HASH_FAILED:
		case SPLITFILE_DECODE_ERROR:
		case NOT_ENOUGH_DISK_SPACE:
			return true;
		case SPLITFILE_ERROR:
			return errorCodes.isDataFound();
		default:
			return false;
		}
	}
	
	public boolean isDNF() {
		switch(mode) {
		case DATA_NOT_FOUND:
		case ALL_DATA_NOT_FOUND:
		case RECENTLY_FAILED:
			return true;
		default:
			return false;
		}
	}

    public static boolean isErrorCode(int code) {
        return code >= 0 && code <= MAX_ERROR_CODE && code < UPPER_LIMIT_ERROR_CODE;
    }

}
