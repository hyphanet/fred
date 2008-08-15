/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

/**
 * Seed node's representation of a client node connecting in order to announce.
 * @author toad
 */
public class SeedClientPeerNode extends PeerNode {

	public SeedClientPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, boolean noSig, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, noSig, mangler, true);
	}

	public PeerNodeStatus getStatus(boolean noHeavy) {
		return new PeerNodeStatus(this, noHeavy);
	}

	public boolean isDarknet() {
		return false;
	}

	public boolean isOpennet() {
		return false; // Not exactly
	}

	public boolean isRealConnection() {
		return false; // We may be connected to the same node as a seed and as a regular connection.
	}

	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		// Different to an OpennetPeerNode with the same identity!
		if(o instanceof SeedClientPeerNode) {
			return super.equals(o);
		} else return false;
	}
	
	public void onSuccess(boolean insert, boolean ssk) {
		// Ignore
	}
	
	public boolean isRoutingCompatible() {
		return false;
	}

	public boolean canAcceptAnnouncements() {
		return true;
	}

	public boolean recordStatus() {
		return false;
	}
	
	public boolean handshakeUnknownInitiator() {
		return true;
	}

	public int handshakeSetupType() {
		return FNPPacketMangler.SETUP_OPENNET_SEEDNODE;
	}
	
	public boolean shouldSendHandshake() {
		return false;
	}

	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		boolean ret = super.disconnected(dumpMessageQueue, dumpTrackers);
		node.peers.disconnect(this, false, false);
		return ret;
	}

	protected boolean generateIdentityFromPubkey() {
		return true;
	}

	protected boolean ignoreLastGoodVersion() {
		return true;
	}
	
	void startARKFetcher() {
		// Do not start an ARK fetcher.
	}
	
	public boolean shouldDisconnectAndRemoveNow() {
		if(!isConnected()) {
			// SeedClientPeerNode's always start off unverified.
			// If it doesn't manage to connect in 60 seconds, dump it.
			// However, we don't want to be dumped *before* we connect,
			// so we need to check that first.
			// Synchronize to avoid messy races.
			synchronized(this) {
				if(timeLastConnectionCompleted() > 0 &&
						System.currentTimeMillis() - lastReceivedPacketTime() > 60*1000)
				return true;
			}
		} else {
			// Disconnect after an hour in any event.
			if(System.currentTimeMillis() - timeLastConnectionCompleted() > 60*60*1000)
				return true;
		}
		return false;
	}
}
