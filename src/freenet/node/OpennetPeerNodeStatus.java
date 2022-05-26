package freenet.node;

public class OpennetPeerNodeStatus extends PeerNodeStatus {

	OpennetPeerNodeStatus(PeerNode peerNode, TrustScoreManager peerScores, boolean noHeavy) {
		super(peerNode, peerScores, noHeavy);
		timeLastSuccess = ((OpennetPeerNode)peerNode).timeLastSuccess();
	}

	public final long timeLastSuccess;
	
}
