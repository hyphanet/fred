package freenet.node;

import java.util.HashMap;
import java.util.HashSet;

import freenet.support.Logger;

/** Track a collection of PeerNode's for each status. */
class PeerStatusTracker {
	
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(PeerManager.class);
    }

	/** PeerNode statuses, by status. WARNING: LOCK THIS LAST. Must NOT call PeerNode inside this lock. */
	private final HashMap<Integer, HashSet<PeerNode>> statuses;

	PeerStatusTracker() {
		statuses = new HashMap<Integer, HashSet<PeerNode>>();
	}

	public synchronized void addStatus(Integer peerNodeStatus, PeerNode peerNode, boolean noLog) {
		HashSet<PeerNode> statusSet = null;
		if(statuses.containsKey(peerNodeStatus)) {
			statusSet = statuses.get(peerNodeStatus);
			if(statusSet.contains(peerNode)) {
				if(!noLog)
					Logger.error(this, "addPeerNodeStatus(): node already in peerNodeStatuses: " + peerNode + " status " + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()), new Exception("debug"));
				return;
			}
			statuses.remove(peerNodeStatus);
		} else
			statusSet = new HashSet<PeerNode>();
		if(logMINOR)
			Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
		statusSet.add(peerNode);
		statuses.put(peerNodeStatus, statusSet);
	}

	public int statusSize(int pnStatus) {
		Integer peerNodeStatus = Integer.valueOf(pnStatus);
		HashSet<PeerNode> statusSet = null;
		synchronized(statuses) {
			if(statuses.containsKey(peerNodeStatus))
				statusSet = statuses.get(peerNodeStatus);
			else
				statusSet = new HashSet<PeerNode>();
			return statusSet.size();
		}
	}

	public void removeStatus(Integer peerNodeStatus, PeerNode peerNode,
			boolean noLog) {
		HashSet<PeerNode> statusSet = null;
		synchronized(statuses) {
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
				statusSet = new HashSet<PeerNode>();
			if(logMINOR)
				Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
			if(statusSet.contains(peerNode))
				statusSet.remove(peerNode);
		}
	}
}
