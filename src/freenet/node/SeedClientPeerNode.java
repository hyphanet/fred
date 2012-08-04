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

	@Override
	public PeerNodeStatus getStatus(boolean noHeavy) {
		return new PeerNodeStatus(this, noHeavy);
	}

	@Override
	public boolean isDarknet() {
		return false;
	}

	@Override
	public boolean isOpennet() {
		return false; // Not exactly
	}

	@Override
	public boolean isSeed() {
		return true;
	}

	@Override
	public boolean isRealConnection() {
		return false; // We may be connected to the same node as a seed and as a regular connection.
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		// Different to an OpennetPeerNode with the same identity!
		if(o instanceof SeedClientPeerNode) {
			return super.equals(o);
		} else return false;
	}
	
	@Override
	public void onSuccess(boolean insert, boolean ssk) {
		// Ignore
	}
	
	@Override
	public boolean isRoutingCompatible() {
		return false;
	}

	@Override
	public boolean canAcceptAnnouncements() {
		return true;
	}

	@Override
	public boolean recordStatus() {
		return false;
	}
	
	@Override
	public boolean handshakeUnknownInitiator() {
		return true;
	}

	@Override
	public int handshakeSetupType() {
		return FNPPacketMangler.SETUP_OPENNET_SEEDNODE;
	}
	
	@Override
	public boolean shouldSendHandshake() {
		return false;
	}

	@Override
	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		boolean ret = super.disconnected(true, true);
		node.peers.disconnectAndRemove(this, false, false, false);
		return ret;
	}

	@Override
	protected boolean generateIdentityFromPubkey() {
		return true;
	}

	@Override
	protected boolean ignoreLastGoodVersion() {
		return true;
	}
	
	@Override
	void startARKFetcher() {
		// Do not start an ARK fetcher.
	}
	
	@Override
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

	@Override
	protected void maybeClearPeerAddedTimeOnConnect() {
		// Do nothing
	}

	@Override
	protected boolean shouldExportPeerAddedTime() {
		return true; // For diagnostic purposes only.
	}
	
	@Override
	protected void maybeClearPeerAddedTimeOnRestart(long now) {
		// Do nothing.
	}

	@Override
	public void fatalTimeout() {
		// Disconnect.
		forceDisconnect();
	}
	
	@Override
	public boolean shallWeRouteAccordingToOurPeersLocation() {
		return false; // Irrelevant
	}
	
	@Override
	protected void onConnect() {
		OpennetManager om = node.getOpennet();
		if(om != null)
			om.seedTracker.onConnectSeed(this);
		super.onConnect();
	}

	@Override
	boolean dontKeepFullFieldSet() {
		return true;
	}


}
