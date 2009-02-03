package freenet.node;

/**
 * A SendableRequest may include many SendableRequestItem's.
 * Typically for requests, these are just an integer indicating which key
 * to fetch. But for inserts, these will often include the actual data to
 * insert, or some means of getting it without access to the database.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface SendableRequestItem {

	/** Called when a request is abandoned. Whether this is called on
	 * a successful request is up to the SendableRequestSender. */
	public void dump();

}
