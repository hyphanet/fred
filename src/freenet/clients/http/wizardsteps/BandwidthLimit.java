package freenet.clients.http.wizardsteps;

/**
 * A bandwidth usage rate limit, measured in bytes.
 */
public class BandwidthLimit {

	/**
	 * Download limit in bytes.
	 */
	public final long downBytes;

	/**
	 * Upload limit in bytes.
	 */
	public final long upBytes;
	
	public final String descriptionKey;
	
	public final boolean maybeDefault;

	public BandwidthLimit(long downBytes, long upBytes, String descriptionKey, boolean maybeDefault) {
		this.downBytes = downBytes;
		this.upBytes = upBytes;
		this.descriptionKey = descriptionKey;
		this.maybeDefault = maybeDefault;
	}
}
