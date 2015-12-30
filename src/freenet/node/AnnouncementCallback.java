package freenet.node;

/**
 * Callback for a local announcement.
 */
public interface AnnouncementCallback {
	
	public void completed();
	public void bogusNoderef(String reason);
	public void nodeFailed(PeerNode pn, String reason);
	/* RNF */
	public void noMoreNodes();
	public void addedNode(PeerNode pn);
	public void nodeNotWanted();
	/** Node valid but locally not added e.g. because we already have it */
	public void nodeNotAdded();
	public void acceptedSomewhere();
	/** Relayed a valid noderef to the (downstream) node which started the announcement */
	public void relayedNoderef();
	
}
