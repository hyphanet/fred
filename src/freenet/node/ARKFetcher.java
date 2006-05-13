package freenet.node;

/**
 * Fetch an ARK. Permanent, tied to a PeerNode, stops itself after a successful fetch.
 */
public class ARKFetcher {

	final PeerNode peer;
	final Node node;

	public ARKFetcher(PeerNode peer, Node node) {
		this.peer = peer;
		this.node = node;
	}

	/**
	 * Called when the node starts / is added, and also when we fail to connect twice 
	 * after a new reference. (So we get one from the ARK, we wait for the current
	 * connect attempt to fail, we start another one, that fails, we start another one,
	 * that also fails, so we try the fetch again to see if we can find something more
	 * recent).
	 */
	public void start() {
		// Start fetch
		// FIXME
	}
	
	/**
	 * Called when the node connects successfully.
	 */
	public void stop() {
		// Stop fetch
		// FIXME
	}
	
}
