/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

/**
 *
 * @author nextgens
 */
public class SeedServerTestPeerNode extends SeedServerPeerNode {

	public SeedServerTestPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, boolean fromLocal) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {
		super(fs, node2, crypto, fromLocal);
	}
	
	@Override
	public SimpleFieldSet exportFieldSet() {
		SimpleFieldSet sfs = super.exportFieldSet();
		sfs.putOverwrite("opennet", "true");
		return sfs;
	}

	@Override
	public boolean shouldDisconnectAndRemoveNow() {
		return false;
	}
	
	@Override
	protected void sendInitialMessages() {}
	
	public enum FATE {
		// Never connected
		NEVER_CONNECTED,
		// Connected but no packets received yet
		CONNECTED_NO_PACKETS_RECEIVED,
		// Connected but TOO_OLD
		CONNECTED_TOO_OLD,
		// Connected and received packets
		CONNECTED_SUCCESS,
		// Connected but timed out after no packets received
		CONNECTED_TIMEOUT_NO_PACKETS_RECEIVED,
		// Connected but then disconnected for no known reason
		CONNECTED_DISCONNECTED_UNKNOWN
	}
	
	@Override
	public void onRemove() {
		long lastReceivedDataPacketTime = lastReceivedDataPacketTime();
		if(lastReceivedDataPacketTime <= 0 && timeLastConnectionCompleted() > 0)
			System.err.println(this.getIdentityString()+" : REMOVED: TIMEOUT: NO PACKETS RECEIVED AFTER SUCCESSFUL CONNECTION SETUP");
		else if(timeLastConnectionCompleted() <= 0)
			System.err.println(this.getIdentityString()+" : REMOVED: NEVER CONNECTED");
		else
			System.err.println(this.getIdentityString()+" : REMOVED: UNKNOWN CAUSE");
		super.onRemove();
	}
	
	public FATE getFate() {
		long lastReceivedDataPacketTime = lastReceivedDataPacketTime();
		if(isConnected()) {
			if(lastReceivedDataPacketTime <= 0)
				return FATE.CONNECTED_NO_PACKETS_RECEIVED;
			else if(this.isUnroutableOlderVersion())
				return FATE.CONNECTED_TOO_OLD;
			else
				return FATE.CONNECTED_SUCCESS;
		}
		long lastConnectionTime = timeLastConnectionCompleted();
		if(lastConnectionTime <= 0)
			return FATE.NEVER_CONNECTED;
		if(lastReceivedDataPacketTime <= 0)
			return FATE.CONNECTED_TIMEOUT_NO_PACKETS_RECEIVED;
		return FATE.CONNECTED_DISCONNECTED_UNKNOWN;
	}
}
