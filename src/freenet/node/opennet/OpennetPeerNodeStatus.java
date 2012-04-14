package freenet.node.opennet;

import freenet.node.PeerNode;
import freenet.node.PeerNodeStatus;

public class OpennetPeerNodeStatus extends PeerNodeStatus {

	OpennetPeerNodeStatus(PeerNode peerNode, boolean noHeavy) {
		super(peerNode, noHeavy);
		timeLastSuccess = ((OpennetPeerNode)peerNode).timeLastSuccess();
	}

	public final long timeLastSuccess;
	
}
