package freenet.node;

public class OpennetPeerNodeStatus extends PeerNodeStatus {

	OpennetPeerNodeStatus(PeerNode peerNode, boolean noHeavy) {
		super(peerNode, noHeavy);
		timeLastSuccess = ((OpennetPeerNode)peerNode).timeLastSuccess();
	}

	public final long timeLastSuccess;
	
}
