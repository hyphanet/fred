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
	
}
