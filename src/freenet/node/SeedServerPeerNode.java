/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.net.InetAddress;
import java.util.ArrayList;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Sender's representation of a seed node.
 * @author toad
 */
public class SeedServerPeerNode extends PeerNode {

	public SeedServerPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, false, mangler, true);
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
		return false;
	}

	@Override
	public boolean isSeed() {
		return true;
	}

	@Override
	public boolean isRealConnection() {
		return false;
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		// Different to an OpennetPeerNode with the same identity!
		if(o instanceof SeedServerPeerNode) {
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
	public boolean recordStatus() {
		return false;
	}

	@Override
	protected void sendInitialMessages() {
		super.sendInitialMessages();
		final OpennetManager om = node.getOpennet();
		if(om == null) {
			Logger.normal(this, "Opennet turned off while connecting to seednodes");
			node.peers.disconnectAndRemove(this, true, true, true);
		} else {
			// Wait 5 seconds. Another node may connect first, we don't want all the
			// announcements to go to the node which we connect to most quickly.
			node.getTicker().queueTimedJob(new Runnable() {
				@Override
				public void run() {
					try {
						om.announcer.maybeSendAnnouncement();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
			}, 5*1000);
		}
	}

	public InetAddress[] getInetAddresses() {
		ArrayList<InetAddress> v = new ArrayList<InetAddress>();
		for(Peer peer: getHandshakeIPs()) {
			FreenetInetAddress fa = peer.getFreenetAddress().dropHostname();
			if(fa == null) continue;
			InetAddress ia = fa.getAddress();
			if(v.contains(ia)) continue;
			v.add(ia);
		}
		if(v.isEmpty()) {
			Logger.error(this, "No valid addresses for seed node "+this);
		}
		return v.toArray(new InetAddress[v.size()]);
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
	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		boolean ret = super.disconnected(dumpMessageQueue, dumpTrackers);
		node.peers.disconnectAndRemove(this, false, false, false);
		return ret;
	}

	@Override
	protected boolean generateIdentityFromPubkey() {
		return false;
	}
	
	@Override
	public boolean shouldDisconnectAndRemoveNow() {
		OpennetManager om = node.getOpennet();
		if(om == null) return true;
		if(!om.announcer.enoughPeers()) return false;
		// We have enough peers, but we might fluctuate a bit.
		// Drop the connection once we have consistently had enough opennet peers for 5 minutes.
		return System.currentTimeMillis() - om.announcer.timeGotEnoughPeers() > 5*60*1000;
	}

	@Override
	protected void maybeClearPeerAddedTimeOnConnect() {
		// Do nothing.
	}

	@Override
	protected boolean shouldExportPeerAddedTime() {
		// For diagnostic purposes only.
		return true;
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
	boolean dontKeepFullFieldSet() {
		return false;
	}

}
