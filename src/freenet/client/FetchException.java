package freenet.client;

public class FetchException extends Exception {

	final int mode;
	
	public FetchException(int m) {
		mode = m;
	}

	/** Too many levels of recursion into archives */
	static final int TOO_DEEP_ARCHIVE_RECURSION = 1;
	/** Don't know what to do with splitfile */
	static final int UNKNOWN_SPLITFILE_METADATA = 2;
	/** Don't know what to do with metadata */
	static final int UNKNOWN_METADATA = 3;
	
}
