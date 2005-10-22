package freenet.client;

/**
 * Thrown when an archive operation fails.
 */
public class ArchiveFailureException extends Exception {

	public static final String TOO_MANY_LEVELS = "Too many archive levels";

	public ArchiveFailureException(String message) {
		super(message);
	}

}
