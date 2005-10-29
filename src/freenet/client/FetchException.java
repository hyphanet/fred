package freenet.client;

import java.io.IOException;

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
		mode = m;
	}

	public FetchException(MetadataParseException e) {
		mode = INVALID_METADATA;
		initCause(e);
	}

	public FetchException(ArchiveFailureException e) {
		mode = ARCHIVE_FAILURE;
		initCause(e);
	}

	public FetchException(int mode, IOException e) {
		this.mode = mode;
		initCause(e);
	}

	/** Too many levels of recursion into archives */
	public static final int TOO_DEEP_ARCHIVE_RECURSION = 1;
	/** Don't know what to do with splitfile */
	public static final int UNKNOWN_SPLITFILE_METADATA = 2;
	/** Too many ordinary redirects */
	public static final int TOO_MANY_REDIRECTS = 3;
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
	/** Internal error, probably failed to read from a bucket */
	public static final int BUCKET_ERROR = 12;
}
