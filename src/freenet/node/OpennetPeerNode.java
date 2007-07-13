package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

public class OpennetPeerNode extends PeerNode {

	final OpennetManager opennet;
	private long timeLastSuccess;
	
    /** When did we last disconnect? Not Disconnected because a discrete event */
    private long timeLastDisconnect;
    /** Previous time of disconnection */
    private long timePrevDisconnect;
    
	public OpennetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, mangler, true);
		this.opennet = opennet;
	}

	public PeerNodeStatus getStatus() {
		return new OpennetPeerNodeStatus(this);
	}

	public boolean isRoutingCompatible() {
		if(!node.isOpennetEnabled()) return false;
		return super.isRoutingCompatible();
	}

	public boolean isOpennet() {
		return true;
	}
	
	public boolean isDroppable() {
		long now = System.currentTimeMillis();
		if(now - getPeerAddedTime() < OpennetManager.DROP_MIN_AGE)
			return false; // New node
		if(now - node.usm.getStartedTime() < OpennetManager.DROP_STARTUP_DELAY)
			return false; // Give them time to connect after we startup
		synchronized(this) {
			if((!isConnected()) && (!super.neverConnected()) && 
					now - timeLastDisconnect < OpennetManager.DROP_DISCONNECT_DELAY &&
					now - timePrevDisconnect > OpennetManager.DROP_DISCONNECT_DELAY_COOLDOWN) {
				// Grace period for node restarting
				return false;
			}
		}
		return true;
	}
	
	public void onSuccess(boolean insert, boolean ssk) {
		if(insert || ssk) return;
		timeLastSuccess = System.currentTimeMillis();
		opennet.onSuccess(this);
	}

	public void onRemove() {
		opennet.onRemove(this);
	}
	
    public synchronized SimpleFieldSet exportMetadataFieldSet() {
    	SimpleFieldSet fs = super.exportMetadataFieldSet();
    	fs.put("timeLastSuccess", timeLastSuccess);
    	return fs;
    }

    public final long timeLastSuccess() {
    	return timeLastSuccess;
    }
    
    public void disconnected() {
    	synchronized(this) {
    		timePrevDisconnect = timeLastDisconnect;
			timeLastDisconnect = System.currentTimeMillis();
    	}
    	super.disconnected();
    }
    
    public synchronized long timeLastDisconnect() {
    	return timeLastDisconnect;
    }
    
}
