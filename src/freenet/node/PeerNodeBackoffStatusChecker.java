package freenet.node;

import java.lang.ref.WeakReference;

class PeerNodeBackoffStatusChecker implements Runnable {
	final WeakReference<PeerNode> ref;
	
	public PeerNodeBackoffStatusChecker(WeakReference<PeerNode> ref) {
		this.ref = ref;
	}

	public void run() {
		PeerNode pn = ref.get();
		if(pn == null) return;
		if(!pn.node.peers.havePeer(pn)) return;
		pn.setPeerNodeStatus(System.currentTimeMillis(), true);
	}
}