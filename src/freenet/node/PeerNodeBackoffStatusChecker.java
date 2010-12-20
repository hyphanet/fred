package freenet.node;

import java.lang.ref.WeakReference;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

class PeerNodeBackoffStatusChecker implements Runnable {
	final WeakReference<PeerNode> ref;
	
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public PeerNodeBackoffStatusChecker(WeakReference<PeerNode> ref) {
		this.ref = ref;
	}

	public void run() {
		PeerNode pn = ref.get();
		if(pn == null) return;
		if(pn.cachedRemoved()) {
			if(logMINOR && pn.node.peers.havePeer(pn)) {
				Logger.error(this, "Removed flag is set yet is in peers table?!: "+pn);
			} else {
				return;
			}
		}
		if(!pn.node.peers.havePeer(pn)) {
			if(!pn.cachedRemoved())
				Logger.error(this, "Not in peers table but not flagged as removed: "+pn);
			return;
		}
		pn.setPeerNodeStatus(System.currentTimeMillis(), true);
	}
}
