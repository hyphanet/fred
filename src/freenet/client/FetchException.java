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
	
}
