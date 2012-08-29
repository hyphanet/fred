package freenet.node;

import java.util.HashMap;

import freenet.support.Logger;
import freenet.support.WeakHashSet;

/** Track a collection of PeerNode's for each status. */
class PeerStatusTracker {
	
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(PeerManager.class);
    }

	/** PeerNode statuses, by status. WARNING: LOCK THIS LAST. Must NOT call PeerNode inside this lock. */
	private final HashMap<Integer, WeakHashSet<PeerNode>> statuses;

	PeerStatusTracker() {
		statuses = new HashMap<Integer, WeakHashSet<PeerNode>>();
	}

	public synchronized void addStatus(Integer peerNodeStatus, PeerNode peerNode, boolean noLog) {
		WeakHashSet<PeerNode> statusSet = null;
		if(statuses.containsKey(peerNodeStatus)) {
			statusSet = statuses.get(peerNodeStatus);
			if(statusSet.contains(peerNode)) {
				if(!noLog)
					Logger.error(this, "addPeerNodeStatus(): node already in peerNodeStatuses: " + peerNode + " status " + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()), new Exception("debug"));
				return;
			}
			statuses.remove(peerNodeStatus);
		} else
			statusSet = new WeakHashSet<PeerNode>();
		if(logMINOR)
			Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
		statusSet.add(peerNode);
		statuses.put(peerNodeStatus, statusSet);
	}

	public synchronized int statusSize(int pnStatus) {
		Integer peerNodeStatus = Integer.valueOf(pnStatus);
		WeakHashSet<PeerNode> statusSet = null;
		if(statuses.containsKey(peerNodeStatus))
			statusSet = statuses.get(peerNodeStatus);
		else
			statusSet = new WeakHashSet<PeerNode>();
		return statusSet.size();
	}

	public synchronized void removeStatus(Integer peerNodeStatus, PeerNode peerNode,
			boolean noLog) {
		WeakHashSet<PeerNode> statusSet = null;
		if(statuses.containsKey(peerNodeStatus)) {
			statusSet = statuses.get(peerNodeStatus);
			if(!statusSet.contains(peerNode)) {
				if(!noLog)
					Logger.error(this, "removePeerNodeStatus(): identity '" + peerNode.getIdentityString() + " for " + peerNode.shortToString() + "' not in peerNodeStatuses with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'", new Exception("debug"));
				return;
			}
			if(statuses.isEmpty())
				statuses.remove(peerNodeStatus);
		} else
			statusSet = new WeakHashSet<PeerNode>();
		if(logMINOR)
			Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
		if(statusSet.contains(peerNode))
			statusSet.remove(peerNode);
	}
	
	public synchronized void changePeerNodeStatus(PeerNode peerNode, int oldPeerNodeStatus,
			int peerNodeStatus, boolean noLog) {
		if(logMINOR) Logger.minor(this, "Peer status change: "+oldPeerNodeStatus+" -> "+peerNodeStatus+" on "+peerNode);
		removeStatus(oldPeerNodeStatus, peerNode, noLog);
		addStatus(peerNodeStatus, peerNode, noLog);
	}

}
