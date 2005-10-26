package freenet.client;

public class FetchException extends Exception {

	final int mode;
	
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

	/** Too many levels of recursion into archives */
	static final int TOO_DEEP_ARCHIVE_RECURSION = 1;
	/** Don't know what to do with splitfile */
	static final int UNKNOWN_SPLITFILE_METADATA = 2;
	/** Too many ordinary redirects */
	static final int TOO_MANY_REDIRECTS = 3;
	/** Don't know what to do with metadata */
	static final int UNKNOWN_METADATA = 3;
	/** Got a MetadataParseException */
	static final int INVALID_METADATA = 4;
	/** Got an ArchiveFailureException */
	static final int ARCHIVE_FAILURE = 5;
	/** Failed to decode a block */
	static final int BLOCK_DECODE_ERROR = 6;
	/** Too many split metadata levels */
	static final int TOO_MANY_METADATA_LEVELS = 7;
	/** Too many archive restarts */
	static final int TOO_MANY_ARCHIVE_RESTARTS = 8;
	/** Too deep recursion */
	static final int TOO_MUCH_RECURSION = 9;
	/** Tried to access an archive file but not in an archive */
	static final int NOT_IN_ARCHIVE = 10;
}
