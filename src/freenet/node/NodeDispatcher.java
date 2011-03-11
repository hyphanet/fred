/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;

import freenet.crypt.HMAC;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.NodeStats.PeerLoadStats;
import freenet.node.NodeStats.RejectReason;
import freenet.store.BlockMetadata;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

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
public class NodeDispatcher implements Dispatcher, Runnable {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	final Node node;
	private NodeStats nodeStats;
	private NodeDispatcherCallback callback;
	
	private static final long STALE_CONTEXT=20000;
	private static final long STALE_CONTEXT_CHECK=20000;

	NodeDispatcher(Node node) {
		this.node = node;
		this.nodeStats = node.nodeStats;
		node.getTicker().queueTimedJob(this, STALE_CONTEXT_CHECK);
	}

	ByteCounter pingCounter = new ByteCounter() {

		public void receivedBytes(int x) {
			node.nodeStats.pingCounterReceived(x);
		}

		public void sentBytes(int x) {
			node.nodeStats.pingCounterSent(x);
		}

		public void sentPayload(int x) {
			// Ignore
		}
		
	};
	
	public interface NodeDispatcherCallback {
		public void snoop(Message m, Node n);
	}
	
	public boolean handleMessage(Message m) {
		PeerNode source = (PeerNode)m.getSource();
		if(source == null) {
			// Node has been disconnected and garbage collected already! Ouch.
			return true;
		}
		if(logMINOR) Logger.minor(this, "Dispatching "+m+" from "+source);
		if(callback != null) {
			try {
				callback.snoop(m, node);
			} catch (Throwable t) {
				Logger.error(this, "Callback threw "+t, t);
			}
		}
		MessageType spec = m.getSpec();
		if(spec == DMT.FNPPing) {
			// Send an FNPPong
			Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
			try {
				source.sendAsync(reply, null, pingCounter); // nothing we can do if can't contact source
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m);
			}
			return true;
		} else if (spec == DMT.FNPStoreSecret) {
			return node.netid.handleStoreSecret(m);
		} else if(spec == DMT.FNPSecretPing) {
			return node.netid.handleSecretPing(m);
		} else if(spec == DMT.FNPDetectedIPAddress) {
			Peer p = (Peer) m.getObject(DMT.EXTERNAL_ADDRESS);
			source.setRemoteDetectedPeer(p);
			node.ipDetector.redetectAddress();
			return true;
		} else if(spec == DMT.FNPTime) {
			return handleTime(m, source);
		} else if(spec == DMT.FNPUptime) {
			return handleUptime(m, source);
		} else if(spec == DMT.FNPSentPackets) {
			source.handleSentPackets(m);
			return true;
		} else if(spec == DMT.FNPVoid) {
			return true;
		} else if(spec == DMT.FNPDisconnect) {
			handleDisconnect(m, source);
			return true;
		} else if(spec == DMT.nodeToNodeMessage) {
			node.receivedNodeToNodeMessage(m, source);
			return true;
		} else if(spec == DMT.UOMAnnounce && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			return node.nodeUpdater.uom.handleAnnounce(m, source);
		} else if(spec == DMT.UOMRequestRevocation && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			return node.nodeUpdater.uom.handleRequestRevocation(m, source);
		} else if(spec == DMT.UOMSendingRevocation && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			return node.nodeUpdater.uom.handleSendingRevocation(m, source);
		} else if(spec == DMT.UOMRequestMain && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			node.nodeUpdater.uom.handleRequestJar(m, source, false);
			return true;
		} else if(spec == DMT.UOMRequestExtra && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			node.nodeUpdater.uom.handleRequestJar(m, source, true);
			return true;
		} else if(spec == DMT.UOMSendingMain && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			return node.nodeUpdater.uom.handleSendingMain(m, source);
		} else if(spec == DMT.UOMSendingExtra && node.nodeUpdater.isEnabled() && source.isRealConnection()) {
			return node.nodeUpdater.uom.handleSendingExt(m, source);
		} else if(spec == DMT.FNPOpennetAnnounceRequest) {
			return handleAnnounceRequest(m, source);
		} else if(spec == DMT.FNPRoutingStatus) {
			if(source instanceof DarknetPeerNode) {
				boolean value = m.getBoolean(DMT.ROUTING_ENABLED);
				if(logMINOR)
					Logger.minor(this, "The peer ("+source+") asked us to set routing="+value);
				((DarknetPeerNode)source).setRoutingStatus(value, false);
			}
			// We claim it in any case
			return true;
		} else if(source.isRealConnection() && spec == DMT.FNPLocChangeNotificationNew) {
			double newLoc = m.getDouble(DMT.LOCATION);
			ShortBuffer buffer = ((ShortBuffer) m.getObject(DMT.PEER_LOCATIONS));
			double[] locs = Fields.bytesToDoubles(buffer.getData());
			
			/**
			 * Do *NOT* remove the sanity check below! 
			 * @see http://archives.freenetproject.org/message/20080718.144240.359e16d3.en.html
			 */
			if((OpennetManager.MAX_PEERS_FOR_SCALING < locs.length) && (source.isOpennet())) {
				if(locs.length > OpennetManager.PANIC_MAX_PEERS) {
					// This can't happen by accident
					Logger.error(this, "We received "+locs.length+ " locations from "+source.toString()+"! That should *NOT* happen! Possible attack!");
					source.forceDisconnect(true);
					return true;
				} else {
					// A few extra can happen by accident. Just use the first 20.
					Logger.normal(this, "Too many locations from "+source.toString()+" : "+locs.length+" could be an accident, using the first "+OpennetManager.MAX_PEERS_FOR_SCALING);
					double[] firstLocs = new double[OpennetManager.MAX_PEERS_FOR_SCALING];
					System.arraycopy(locs, 0, firstLocs, 0, OpennetManager.MAX_PEERS_FOR_SCALING);
					locs = firstLocs;
				}
			}
			// We are on darknet and we trust our peers OR we are on opennet
			// and the amount of locations sent to us seems reasonable
			source.updateLocation(newLoc, locs);
			
			return true;
		}
		
		if(!source.isRoutable()) {
			if(logDEBUG) Logger.debug(this, "Not routable");

			if(spec == DMT.FNPCHKDataRequest) {
				rejectRequest(m, node.nodeStats.chkRequestCtr);
			} else if(spec == DMT.FNPSSKDataRequest) {
				rejectRequest(m, node.nodeStats.sskRequestCtr);
			} else if(spec == DMT.FNPInsertRequest) {
				rejectRequest(m, node.nodeStats.chkInsertCtr);
			} else if(spec == DMT.FNPSSKInsertRequest) {
				rejectRequest(m, node.nodeStats.sskInsertCtr);
			} else if(spec == DMT.FNPSSKInsertRequestNew) {
				rejectRequest(m, node.nodeStats.sskInsertCtr);
			} else if(spec == DMT.FNPGetOfferedKey) {
				rejectRequest(m, node.failureTable.senderCounter);
			}
			return false;
		}

		if(spec == DMT.FNPNetworkID) {
			source.handleFNPNetworkID(m);
			return true;
		} else if(spec == DMT.FNPSwapRequest) {
			return node.lm.handleSwapRequest(m, source);
		} else if(spec == DMT.FNPSwapReply) {
			return node.lm.handleSwapReply(m, source);
		} else if(spec == DMT.FNPSwapRejected) {
			return node.lm.handleSwapRejected(m, source);
		} else if(spec == DMT.FNPSwapCommit) {
			return node.lm.handleSwapCommit(m, source);
		} else if(spec == DMT.FNPSwapComplete) {
			return node.lm.handleSwapComplete(m, source);
		} else if(spec == DMT.FNPCHKDataRequest) {
			handleDataRequest(m, source, false);
			return true;
		} else if(spec == DMT.FNPSSKDataRequest) {
			handleDataRequest(m, source, true);
			return true;
		} else if(spec == DMT.FNPInsertRequest) {
			handleInsertRequest(m, source, false);
			return true;
		} else if(spec == DMT.FNPSSKInsertRequest) {
			handleInsertRequest(m, source, true);
			return true;
		} else if(spec == DMT.FNPSSKInsertRequestNew) {
			handleInsertRequest(m, source, true);
			return true;
		} else if(spec == DMT.FNPRHProbeRequest) {
			return handleProbeRequest(m, source);
		} else if(spec == DMT.FNPRoutedPing) {
			return handleRouted(m, source);
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
		} else if(spec == DMT.FNPOfferKey) {
			return handleOfferKey(m, source);
		} else if(spec == DMT.FNPGetOfferedKey) {
			return handleGetOfferedKey(m, source);
		} else if(spec == DMT.FNPPeerLoadStatusByte || spec == DMT.FNPPeerLoadStatusShort || spec == DMT.FNPPeerLoadStatusInt) {
			return handlePeerLoadStatus(m, source);
		}
		return false;
	}

	private void rejectRequest(Message m, ByteCounter ctr) {
		long uid = m.getLong(DMT.UID);
		Message msg = DMT.createFNPRejectedOverload(uid, true, false, false);
		// Send the load status anyway, hopefully this is a temporary problem.
		msg.setNeedsLoadBulk();
		msg.setNeedsLoadRT();
		try {
			m.getSource().sendAsync(msg, null, ctr);
		} catch (NotConnectedException e) {
			// Ignore
		}
	}

	private boolean handlePeerLoadStatus(Message m, PeerNode source) {
		PeerLoadStats stat = node.nodeStats.parseLoadStats(source, m);
		source.reportLoadStatus(stat);
		return true;
	}

	private boolean handleUptime(Message m, PeerNode source) {
		byte uptime = m.getByte(DMT.UPTIME_PERCENT_48H);
		source.setUptime(uptime);
		return true;
	}

	private boolean handleOfferKey(Message m, PeerNode source) {
		Key key = (Key) m.getObject(DMT.KEY);
		byte[] authenticator = ((ShortBuffer) m.getObject(DMT.OFFER_AUTHENTICATOR)).getData();
		node.failureTable.onOffer(key, source, authenticator);
		return true;
	}

	private boolean handleGetOfferedKey(Message m, PeerNode source) {
		Key key = (Key) m.getObject(DMT.KEY);
		byte[] authenticator = ((ShortBuffer) m.getObject(DMT.OFFER_AUTHENTICATOR)).getData();
		long uid = m.getLong(DMT.UID);
		if(!HMAC.verifyWithSHA256(node.failureTable.offerAuthenticatorKey, key.getFullKey(), authenticator)) {
			Logger.error(this, "Invalid offer request from "+source+" : authenticator did not verify");
			try {
				source.sendAsync(DMT.createFNPGetOfferedKeyInvalid(uid, DMT.GET_OFFERED_KEY_REJECTED_BAD_AUTHENTICATOR), null, node.failureTable.senderCounter);
			} catch (NotConnectedException e) {
				// Too bad.
			}
			return true;
		}
		if(logMINOR) Logger.minor(this, "Valid GetOfferedKey for "+key+" from "+source);
		
		// Do we want it? We can RejectOverload if we don't have the bandwidth...
		boolean isSSK = key instanceof NodeSSK;
        boolean realTimeFlag = DMT.getRealTimeFlag(m);
		OfferReplyTag tag = new OfferReplyTag(isSSK, source, realTimeFlag, uid, node);
		node.lockUID(uid, isSSK, false, true, false, realTimeFlag, tag);
		boolean needPubKey;
		try {
		needPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
		RejectReason reject = 
			nodeStats.shouldRejectRequest(true, false, isSSK, false, true, source, false, false, realTimeFlag);
		if(reject != null) {
			Logger.normal(this, "Rejecting FNPGetOfferedKey from "+source+" for "+key+" : "+reject);
			Message rejected = DMT.createFNPRejectedOverload(uid, true, true, realTimeFlag);
			if(reject.soft)
				rejected.addSubMessage(DMT.createFNPRejectIsSoft());
			try {
				source.sendAsync(rejected, null, node.failureTable.senderCounter);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) data request from "+source.getPeer()+": "+e);
			}
			tag.unlockHandler(reject.soft);
			return true;
		}
		
		} catch (Error e) {
			tag.unlockHandler();
			throw e;
		} catch (RuntimeException e) {
			tag.unlockHandler();
			throw e;
		} // Otherwise, sendOfferedKey is responsible for unlocking. 
		
		// Accept it.
		
		try {
			node.failureTable.sendOfferedKey(key, isSSK, needPubKey, uid, source, tag,realTimeFlag);
		} catch (NotConnectedException e) {
			// Too bad.
		}
		return true;
	}

	private void handleDisconnect(final Message m, final PeerNode source) {
		// Must run ON the packet sender thread as it sends a packet directly
		node.getTicker().queueTimedJob(new FastRunnable() {
			public void run() {
				// Send the ack
					source.sendAnyUrgentNotifications(true);
				finishDisconnect(m, source);
			}
		}, 0);
	}
	
	private void finishDisconnect(final Message m, final PeerNode source) {
		source.disconnected(true, true);
		// If true, remove from active routing table, likely to be down for a while.
		// Otherwise just dump all current connection state and keep trying to connect.
		boolean remove = m.getBoolean(DMT.REMOVE);
		if(remove)
			node.peers.disconnect(source, false, false, false);
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

	// We need to check the datastore before deciding whether to accept a request.
	// This can block - in bad cases, for a long time.
	// So we need to run it on a separate thread.
	
	private final PrioRunnable queueRunner = new PrioRunnable() {

		public void run() {
			while(true) {
				try {
					Message msg = requestQueue.take();
					boolean isSSK = msg.getSpec() == DMT.FNPSSKDataRequest;
					innerHandleDataRequest(msg, (PeerNode)msg.getSource(), isSSK);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}

		public int getPriority() {
			// Slightly less than the actual requests themselves because accepting requests increases load.
			return NativeThread.HIGH_PRIORITY-1;
		}
		
	};
	
	private final ArrayBlockingQueue<Message> requestQueue = new ArrayBlockingQueue<Message>(100);
	
	private void handleDataRequest(Message m, PeerNode source, boolean isSSK) {
		// FIXME check probablyInStore and if not, we can handle it inline.
		// This and DatastoreChecker require that method be implemented...
		// For now just handle everything on the thread...
		if(!requestQueue.offer(m)) {
			rejectRequest(m, isSSK ? node.nodeStats.sskRequestCtr : node.nodeStats.chkRequestCtr);
		}
	}
	
	/**
	 * Handle an incoming FNPDataRequest.
	 */
	private void innerHandleDataRequest(Message m, PeerNode source, boolean isSSK) {
		if(!source.isConnected()) {
			if(logMINOR) Logger.minor(this, "Handling request off thread, source disconnected: "+source+" for "+m);
			return;
		}
		if(!source.isRoutable()) {
			if(logMINOR) Logger.minor(this, "Handling request off thread, source no longer routable: "+source+" for "+m);
			rejectRequest(m, isSSK ? node.nodeStats.sskRequestCtr : node.nodeStats.chkRequestCtr);
			return;
		}
		long id = m.getLong(DMT.UID);
		ByteCounter ctr = isSSK ? node.nodeStats.sskRequestCtr : node.nodeStats.chkRequestCtr;
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting data request (loop, finished): "+e);
			}
			return;
		}
        short htl = m.getShort(DMT.HTL);
        Key key = (Key) m.getObject(DMT.FREENET_ROUTING_KEY);
        boolean realTimeFlag = DMT.getRealTimeFlag(m);
        final RequestTag tag = new RequestTag(isSSK, RequestTag.START.REMOTE, source, realTimeFlag, id, node);
		if(!node.lockUID(id, isSSK, false, false, false, realTimeFlag, tag)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting request from "+source.getPeer()+": "+e);
			}
			node.failureTable.onFinalFailure(key, null, htl, htl, -1, source);
			return;
		} else {
			if(logMINOR) Logger.minor(this, "Locked "+id);
		}
		
		// There are at least 2 threads that call this function.
		// DO NOT reuse the meta object, unless on a per-thread basis.
		// Object allocation is pretty cheap in modern Java anyway...
		// If we do reuse it, call reset().
		BlockMetadata meta = new BlockMetadata();
		KeyBlock block = node.fetch(key, false, false, false, false, meta);
		if(block != null)
			tag.setNotRoutedOnwards();
		
		RejectReason rejectReason = nodeStats.shouldRejectRequest(!isSSK, false, isSSK, false, false, source, block != null, false, realTimeFlag);
		if(rejectReason != null) {
			// can accept 1 CHK request every so often, but not with SSKs because they aren't throttled so won't sort out bwlimitDelayTime, which was the whole reason for accepting them when overloaded...
			Logger.normal(this, "Rejecting "+(isSSK ? "SSK" : "CHK")+" request from "+source.getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true, true, realTimeFlag);
			if(rejectReason.soft)
				rejected.addSubMessage(DMT.createFNPRejectIsSoft());
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) data request from "+source.getPeer()+": "+e);
			}
			tag.setRejected();
			tag.unlockHandler(rejectReason.soft);
			// Do not tell failure table.
			// Otherwise an attacker can flood us with requests very cheaply and purge our
			// failure table even though we didn't accept any of them.
			return;
		}
		nodeStats.reportIncomingRequestLocation(key.toNormalizedDouble());
		//if(!node.lockUID(id)) return false;
		RequestHandler rh = new RequestHandler(m, source, id, node, htl, key, tag, block, realTimeFlag);
		node.executor.execute(rh, "RequestHandler for UID "+id+" on "+node.getDarknetPortNumber());
	}

	private void handleInsertRequest(Message m, PeerNode source, boolean isSSK) {
		ByteCounter ctr = isSSK ? node.nodeStats.sskInsertCtr : node.nodeStats.chkInsertCtr;
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+source.getPeer()+": "+e);
			}
			return;
		}
        boolean realTimeFlag = DMT.getRealTimeFlag(m);
		InsertTag tag = new InsertTag(isSSK, InsertTag.START.REMOTE, source, realTimeFlag, id, node);
		if(!node.lockUID(id, isSSK, true, false, false, realTimeFlag, tag)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+source.getPeer()+": "+e);
			}
			return;
		}
		boolean preferInsert = Node.PREFER_INSERT_DEFAULT;
		boolean ignoreLowBackoff = Node.IGNORE_LOW_BACKOFF_DEFAULT;
		boolean forkOnCacheable = Node.FORK_ON_CACHEABLE_DEFAULT;
		Message forkControl = m.getSubMessage(DMT.FNPSubInsertForkControl);
		if(forkControl != null)
			forkOnCacheable = forkControl.getBoolean(DMT.ENABLE_INSERT_FORK_WHEN_CACHEABLE);
		Message lowBackoff = m.getSubMessage(DMT.FNPSubInsertIgnoreLowBackoff);
		if(lowBackoff != null)
			ignoreLowBackoff = lowBackoff.getBoolean(DMT.IGNORE_LOW_BACKOFF);
		Message preference = m.getSubMessage(DMT.FNPSubInsertPreferInsert);
		if(preference != null)
			preferInsert = preference.getBoolean(DMT.PREFER_INSERT);
		// SSKs don't fix bwlimitDelayTime so shouldn't be accepted when overloaded.
		RejectReason rejectReason = nodeStats.shouldRejectRequest(!isSSK, true, isSSK, false, false, source, false, preferInsert, realTimeFlag);
		if(rejectReason != null) {
			Logger.normal(this, "Rejecting insert from "+source.getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true, true, realTimeFlag);
			if(rejectReason.soft)
				rejected.addSubMessage(DMT.createFNPRejectIsSoft());
			try {
				source.sendAsync(rejected, null, ctr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) insert request from "+source.getPeer()+": "+e);
			}
			tag.unlockHandler(rejectReason.soft);
			return;
		}
		long now = System.currentTimeMillis();
		if(m.getSpec().equals(DMT.FNPSSKInsertRequest)) {
			NodeSSK key = (NodeSSK) m.getObject(DMT.FREENET_ROUTING_KEY);
	        byte[] data = ((ShortBuffer) m.getObject(DMT.DATA)).getData();
	        byte[] headers = ((ShortBuffer) m.getObject(DMT.BLOCK_HEADERS)).getData();
	        short htl = m.getShort(DMT.HTL);
			SSKInsertHandler rh = new SSKInsertHandler(key, data, headers, htl, source, id, node, now, tag, node.canWriteDatastoreInsert(htl), forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
	        rh.receivedBytes(m.receivedByteCount());
			node.executor.execute(rh, "SSKInsertHandler for "+id+" on "+node.getDarknetPortNumber());
		} else if(m.getSpec().equals(DMT.FNPSSKInsertRequestNew)) {
			NodeSSK key = (NodeSSK) m.getObject(DMT.FREENET_ROUTING_KEY);
			short htl = m.getShort(DMT.HTL);
			SSKInsertHandler rh = new SSKInsertHandler(key, null, null, htl, source, id, node, now, tag, node.canWriteDatastoreInsert(htl), forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
	        rh.receivedBytes(m.receivedByteCount());
			node.executor.execute(rh, "SSKInsertHandler for "+id+" on "+node.getDarknetPortNumber());
		} else {
			CHKInsertHandler rh = new CHKInsertHandler(m, source, id, node, now, tag, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
			node.executor.execute(rh, "CHKInsertHandler for "+id+" on "+node.getDarknetPortNumber());
		}
		if(logMINOR) Logger.minor(this, "Started InsertHandler for "+id);
	}
	
	private boolean handleProbeRequest(Message m, PeerNode source) {
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				source.sendAsync(rejected, null, node.nodeStats.probeRequestCtr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting probe request from "+source.getPeer()+": "+e);
			}
			return true;
		}
		// Lets not bother with full lockUID, just add it to the recently completed list.
		node.completed(id);
		// SSKs don't fix bwlimitDelayTime so shouldn't be accepted when overloaded.
		if(source.shouldRejectProbeRequest()) {
			Logger.normal(this, "Rejecting probe request from "+source.getPeer());
			Message rejected = DMT.createFNPRejectedOverload(id, true, false, false);
			try {
				source.sendAsync(rejected, null, node.nodeStats.probeRequestCtr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) insert request from "+source.getPeer()+": "+e);
			}
			return true;
		}
		double target = m.getDouble(DMT.TARGET_LOCATION);
		if(target > 1.0 || target < 0.0) {
			Logger.normal(this, "Rejecting invalid (target="+target+") probe request from "+source.getPeer());
			Message rejected = DMT.createFNPRejectedOverload(id, true, false, false);
			try {
				source.sendAsync(rejected, null, node.nodeStats.probeRequestCtr);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (invalid) insert request from "+source.getPeer()+": "+e);
			}
			return true;
		}
		ProbeRequestHandler.start(m, source, node, target);
		return true;
	}

	private boolean handleAnnounceRequest(Message m, PeerNode source) {
		long uid = m.getLong(DMT.UID);
		OpennetManager om = node.getOpennet();
		if(om == null || !source.canAcceptAnnouncements()) {
			if(om != null && source instanceof SeedClientPeerNode)
				om.seedTracker.rejectedAnnounce((SeedClientPeerNode)source);
			Message msg = DMT.createFNPOpennetDisabled(uid);
			try {
				source.sendAsync(msg, null, node.nodeStats.announceByteCounter);
			} catch (NotConnectedException e) {
				// Ok
			}
			return true;
		}
		if(node.recentlyCompleted(uid)) {
			if(om != null && source instanceof SeedClientPeerNode)
				om.seedTracker.rejectedAnnounce((SeedClientPeerNode)source);
			Message msg = DMT.createFNPRejectedLoop(uid);
			try {
				source.sendAsync(msg, null, node.nodeStats.announceByteCounter);
			} catch (NotConnectedException e) {
				// Ok
			}
			return true;
		}
		boolean success = false;
		// No way to check whether it's actually running atm, so lets report it to the completed list immediately.
		// FIXME we should probably keep a list!
		node.completed(uid);
		try {
			if(!node.nodeStats.shouldAcceptAnnouncement(uid)) {
				if(om != null && source instanceof SeedClientPeerNode)
					om.seedTracker.rejectedAnnounce((SeedClientPeerNode)source);
				Message msg = DMT.createFNPRejectedOverload(uid, true, false, false);
				try {
					source.sendAsync(msg, null, node.nodeStats.announceByteCounter);
				} catch (NotConnectedException e) {
					// Ok
				}
				return true;
			}
			if(!source.shouldAcceptAnnounce(uid)) {
				if(om != null && source instanceof SeedClientPeerNode)
					om.seedTracker.rejectedAnnounce((SeedClientPeerNode)source);
				node.nodeStats.endAnnouncement(uid);
				Message msg = DMT.createFNPRejectedOverload(uid, true, false, false);
				try {
					source.sendAsync(msg, null, node.nodeStats.announceByteCounter);
				} catch (NotConnectedException e) {
					// Ok
				}
				return true;
			}
			if(om != null && source instanceof SeedClientPeerNode) {
				if(!om.seedTracker.acceptAnnounce((SeedClientPeerNode)source, node.fastWeakRandom)) {
					node.nodeStats.endAnnouncement(uid);
					Message msg = DMT.createFNPRejectedOverload(uid, true, false, false);
					try {
						source.sendAsync(msg, null, node.nodeStats.announceByteCounter);
					} catch (NotConnectedException e) {
						// Ok
					}
					return true;
				}
			}
			AnnounceSender sender = new AnnounceSender(m, uid, source, om, node);
			node.executor.execute(sender, "Announcement sender for "+uid);
			success = true;
			return true;
		} finally {
			if(!success)
				source.completedAnnounce(uid);
		}
	}

	final Hashtable<Long, RoutedContext> routedContexts = new Hashtable<Long, RoutedContext>();

	static class RoutedContext {
		long createdTime;
		long accessTime;
		PeerNode source;
		final HashSet<PeerNode> routedTo;
		Message msg;
		short lastHtl;
		final byte[] identity;

		RoutedContext(Message msg, PeerNode source, byte[] identity) {
			createdTime = accessTime = System.currentTimeMillis();
			this.source = source;
			routedTo = new HashSet<PeerNode>();
			this.msg = msg;
			lastHtl = msg.getShort(DMT.HTL);
			this.identity = identity;
		}

		void addSent(PeerNode n) {
			routedTo.add(n);
		}
	}
	
	/**
	 * Cleanup any old/stale routing contexts and reschedule execution.
	 */
	public void run() {
		long now=System.currentTimeMillis();
		synchronized (routedContexts) {
			Iterator<RoutedContext> i = routedContexts.values().iterator();
			while (i.hasNext()) {
				RoutedContext rc = i.next();
				if (now-rc.createdTime > STALE_CONTEXT) {
					i.remove();
				}
			}
		}
		node.getTicker().queueTimedJob(this, STALE_CONTEXT_CHECK);
	}

	/**
	 * Handle an FNPRoutedRejected message.
	 */
	private boolean handleRoutedRejected(Message m) {
		long id = m.getLong(DMT.UID);
		Long lid = Long.valueOf(id);
		RoutedContext rc = routedContexts.get(lid);
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
		if(htl == 0) {
			// Equivalent to DNF.
			// Relay.
			if(rc.source != null) {
				try {
					rc.source.sendAsync(DMT.createFNPRoutedRejected(id, (short)0), null, nodeStats.routedMessageCtr);
				} catch (NotConnectedException e) {
					// Ouch.
					Logger.error(this, "Unable to relay probe DNF: peer disconnected: "+rc.source);
				}
			}
		} else {
			// Try routing to the next node
			forward(rc.msg, id, rc.source, htl, rc.msg.getDouble(DMT.TARGET_LOCATION), rc, rc.identity);
		}
		return true;
	}

	/**
	 * Handle a routed-to-a-specific-node message.
	 * @param m
	 * @return False if we want the message put back on the queue.
	 */
	boolean handleRouted(Message m, PeerNode source) {
		if(logMINOR) Logger.minor(this, "handleRouted("+m+ ')');

		long id = m.getLong(DMT.UID);
		Long lid = Long.valueOf(id);
		short htl = m.getShort(DMT.HTL);
		byte[] identity = ((ShortBuffer) m.getObject(DMT.NODE_IDENTITY)).getData();
		if(source != null) htl = source.decrementHTL(htl);
		RoutedContext ctx;
		ctx = routedContexts.get(lid);
		if(ctx != null) {
			try {
				source.sendAsync(DMT.createFNPRoutedRejected(id, htl), null, nodeStats.routedMessageCtr);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		}
		ctx = new RoutedContext(m, source, identity);
		synchronized (routedContexts) {
			routedContexts.put(lid, ctx);
		}
		// source == null => originated locally, keep full htl
		double target = m.getDouble(DMT.TARGET_LOCATION);
		if(logMINOR) Logger.minor(this, "id "+id+" from "+source+" htl "+htl+" target "+target);
		if(Math.abs(node.lm.getLocation() - target) <= Double.MIN_VALUE) {
			if(logMINOR) Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.getDarknetPortNumber());
			// Handle locally
			// Message type specific processing
			dispatchRoutedMessage(m, source, id);
			return true;
		} else if(htl == 0) {
			Message reject = DMT.createFNPRoutedRejected(id, (short)0);
			if(source != null) try {
				source.sendAsync(reject, null, nodeStats.routedMessageCtr);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		} else {
			return forward(m, id, source, htl, target, ctx, identity);
		}
	}

	boolean handleRoutedReply(Message m) {
		long id = m.getLong(DMT.UID);
		if(logMINOR) Logger.minor(this, "Got reply: "+m);
		Long lid = Long.valueOf(id);
		RoutedContext ctx = routedContexts.get(lid);
		if(ctx == null) {
			Logger.error(this, "Unrecognized routed reply: "+m);
			return false;
		}
		PeerNode pn = ctx.source;
		if(pn == null) return false;
		try {
			pn.sendAsync(m, null, nodeStats.routedMessageCtr);
		} catch (NotConnectedException e) {
			if(logMINOR) Logger.minor(this, "Lost connection forwarding "+m+" to "+pn);
		}
		return true;
	}

	private boolean forward(Message m, long id, PeerNode pn, short htl, double target, RoutedContext ctx, byte[] targetIdentity) {
		if(logMINOR) Logger.minor(this, "Should forward");
		// Forward
		m = preForward(m, htl);
		while(true) {
			PeerNode next = node.peers.getByIdentity(targetIdentity);
			if(next != null && !next.isConnected()) {
				Logger.error(this, "Found target but disconnected!: "+next);
				next = null;
			}
			if(next == null)
			next = node.peers.closerPeer(pn, ctx.routedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
				        null, htl, 0, pn == null, false);
			if(logMINOR) Logger.minor(this, "Next: "+next+" message: "+m);
			if(next != null) {
				// next is connected, or at least has been => next.getPeer() CANNOT be null.
				if(logMINOR) Logger.minor(this, "Forwarding "+m.getSpec()+" to "+next.getPeer().getPort());
				ctx.addSent(next);
				try {
					next.sendAsync(m, null, nodeStats.routedMessageCtr);
				} catch (NotConnectedException e) {
					continue;
				}
			} else {
				if(logMINOR) Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.getDarknetPortNumber());
				// Reached a dead end...
				Message reject = DMT.createFNPRoutedRejected(id, htl);
				if(pn != null) try {
					pn.sendAsync(reject, null, nodeStats.routedMessageCtr);
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
			if(logMINOR) Logger.minor(this, "RoutedPing reached other side! ("+id+")");
			int x = m.getInt(DMT.COUNTER);
			Message reply = DMT.createFNPRoutedPong(id, x);
			if(logMINOR) Logger.minor(this, "Replying - counter = "+x+" for "+id);
			try {
				src.sendAsync(reply, null, nodeStats.routedMessageCtr);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m+" in dispatchRoutedMessage");
			}
			return true;
		}
		return false;
	}

	void start(NodeStats stats) {
		this.nodeStats = stats;
		node.executor.execute(queueRunner);
	}

	public static String peersUIDsToString(long[] peerUIDs, double[] peerLocs) {
		StringBuilder sb = new StringBuilder(peerUIDs.length*23+peerLocs.length*26);
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
	public static final int PROBE_TYPE_RESETTING_HTL = 0;
	
	public void startProbe(final double target, final ProbeCallback cb) {
		final long uid = node.random.nextLong();
		
			ProbeRequestSender rs = new ProbeRequestSender(target, node.maxHTL(), uid, node, node.getLocation(), null, 2.0);
			rs.addListener(new ProbeRequestSender.Listener() {

				public void onCompletion(double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
					cb.onCompleted("completed", target, best, nearest, uid, counter, uniqueCounter, linearCounter);
				}

				public void onRNF(short htl, double nearest, double best, short counter, short uniqueCounter, short linearCounter) throws NotConnectedException {
					cb.onCompleted("rnf", target, best, nearest, uid, counter, uniqueCounter, linearCounter);					
				}

				public void onReceivedRejectOverload(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
					cb.onRejectOverload();
				}

				public void onTimeout(double nearest, double best, short counter, short uniqueCounter, short linearCounter, String reason) throws NotConnectedException {
					cb.onCompleted("timeout", target, best, nearest, uid, counter, uniqueCounter, linearCounter);					
				}

				public void onTrace(long uid, double nearest, double best, short htl, short counter, short uniqueCounter, double location, long myUID, ShortBuffer peerLocs, ShortBuffer peerUIDs, short forkCount, short linearCounter, String reason, long prevUID) throws NotConnectedException {
					cb.onTrace(uid, target, nearest, best, htl, counter, location, myUID, Fields.bytesToDoubles(peerLocs.getData()), Fields.bytesToLongs(peerUIDs.getData()), new double[0], forkCount, linearCounter, reason, prevUID);
				}
				
			});
			rs.start();
	}

	public void setHook(NodeDispatcherCallback cb) {
		this.callback = cb;
	}
}
