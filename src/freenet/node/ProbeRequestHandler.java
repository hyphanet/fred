/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.support.ShortBuffer;

/**
 * Handler for probe requests.
 * @author toad
 */
public class ProbeRequestHandler implements ProbeRequestSender.Listener {

	final PeerNode source;
	final long uid;
	final ProbeRequestSender sender;
	
	ProbeRequestHandler(PeerNode source, long uid, ProbeRequestSender sender) {
		this.source = source;
		this.uid = uid;
		this.sender = sender;
	}
	
	static void start(Message m, PeerNode source, Node n, double target) {
		long uid = m.getLong(DMT.UID);
		double nearestLoc = m.getDouble(DMT.NEAREST_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		ProbeRequestSender sender =
			new ProbeRequestSender(target, htl, uid, n, nearestLoc, 
					source, best);
		ProbeRequestHandler handler = 
			new ProbeRequestHandler(source, uid, sender);
		sender.addListener(handler);
		PeerNode[] peers = n.peers.connectedPeers;
		Message accepted = DMT.createFNPAccepted(uid);
		Message trace = DMT.createFNPRHProbeTrace(uid, sender.getNearestLoc(), sender.getBest(), htl, (short)1, (short)1, n.getLocation(), n.swapIdentifier, LocationManager.extractLocs(peers, true), LocationManager.extractUIDs(peers), (short)0, (short)1, "", source.swapIdentifier);
		try {
			source.sendAsync(accepted, null, sender);
			source.sendAsync(trace, null, sender);
		} catch (NotConnectedException e) {
			// We completed(id), rather than locking it, so we don't need to unlock.
			return; // So all we need to do is not start the sender.
		}
		sender.start();
	}

	public void onCompletion(double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
		source.sendAsync(DMT.createFNPRHProbeReply(uid, nearest, best, counter, uniqueCounter, linearCounter), null, sender);
	}

	public void onRNF(short htl, double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
		Message rnf = DMT.createFNPRouteNotFound(uid, htl);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, "rnf");
		rnf.addSubMessage(sub);
		source.sendAsync(rnf, null, sender);
	}

	public void onReceivedRejectOverload(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
		Message ro = DMT.createFNPRejectedOverload(uid, false, false, false);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, reason);
		ro.addSubMessage(sub);
		source.sendAsync(ro, null, sender);
	}

	public void onTimeout(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
		Message ro = DMT.createFNPRejectedOverload(uid, true, false, false);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, reason);
		ro.addSubMessage(sub);
		source.sendAsync(ro, null, sender);
	}

	public void onTrace(long uid, double nearest, double best, short htl, short counter, short uniqueCounter, double location, long myUID, ShortBuffer peerLocs, ShortBuffer peerUIDs, short forkCount, short linearCounter, String reason, long prevUID) throws NotConnectedException {
		Message trace = DMT.createFNPRHProbeTrace(uid, nearest, best, htl, counter, uniqueCounter, location, myUID, peerLocs, peerUIDs, forkCount, linearCounter, reason, prevUID);
		source.sendAsync(trace, null, sender);
	}

}
