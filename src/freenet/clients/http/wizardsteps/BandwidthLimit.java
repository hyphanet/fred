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

	public BandwidthLimit(long downBytes, long upBytes) {
		this.downBytes = downBytes;
		this.upBytes = upBytes;
	}
}
