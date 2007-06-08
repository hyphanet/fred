/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

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
 * 
 * Note that if in response to these we have to send a packet,
 * IT MUST BE DONE OFF-THREAD. Because sends will normally block.
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
		} else if(spec == DMT.FNPVoid) {
			return true;
		} else if(spec == DMT.nodeToNodeMessage) {
			node.receivedNodeToNodeMessage(m);
			return true;
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
		} else if(spec == DMT.FNPProbeRequest) {
			return handleProbeRequest(m, source);
		} else if(spec == DMT.FNPProbeReply) {
			return handleProbeReply(m, source);
		} else if(spec == DMT.FNPProbeRejected) {
			return handleProbeRejected(m, source);
		} else if(spec == DMT.FNPProbeTrace) {
			return handleProbeTrace(m, source);
		}
		return false;
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
			node.unlockUID(id, isSSK, false);
			return true;
		}
		//if(!node.lockUID(id)) return false;
		RequestHandler rh = new RequestHandler(m, id, node);
		Thread t = new Thread(rh, "RequestHandler for UID "+id);
		t.setDaemon(true);
		t.start();
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
			node.unlockUID(id, isSSK, true);
			return true;
		}
		long now = System.currentTimeMillis();
		if(m.getSpec().equals(DMT.FNPSSKInsertRequest)) {
			SSKInsertHandler rh = new SSKInsertHandler(m, id, node, now);
			Thread t = new Thread(rh, "InsertHandler for "+id+" on "+node.portNumber);
			t.setDaemon(true);
			t.start();
		} else {
			InsertHandler rh = new InsertHandler(m, id, node, now);
			Thread t = new Thread(rh, "InsertHandler for "+id+" on "+node.portNumber);
			t.setDaemon(true);
			t.start();
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
		if(Math.abs(node.lm.getLocation().getValue() - target) <= Double.MIN_VALUE) {
			if(logMINOR) Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.portNumber);
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
				if(logMINOR) Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.portNumber);
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

	// Probe request handling

	long tLastReceivedProbeRequest;

	static final int MAX_PROBE_CONTEXTS = 1000;
	static final int MAX_PROBE_IDS = 10000;

	class ProbeContext {

		final PeerNode src; // FIXME make this a weak reference or something ? - Memory leak with high connection churn
		final HashSet visitedPeers;
		final ProbeCallback cb;
		short counter;
		short htl;
		double nearest;
		double best;
		Vector notVisitedList; // List of best locations not yet visited by this request

		public ProbeContext(long id, double target, double best, double nearest, short htl, short counter, PeerNode src, ProbeCallback cb) {
			visitedPeers = new HashSet();
			this.counter = counter;
			this.htl = htl;
			this.nearest = nearest;
			this.best = best;
			this.src = src;
			this.cb = cb;
		}

	}

	final LRUQueue recentProbeRequestIDs = new LRUQueue();
	final LRUHashtable recentProbeContexts = new LRUHashtable();

	/** 
	 * Handle a probe request.
	 * Reject it if it's looped.
	 * Look up (and promote) its context object.
	 * Update its HTL, nearest-seen and best-seen.
	 * Complete it if it has run out of HTL.
	 * Otherwise forward it.
	 **/
	private boolean handleProbeRequest(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		short counter = m.getShort(DMT.COUNTER);
		if(logMINOR)
			Logger.minor(this, "Probe request: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+ ' ' +htl+ ' ' +counter);
		synchronized(recentProbeContexts) {
			if(recentProbeRequestIDs.contains(lid)) {
				// Reject: Loop
				Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_LOOP);
				try {
					src.sendAsync(reject, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Not connected rejecting a Probe request from "+src);
				}
				return true;
			} else
				Logger.minor(this, "Probe request "+id+" not already present");
			recentProbeRequestIDs.push(lid);
			while(recentProbeRequestIDs.size() > MAX_PROBE_IDS) {
				Object o = recentProbeRequestIDs.pop();
				Logger.minor(this, "Probe request popped "+o);
			}
		}
		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		Vector notVisitedList = new Vector();
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)m.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
			for(int i=0;i<locsNotVisited.length;i++)
				notVisitedList.add(new Double(locsNotVisited[i]));
		}
		return innerHandleProbeRequest(src, id, lid, target, best, nearest, htl, counter, true, true, false, null, notVisitedList);
	}

	final int MAX_LOCS_NOT_VISITED = 3;
	
	/**
	 * 
	 * @param src
	 * @param id
	 * @param lid
	 * @param target
	 * @param best
	 * @param nearest Best-so-far for normal routing purposes.
	 * @param htl
	 * @param counter
	 * @param checkRecent
	 * @param canReject
	 * @param cb
	 * @param locsNotVisited 
	 * @return
	 */
	private boolean innerHandleProbeRequest(PeerNode src, long id, Long lid, final double target, double best, 
			double nearest, short htl, short counter, boolean checkRecent, boolean canReject, 
			boolean fromRejection, ProbeCallback cb, Vector locsNotVisited) {
		short max = node.maxHTL();
		if(htl > max) htl = max;
		if(htl <= 1) htl = 1;
		ProbeContext ctx = null;
		boolean rejected = false;
		boolean isNew = false;
		synchronized(recentProbeContexts) {
			if(checkRecent) {
				long now = System.currentTimeMillis();
				if(now - tLastReceivedProbeRequest < 500) {
					rejected = true;
				} else {
					tLastReceivedProbeRequest = now;
					counter++; // Accepted it; another hop
				}
			}
			if(!rejected) {
				ctx = (ProbeContext) recentProbeContexts.get(lid);
				if(ctx == null) {
					ctx = new ProbeContext(id, target, best, nearest, htl, counter, src, cb);
					isNew = true;
				}
				recentProbeContexts.push(lid, ctx); // promote or add
				while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
					recentProbeContexts.popValue();
			}
		}
		if(locsNotVisited != null) {
			if(logMINOR)
				Logger.minor(this, "Locs not visited: "+locsNotVisited);
		}
		
		// Add source
		if(src != null) ctx.visitedPeers.add(src);
		if(rejected) {
			// Reject: rate limit
			Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_OVERLOAD);
			try {
				src.sendAsync(reject, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.error(this, "Not connected rejecting a probe request from "+src);
			}
			return true;
		}
		if(ctx.counter < counter) ctx.counter = counter;
		if(logMINOR)
			Logger.minor(this, "ctx.nearest="+ctx.nearest+", nearest="+nearest+", target="+target+", htl="+htl+", ctx.htl="+ctx.htl);
		if(ctx.htl > htl) {
			// Rejected can reduce HTL
			ctx.htl = htl;
		}
		Logger.minor(this, "htl="+htl+", nearest="+nearest+", ctx.htl="+ctx.htl+", ctx.nearest="+ctx.nearest);

		PeerNode[] peers = node.peers.connectedPeers;

		double myLoc = node.getLocation();
		for(int i=0;i<locsNotVisited.size();i++) {
			double loc = ((Double) locsNotVisited.get(i)).doubleValue();
			if(Math.abs(loc - myLoc) < Double.MIN_VALUE * 2) {
				locsNotVisited.remove(i);
				break;
			}
		}
		// Update best

		if(myLoc > target && myLoc < best)
			best = myLoc;

		if(ctx.best > target && ctx.best < best)
			best = ctx.best;

		for(int i=0;i<peers.length;i++) {
			double loc = peers[i].getLocation().getValue();
			if(logMINOR) Logger.minor(this, "Location: "+loc);
			// We are only interested in locations greater than the target
			if(loc <= (target + 2*Double.MIN_VALUE)) {
				if(logMINOR) Logger.minor(this, "Location is under target");
				continue;
			}
			if(loc < best) {
				if(logMINOR) Logger.minor(this, "New best: "+loc+" was "+best);
				best = loc;
			}
		}

		// Update nearest, htl

		if(PeerManager.distance(myLoc, target) < PeerManager.distance(nearest, target)) {
			if(logMINOR)
				Logger.minor(this, "Updating nearest to "+myLoc+" from "+nearest+" for "+target+" and resetting htl from "+htl+" to "+max);
			nearest = myLoc;
			htl = max;
			ctx.nearest = nearest;
			ctx.htl = htl;
		} else {
			htl = node.decrementHTL(src, htl);
			ctx.htl = htl;
			if(logMINOR)
				Logger.minor(this, "Updated htl to "+htl+" - myLoc="+myLoc+", target="+target+", nearest="+nearest);
		}

		// Complete ?
		if(htl == 0) {
			if(src != null) {
				// Complete
				Message complete = DMT.createFNPProbeReply(id, target, nearest, best, counter++);
				Message sub = DMT.createFNPBestRoutesNotTaken((Double[])locsNotVisited.toArray(new Double[locsNotVisited.size()]));
				complete.addSubMessage(sub);
				try {
					src.sendAsync(complete, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Not connected completing a probe request from "+src);
				}
				return true;
			} else {
				complete("success", target, best, nearest, id, ctx, counter);
			}
		}

		// Otherwise route it

		HashSet visited = ctx.visitedPeers;

		while(true) {

			Vector newBestLocs = new Vector();
			newBestLocs.addAll(locsNotVisited);
			PeerNode pn = node.peers.closerPeer(src, visited, null, target, true, false, 965, newBestLocs);
			
			Double[] locs = (Double[]) newBestLocs.toArray(new Double[newBestLocs.size()]);
			Arrays.sort(locs, new Comparator() {
				public int compare(Object arg0, Object arg1) {
					double d0 = ((Double) arg0).doubleValue();
					double d1 = ((Double) arg1).doubleValue();
					double dist0 = PeerManager.distance(d0, target);
					double dist1 = PeerManager.distance(d1, target);
					if(dist0 < dist1) return -1; // best at the beginning
					if(dist0 > dist1) return 1;
					return 0; // should not happen
				}
			});
			locsNotVisited.clear();
			for(int i=0;i<Math.min(MAX_LOCS_NOT_VISITED, locs.length);i++)
				locsNotVisited.add(locs[i]);
			
			Message sub = DMT.createFNPBestRoutesNotTaken((Double[])locsNotVisited.toArray(new Double[locsNotVisited.size()]));
			
			ctx.notVisitedList = locsNotVisited;

			if(pn == null) {
				// Can't complete, because some HTL left
				// Reject: RNF
				if(canReject) {
					Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_RNF);
					reject.addSubMessage(sub);
					try {
						src.sendAsync(reject, null, 0, null);
					} catch (NotConnectedException e) {
						Logger.error(this, "Not connected rejecting a probe request from "+src);
					}
				} else {
					complete("RNF", target, best, nearest, id, ctx, counter);
				}
				return true;
			}

			visited.add(pn);

			if(src != null) {
				Message trace =
					DMT.createFNPProbeTrace(id, target, nearest, best, htl, counter, myLoc, node.swapIdentifier, LocationManager.extractLocs(peers, true), LocationManager.extractUIDs(peers));
				trace.addSubMessage(sub);
				try {
					src.sendAsync(trace, null, 0, null);
				} catch (NotConnectedException e1) {
					// Ignore
				}
			}
			
			Message forwarded =
				DMT.createFNPProbeRequest(id, target, nearest, best, htl, counter++);
			forwarded.addSubMessage(sub);
			try {
				pn.sendAsync(forwarded, null, 0, null);
				return true;
			} catch (NotConnectedException e) {
				Logger.error(this, "Could not forward message: disconnected: "+pn+" : "+e, e);
				// Try another one
			}
		}

	}

	private void complete(String msg, double target, double best, double nearest, long id, ProbeContext ctx, short counter) {
		Logger.normal(this, "Completed Probe request # "+id+" - RNF - "+msg+": "+best);
		ctx.cb.onCompleted(msg, target, best, nearest, id, counter);
	}

	private void reportTrace(ProbeContext ctx, Message msg) {
		long uid = msg.getLong(DMT.UID);
		double target = msg.getDouble(DMT.TARGET_LOCATION);
		double nearest = msg.getDouble(DMT.NEAREST_LOCATION);
		double best = msg.getDouble(DMT.BEST_LOCATION);
		short htl = msg.getShort(DMT.HTL);
		short counter = msg.getShort(DMT.COUNTER);
		double location = msg.getDouble(DMT.LOCATION);
		long nodeUID = msg.getLong(DMT.MY_UID);
		double[] peerLocs = Fields.bytesToDoubles(((ShortBuffer)msg.getObject(DMT.PEER_LOCATIONS)).getData());
		long[] peerUIDs = Fields.bytesToLongs(((ShortBuffer)msg.getObject(DMT.PEER_UIDS)).getData());
		ctx.cb.onTrace(uid, target, nearest, best, htl, counter, location, nodeUID, peerLocs, peerUIDs);
	}

	private boolean handleProbeReply(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short counter = m.getShort(DMT.COUNTER);
		if(logMINOR)
			Logger.minor(this, "Probe reply: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest);
		// Just propagate back to source
		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Could not forward probe reply back to source for ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote or add
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}

		if(ctx.src != null) {
			Message complete = DMT.createFNPProbeReply(id, target, nearest, best, counter++);
			Message sub = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
			if(sub != null) complete.addSubMessage(sub);
			try {
				ctx.src.sendAsync(complete, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.error(this, "Not connected completing a probe request from "+ctx.src+" (forwarding completion from "+src+ ')');
			}
		} else {
			if(ctx.cb != null)
				complete("Completed", target, best, nearest, id, ctx, counter);
		}
		return true;
	}

	private boolean handleProbeTrace(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short counter = m.getShort(DMT.COUNTER);
		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)m.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
		}
		if(logMINOR)
			Logger.minor(this, "Probe trace: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+' '+counter);
		if(locsNotVisited != null) {
			if(logMINOR)
				Logger.minor(this, "Locs not visited: "+StringArray.toString(locsNotVisited));
		}
		// Just propagate back to source
		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Could not forward probe reply back to source for ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote or add
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}

		if(ctx.src != null) {
			try {
				ctx.src.sendAsync(m, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.error(this, "Not connected forwarding trace to "+ctx.src+" (from "+src+ ')');
			}
		} else {
			if(ctx.cb != null)
				reportTrace(ctx, m);
		}
		return true;
	}

	private boolean handleProbeRejected(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		short counter = m.getShort(DMT.COUNTER);
		short reason = m.getShort(DMT.REASON);
		if(logMINOR)
			Logger.minor(this, "Probe rejected: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+ ' ' +htl+ ' ' +counter+ ' ' +reason);

		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Unknown rejected probe request ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote or add
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}

		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		Vector notVisitedList = new Vector();
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)m.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
			for(int i=0;i<locsNotVisited.length;i++)
				notVisitedList.add(new Double(locsNotVisited[i]));
		}
		return innerHandleProbeRequest(src, id, lid, target, best, nearest, htl, counter, false, false, true, null, notVisitedList);
	}

	public void startProbe(double d, ProbeCallback cb) {
		long l = node.random.nextLong();
		Long ll = new Long(l);
		synchronized(recentProbeRequestIDs) {
			recentProbeRequestIDs.push(ll);
		}
		double nodeLoc = node.getLocation();
		innerHandleProbeRequest(null, l, ll, d, (nodeLoc > d) ? nodeLoc : 1.0, nodeLoc, node.maxHTL(), (short)0, false, false, false, cb, new Vector());
	}
	
	void start(NodeStats stats) {
		this.nodeStats = stats;
	}
}