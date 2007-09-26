package freenet.node;

public class OpennetPeerNodeStatus extends PeerNodeStatus {

	OpennetPeerNodeStatus(PeerNode peerNode) {
		super(peerNode);
		timeLastSuccess = ((OpennetPeerNode)peerNode).timeLastSuccess();
	}

	public final long timeLastSuccess;
	
}
