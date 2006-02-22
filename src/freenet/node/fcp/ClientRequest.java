package freenet.node.fcp;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest {

	/** Lost connection */
	public abstract void onLostConnection();
	
	/** Is the request persistent? False = we can drop the request if we lose the connection */
	public abstract boolean isPersistent();

	/** Completed request dropped off the end without being acknowledged */
	public abstract void dropped();

	/** Get identifier string for request */
	public abstract String getIdentifier();
	
	/** Send any pending messages for a persistent request e.g. after reconnecting */
	public abstract void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest);

	// Persistence
	
	static final short PERSIST_CONNECTION = 0;
	static final short PERSIST_REBOOT = 1;
	static final short PERSIST_FOREVER = 2;
	
	public static String persistenceTypeString(short type) {
		switch(type) {
		case PERSIST_CONNECTION:
			return "connection";
		case PERSIST_REBOOT:
			return "reboot";
		case PERSIST_FOREVER:
			return "forever";
		default:
			return Short.toString(type);
		}
	}

}
