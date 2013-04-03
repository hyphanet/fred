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
	
	/** Get a lightweight object for tracking which SendableRequestItem's
	 * are queued. This will usually be "return this", but if creating your 
	 * SendableRequest is expensive, you may want to define a separate key 
	 * type (especially for transient inserts). */
	public SendableRequestItemKey getKey();

}
