package freenet.client;

import java.io.IOException;

/**
 * Thrown when an archive operation fails.
 */
public class ArchiveFailureException extends Exception {

	private static final long serialVersionUID = -5915105120222575469L;
	
	public static final String TOO_MANY_LEVELS = "Too many archive levels";

	public ArchiveFailureException(String message) {
		super(message);
	}

	public ArchiveFailureException(String message, IOException e) {
		super(message);
		initCause(e);
	}

}
