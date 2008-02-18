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
 * Uses the resetting-HTL algorithm used by Freenet 0.7 for a long while invented by me and
 * ian.
 * @author toad
 */
public class ResettingHTLProbeRequestHandler implements ResettingHTLProbeRequestSender.Listener {

	final PeerNode source;
	final long uid;
	final ResettingHTLProbeRequestSender sender;
	
	ResettingHTLProbeRequestHandler(PeerNode source, long uid, ResettingHTLProbeRequestSender sender) {
		this.source = source;
		this.uid = uid;
		this.sender = sender;
	}
	
	static void start(Message m, PeerNode source, Node n, double target) {
		long uid = m.getLong(DMT.UID);
		double nearestLoc = m.getDouble(DMT.NEAREST_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		if(htl > n.maxHTL()) htl = n.maxHTL();
		double nodeLoc = n.getLocation();
		boolean resetNearestLoc = false;
		if(Location.distance(nodeLoc, target, true) <
				Location.distance(nearestLoc, target, true)) {
			nearestLoc = nodeLoc;
			htl = Node.DEFAULT_MAX_HTL;
			resetNearestLoc = true;
		}
		ResettingHTLProbeRequestSender sender =
			new ResettingHTLProbeRequestSender(target, htl, uid, n, nearestLoc, resetNearestLoc,
					source, best);
		ResettingHTLProbeRequestHandler handler = 
			new ResettingHTLProbeRequestHandler(source, uid, sender);
		sender.addListener(handler);
		PeerNode[] peers = n.peers.connectedPeers;
		Message accepted = DMT.createFNPAccepted(uid);
		Message trace = DMT.createFNPRHProbeTrace(uid, sender.getNearestLoc(), sender.getBest(), htl, (short)1, (short)1, n.getLocation(), n.swapIdentifier, LocationManager.extractLocs(peers, true), LocationManager.extractUIDs(peers), (short)0, (short)1, "", source.swapIdentifier);
		try {
			source.sendAsync(accepted, null, 0, sender);
			source.sendAsync(trace, null, 0, sender);
		} catch (NotConnectedException e) {
			// Ignore, sender will pick up
		}
		sender.start();
	}

	public void onCompletion(double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
		source.sendAsync(DMT.createFNPRHProbeReply(uid, nearest, best, counter, uniqueCounter, linearCounter), null, 0, sender);
	}

	public void onRNF(short htl, double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
		Message rnf = DMT.createFNPRouteNotFound(uid, htl);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, "rnf");
		rnf.addSubMessage(sub);
		source.sendAsync(rnf, null, 0, sender);
	}

	public void onReceivedRejectOverload(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
		Message ro = DMT.createFNPRejectedOverload(uid, false);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, reason);
		ro.addSubMessage(sub);
		source.sendAsync(ro, null, 0, sender);
	}

	public void onTimeout(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
		Message ro = DMT.createFNPRejectedOverload(uid, true);
		Message sub = DMT.createFNPRHReturnSubMessage(nearest, best, counter, uniqueCounter, linearCounter, reason);
		ro.addSubMessage(sub);
		source.sendAsync(ro, null, 0, sender);
	}

	public void onTrace(long uid, double nearest, double best, short htl, short counter, short uniqueCounter, double location, long myUID, ShortBuffer peerLocs, ShortBuffer peerUIDs, short forkCount, short linearCounter, String reason, long prevUID) throws NotConnectedException {
		Message trace = DMT.createFNPRHProbeTrace(uid, nearest, best, htl, counter, uniqueCounter, location, myUID, peerLocs, peerUIDs, forkCount, linearCounter, reason, prevUID);
		source.sendAsync(trace, null, 0, sender);
	}

}
