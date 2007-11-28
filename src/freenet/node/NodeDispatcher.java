/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.support.Fields;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.StringArray;
import freenet.support.WeakHashSet;

/**
 * @author amphibian
 * 
 * Dispatcher for unmatched FNP messages.
 * 
 * What can we get?
 * 
 * SwapRequests
 * 
 * DataRequests
 * 
 * InsertRequests
 * 
 * Probably a few others; those are the important bits.
 */
public class NodeDispatcher implements Dispatcher {

	private static boolean logMINOR;
	final Node node;
	private NodeStats nodeStats;

	NodeDispatcher(Node node) {
		this.node = node;
		this.nodeStats = node.nodeStats;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public boolean handleMessage(Message m) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		PeerNode source = (PeerNode)m.getSource();
		if(logMINOR) Logger.minor(this, "Dispatching "+m+" from "+source);
		MessageType spec = m.getSpec();
		if(spec == DMT.FNPPing) {
			// Send an FNPPong
			Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
			try {
				m.getSource().sendAsync(reply, null, 0, null); // nothing we can do if can't contact source
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m);
			}
			return true;
		}else if(spec == DMT.FNPLinkPing) {
			long id = m.getLong(DMT.PING_SEQNO);
			Message msg = DMT.createFNPLinkPong(id);
			try {
				source.sendAsync(msg, null, 0, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
			return true;
		} else if(spec == DMT.FNPLinkPong) {
			long id = m.getLong(DMT.PING_SEQNO);
			source.receivedLinkPong(id);
			return true;
		} else if(spec == DMT.FNPDetectedIPAddress) {
			Peer p = (Peer) m.getObject(DMT.EXTERNAL_ADDRESS);
			source.setRemoteDetectedPeer(p);
			node.ipDetector.redetectAddress();
			return true;
		} else if(spec == DMT.FNPTime) {
			return handleTime(m, source);
		} else if(spec == DMT.FNPVoid) {
			return true;
		} else if(spec == DMT.FNPDisconnect) {
			handleDisconnect(m, source);
			return true;
		} else if(spec == DMT.nodeToNodeMessage) {
			node.receivedNodeToNodeMessage(m);
			return true;
		} else if(spec == DMT.UOMAnnounce) {
			return node.nodeUpdater.uom.handleAnnounce(m, source);
		} else if(spec == DMT.UOMRequestRevocation) {
			return node.nodeUpdater.uom.handleRequestRevocation(m, source);
		} else if(spec == DMT.UOMSendingRevocation) {
			return node.nodeUpdater.uom.handleSendingRevocation(m, source);
		} else if(spec == DMT.UOMRequestMain) {
			return node.nodeUpdater.uom.handleRequestMain(m, source);
		} else if(spec == DMT.UOMSendingMain) {
			return node.nodeUpdater.uom.handleSendingMain(m, source);
		}

		if(!source.isRoutable()) return false;

		if(spec == DMT.FNPLocChangeNotification) {
			double newLoc = m.getDouble(DMT.LOCATION);
			source.updateLocation(newLoc);
			return true;
		} else if(spec == DMT.FNPSwapRequest) {
			return node.lm.handleSwapRequest(m);
		} else if(spec == DMT.FNPSwapReply) {
			return node.lm.handleSwapReply(m);
		} else if(spec == DMT.FNPSwapRejected) {
			return node.lm.handleSwapRejected(m);
		} else if(spec == DMT.FNPSwapCommit) {
			return node.lm.handleSwapCommit(m);
		} else if(spec == DMT.FNPSwapComplete) {
			return node.lm.handleSwapComplete(m);
		} else if(spec == DMT.FNPCHKDataRequest) {
			return handleDataRequest(m, false);
		} else if(spec == DMT.FNPSSKDataRequest) {
			return handleDataRequest(m, true);
		} else if(spec == DMT.FNPInsertRequest) {
			return handleInsertRequest(m, false);
		} else if(spec == DMT.FNPSSKInsertRequest) {
			return handleInsertRequest(m, true);
		} else if(spec == DMT.FNPRoutedPing) {
			return handleRouted(m);
		} else if(spec == DMT.FNPRoutedPong) {
			return handleRoutedReply(m);
		} else if(spec == DMT.FNPRoutedRejected) {
			return handleRoutedRejected(m);
			// FIXME implement threaded probe requests of various kinds.
			// Old probe request code was a major pain, never really worked.
			// We should have threaded probe requests (for simple code),
			// and one for each routing strategy.
//		} else if(spec == DMT.FNPProbeRequest) {
//			return handleProbeRequest(m, source);
//		} else if(spec == DMT.FNPProbeReply) {
//			return handleProbeReply(m, source);
//		} else if(spec == DMT.FNPProbeRejected) {
//			return handleProbeRejected(m, source);
//		} else if(spec == DMT.FNPProbeTrace) {
//			return handleProbeTrace(m, source);
		}
		return false;
	}

	private void handleDisconnect(Message m, PeerNode source) {
		source.disconnected();
		// If true, remove from active routing table, likely to be down for a while.
		// Otherwise just dump all current connection state and keep trying to connect.
		boolean remove = m.getBoolean(DMT.REMOVE);
		if(remove)
			node.peers.disconnect(source, false, false);
		// If true, purge all references to this node. Otherwise, we can keep the node
		// around in secondary tables etc in order to more easily reconnect later. 
		// (Mostly used on opennet)
		boolean purge = m.getBoolean(DMT.PURGE);
		if(purge) {
			OpennetManager om = node.getOpennet();
			if(om != null)
				om.purgeOldOpennetPeer(source);
		}
		// Process parting message
		int type = m.getInt(DMT.NODE_TO_NODE_MESSAGE_TYPE);
		ShortBuffer messageData = (ShortBuffer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_DATA);
		if(messageData.getLength() == 0) return;
		node.receivedNodeToNodeMessage(source, type, messageData, true);
	}

	private boolean handleTime(Message m, PeerNode source) {
		long delta = m.getLong(DMT.TIME) - System.currentTimeMillis();
		source.setTimeDelta(delta);
		return true;
	}

	/**
	 * Handle an incoming FNPDataRequest.
	 */
	private boolean handleDataRequest(Message m, boolean isSSK) {
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting data request (loop, finished): "+e);
			}
			return true;
		}
		if(!node.lockUID(id, isSSK, false)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Locked "+id);
		}
		String rejectReason = nodeStats.shouldRejectRequest(!isSSK, false, isSSK);
		if(rejectReason != null) {
			// can accept 1 CHK request every so often, but not with SSKs because they aren't throttled so won't sort out bwlimitDelayTime, which was the whole reason for accepting them when overloaded...
			Logger.normal(this, "Rejecting request from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) data request from "+m.getSource().getPeer()+": "+e);
			}
			node.unlockUID(id, isSSK, false, false);
			return true;
		}
		//if(!node.lockUID(id)) return false;
		RequestHandler rh = new RequestHandler(m, id, node);
		node.executor.execute(rh, "RequestHandler for UID "+id);
		return true;
	}

	private boolean handleInsertRequest(Message m, boolean isSSK) {
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		}
		if(!node.lockUID(id, isSSK, true)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		}
		// SSKs don't fix bwlimitDelayTime so shouldn't be accepted when overloaded.
		String rejectReason = nodeStats.shouldRejectRequest(!isSSK, true, isSSK);
		if(rejectReason != null) {
			Logger.normal(this, "Rejecting insert from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) insert request from "+m.getSource().getPeer()+": "+e);
			}
			node.unlockUID(id, isSSK, true, false);
			return true;
		}
		long now = System.currentTimeMillis();
		if(m.getSpec().equals(DMT.FNPSSKInsertRequest)) {
			SSKInsertHandler rh = new SSKInsertHandler(m, id, node, now);
			node.executor.execute(rh, "InsertHandler for "+id+" on "+node.getDarknetPortNumber());
		} else {
			InsertHandler rh = new InsertHandler(m, id, node, now);
			node.executor.execute(rh, "InsertHandler for "+id+" on "+node.getDarknetPortNumber());
		}
		if(logMINOR) Logger.minor(this, "Started InsertHandler for "+id);
		return true;
	}

	final Hashtable routedContexts = new Hashtable();

	static class RoutedContext {
		long createdTime;
		long accessTime;
		PeerNode source;
		final HashSet routedTo;
		final HashSet notIgnored;
		Message msg;
		short lastHtl;

		RoutedContext(Message msg) {
			createdTime = accessTime = System.currentTimeMillis();
			source = (PeerNode)msg.getSource();
			routedTo = new HashSet();
			notIgnored = new HashSet();
			this.msg = msg;
			lastHtl = msg.getShort(DMT.HTL);
		}

		void addSent(PeerNode n) {
			routedTo.add(n);
		}
	}

	/**
	 * Handle an FNPRoutedRejected message.
	 */
	private boolean handleRoutedRejected(Message m) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		RoutedContext rc = (RoutedContext) routedContexts.get(lid);
		if(rc == null) {
			// Gah
			Logger.error(this, "Unrecognized FNPRoutedRejected");
			return false; // locally originated??
		}
		short htl = rc.lastHtl;
		if(rc.source != null)
			htl = rc.source.decrementHTL(htl);
		short ohtl = m.getShort(DMT.HTL);
		if(ohtl < htl) htl = ohtl;
		// Try routing to the next node
		forward(rc.msg, id, rc.source, htl, rc.msg.getDouble(DMT.TARGET_LOCATION), rc);
		return true;
	}

	/**
	 * Handle a routed-to-a-specific-node message.
	 * @param m
	 * @return False if we want the message put back on the queue.
	 */
	boolean handleRouted(Message m) {
		if(logMINOR) Logger.minor(this, "handleRouted("+m+ ')');
		if((m.getSource() != null) && (!(m.getSource() instanceof PeerNode))) {
			Logger.error(this, "Routed message but source "+m.getSource()+" not a PeerNode!");
			return true;
		}
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		PeerNode pn = (PeerNode) (m.getSource());
		short htl = m.getShort(DMT.HTL);
		if(pn != null) htl = pn.decrementHTL(htl);
		RoutedContext ctx;
		ctx = (RoutedContext)routedContexts.get(lid);
		if(ctx != null) {
			try {
				m.getSource().sendAsync(DMT.createFNPRoutedRejected(id, (short)(htl-1)), null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		}
		ctx = new RoutedContext(m);
		routedContexts.put(lid, ctx);
		// pn == null => originated locally, keep full htl
		double target = m.getDouble(DMT.TARGET_LOCATION);
		if(logMINOR) Logger.minor(this, "id "+id+" from "+pn+" htl "+htl+" target "+target);
		if(Math.abs(node.lm.getLocation() - target) <= Double.MIN_VALUE) {
			if(logMINOR) Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.getDarknetPortNumber());
			// Handle locally
			// Message type specific processing
			dispatchRoutedMessage(m, pn, id);
			return true;
		} else if(htl == 0) {
			Message reject = DMT.createFNPRoutedRejected(id, (short)0);
			if(pn != null) try {
				pn.sendAsync(reject, null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		} else {
			return forward(m, id, pn, htl, target, ctx);
		}
	}

	boolean handleRoutedReply(Message m) {
		long id = m.getLong(DMT.UID);
		if(logMINOR) Logger.minor(this, "Got reply: "+m);
		Long lid = new Long(id);
		RoutedContext ctx = (RoutedContext) routedContexts.get(lid);
		if(ctx == null) {
			Logger.error(this, "Unrecognized routed reply: "+m);
			return false;
		}
		PeerNode pn = ctx.source;
		if(pn == null) return false;
		try {
			pn.sendAsync(m, null, 0, null);
		} catch (NotConnectedException e) {
			if(logMINOR) Logger.minor(this, "Lost connection forwarding "+m+" to "+pn);
		}
		return true;
	}

	private boolean forward(Message m, long id, PeerNode pn, short htl, double target, RoutedContext ctx) {
		if(logMINOR) Logger.minor(this, "Should forward");
		// Forward
		m = preForward(m, htl);
		while(true) {
			PeerNode next = node.peers.closerPeer(pn, ctx.routedTo, ctx.notIgnored, target, true, node.isAdvancedModeEnabled(), -1, null);
			if(logMINOR) Logger.minor(this, "Next: "+next+" message: "+m);
			if(next != null) {
				// next is connected, or at least has been => next.getPeer() CANNOT be null.
				if(logMINOR) Logger.minor(this, "Forwarding "+m.getSpec()+" to "+next.getPeer().getPort());
				ctx.addSent(next);
				try {
					next.sendAsync(m, null, 0, null);
				} catch (NotConnectedException e) {
					continue;
				}
			} else {
				if(logMINOR) Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.getDarknetPortNumber());
				// Reached a dead end...
				Message reject = DMT.createFNPRoutedRejected(id, htl);
				if(pn != null) try {
					pn.sendAsync(reject, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Cannot send reject message back to source "+pn);
					return true;
				}
			}
			return true;
		}
	}

	/**
	 * Prepare a routed-to-node message for forwarding.
	 */
	private Message preForward(Message m, short newHTL) {
		m.set(DMT.HTL, newHTL); // update htl
		if(m.getSpec() == DMT.FNPRoutedPing) {
			int x = m.getInt(DMT.COUNTER);
			x++;
			m.set(DMT.COUNTER, x);
		}
		return m;
	}

	/**
	 * Deal with a routed-to-node message that landed on this node.
	 * This is where message-type-specific code executes. 
	 * @param m
	 * @return
	 */
	private boolean dispatchRoutedMessage(Message m, PeerNode src, long id) {
		if(m.getSpec() == DMT.FNPRoutedPing) {
			if(logMINOR) Logger.minor(this, "RoutedPing reached other side!");
			int x = m.getInt(DMT.COUNTER);
			Message reply = DMT.createFNPRoutedPong(id, x);
			try {
				src.sendAsync(reply, null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m+" in dispatchRoutedMessage");
			}
			return true;
		}
		return false;
	}

	void start(NodeStats stats) {
		this.nodeStats = stats;
	}

	public static String peersUIDsToString(long[] peerUIDs, double[] peerLocs) {
		StringBuffer sb = new StringBuffer(peerUIDs.length*23+peerLocs.length*26);
		int min=Math.min(peerUIDs.length, peerLocs.length);
		for(int i=0;i<min;i++) {
			double loc = peerLocs[i];
			long uid = peerUIDs[i];
			sb.append(loc);
			sb.append('=');
			sb.append(uid);
			if(i != min-1)
				sb.append('|');
		}
		if(peerUIDs.length > min) {
			for(int i=min;i<peerUIDs.length;i++) {
				sb.append("|U:");
				sb.append(peerUIDs[i]);
			}
		} else if(peerLocs.length > min) {
			for(int i=min;i<peerLocs.length;i++) {
				sb.append("|L:");
				sb.append(peerLocs[i]);
			}
		}
		return sb.toString();
	}
	
	// Probe requests

	// FIXME
	public static final int PROBE_TYPE_DEFAULT = 0;
	
	public void startProbe(double d, ProbeCallback cb, int probeType) {
		long l = node.random.nextLong();
		// FIXME implement!
		throw new UnsupportedOperationException();
	}
}