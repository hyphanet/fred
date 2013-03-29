package freenet.node;

/** Any expensive operations that need to be done before generating PeerNodeStatus's are kept here.
 * @author toad */
public class PeerNodeStatusContext {
	
	private final PeerManager peers;
	private final Node node;

	public PeerNodeStatusContext(PeerManager peerManager, Node node) {
		this.peers = peerManager;
		this.node = node;
	}

}
