package freenet.node;

import java.util.Map;

import freenet.node.NodeStats.RunningRequestsSnapshot;

/** Any expensive operations that need to be done before generating PeerNodeStatus's are kept here.
 * @author toad */
public class PeerNodeStatusContext {
	
	private final PeerManager peers;
	final Node node;
	private final Map<PeerNode, RunningRequestsSnapshot> requestsSnapshot;
	public final int transfersPerInsert;
	public final double bandwidthAvailableUpperLower;
	public final int peerCount;
	public final boolean ignoreLocalVsRemoteBandwidthLiability;

	public PeerNodeStatusContext(PeerManager peerManager, Node node) {
		this.peers = peerManager;
		this.node = node;
		requestsSnapshot = node.nodeStats.getAllPeerSnapshots();
		transfersPerInsert = node.nodeStats.outwardTransfersPerInsert();
		peerCount = requestsSnapshot.size();
		bandwidthAvailableUpperLower = node.nodeStats.getBandwidthAvailableForPeersGuaranteed(peerCount);
		ignoreLocalVsRemoteBandwidthLiability = node.nodeStats.ignoreLocalVsRemoteBandwidthLiability();
	}
	
	public RunningRequestsSnapshot getPeerRequests(PeerNode pn) {
		return requestsSnapshot.get(pn);
	}

}
