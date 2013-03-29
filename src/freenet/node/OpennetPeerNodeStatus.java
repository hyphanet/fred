package freenet.node;

public class OpennetPeerNodeStatus extends PeerNodeStatus {

	OpennetPeerNodeStatus(PeerNode peerNode, boolean noHeavy, PeerNodeStatusContext context) {
		super(peerNode, noHeavy, context);
		timeLastSuccess = ((OpennetPeerNode)peerNode).timeLastSuccess();
	}

	public final long timeLastSuccess;
	
}
