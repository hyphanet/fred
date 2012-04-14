package freenet.node.requests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.node.PeerNode;
import freenet.support.LRUQueue;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;

/** Tracks all running requests */
public class RequestTracker {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(RequestTracker.class);
	}
	
	private final Ticker ticker;

	/** HashSet of currently running request UIDs */
	private final HashMap<Long,UIDTag> runningUIDs;
	private final HashMap<Long,RequestTag> runningCHKGetUIDsBulk;
	private final HashMap<Long,RequestTag> runningLocalCHKGetUIDsBulk;
	private final HashMap<Long,RequestTag> runningSSKGetUIDsBulk;
	private final HashMap<Long,RequestTag> runningLocalSSKGetUIDsBulk;
	private final HashMap<Long,InsertTag> runningCHKPutUIDsBulk;
	private final HashMap<Long,InsertTag> runningLocalCHKPutUIDsBulk;
	private final HashMap<Long,InsertTag> runningSSKPutUIDsBulk;
	private final HashMap<Long,InsertTag> runningLocalSSKPutUIDsBulk;
	private final HashMap<Long,OfferReplyTag> runningCHKOfferReplyUIDsBulk;
	private final HashMap<Long,OfferReplyTag> runningSSKOfferReplyUIDsBulk;

	private final HashMap<Long,RequestTag> runningCHKGetUIDsRT;
	private final HashMap<Long,RequestTag> runningLocalCHKGetUIDsRT;
	private final HashMap<Long,RequestTag> runningSSKGetUIDsRT;
	private final HashMap<Long,RequestTag> runningLocalSSKGetUIDsRT;
	private final HashMap<Long,InsertTag> runningCHKPutUIDsRT;
	private final HashMap<Long,InsertTag> runningLocalCHKPutUIDsRT;
	private final HashMap<Long,InsertTag> runningSSKPutUIDsRT;
	private final HashMap<Long,InsertTag> runningLocalSSKPutUIDsRT;
	private final HashMap<Long,OfferReplyTag> runningCHKOfferReplyUIDsRT;
	private final HashMap<Long,OfferReplyTag> runningSSKOfferReplyUIDsRT;
	
	/** RequestSender's currently transferring, by key */
	private final HashMap<NodeCHK, RequestSender> transferringRequestSendersRT;
	private final HashMap<NodeCHK, RequestSender> transferringRequestSendersBulk;
	/** UIDs of RequestHandler's currently transferring */
	private final HashSet<Long> transferringRequestHandlers;
	
	private final CompletedGroupHandler completedGroupHandler;
	
	final LRUQueue<Long> recentlyCompletedIDs;

	static final int MAX_RECENTLY_COMPLETED_IDS = 10*1000;

	private ArrayList<Long> completedBuffer = new ArrayList<Long>();

	// Every this many slots, we tell all the PeerMessageQueue's to remove the old Items for the ID's in question.
	// This prevents memory DoS amongst other things.
	static final int COMPLETED_THRESHOLD = 128;

	public RequestTracker(Ticker ticker, CompletedGroupHandler completedGroupHandler) {
		this.ticker = ticker;
		this.completedGroupHandler = completedGroupHandler;
		runningUIDs = new HashMap<Long,UIDTag>();
		runningCHKGetUIDsRT = new HashMap<Long,RequestTag>();
		runningLocalCHKGetUIDsRT = new HashMap<Long,RequestTag>();
		runningSSKGetUIDsRT = new HashMap<Long,RequestTag>();
		runningLocalSSKGetUIDsRT = new HashMap<Long,RequestTag>();
		runningCHKPutUIDsRT = new HashMap<Long,InsertTag>();
		runningLocalCHKPutUIDsRT = new HashMap<Long,InsertTag>();
		runningSSKPutUIDsRT = new HashMap<Long,InsertTag>();
		runningLocalSSKPutUIDsRT = new HashMap<Long,InsertTag>();
		runningCHKOfferReplyUIDsRT = new HashMap<Long,OfferReplyTag>();
		runningSSKOfferReplyUIDsRT = new HashMap<Long,OfferReplyTag>();

		runningCHKGetUIDsBulk = new HashMap<Long,RequestTag>();
		runningLocalCHKGetUIDsBulk = new HashMap<Long,RequestTag>();
		runningSSKGetUIDsBulk = new HashMap<Long,RequestTag>();
		runningLocalSSKGetUIDsBulk = new HashMap<Long,RequestTag>();
		runningCHKPutUIDsBulk = new HashMap<Long,InsertTag>();
		runningLocalCHKPutUIDsBulk = new HashMap<Long,InsertTag>();
		runningSSKPutUIDsBulk = new HashMap<Long,InsertTag>();
		runningLocalSSKPutUIDsBulk = new HashMap<Long,InsertTag>();
		runningCHKOfferReplyUIDsBulk = new HashMap<Long,OfferReplyTag>();
		runningSSKOfferReplyUIDsBulk = new HashMap<Long,OfferReplyTag>();
		
		transferringRequestSendersRT = new HashMap<NodeCHK, RequestSender>();
		transferringRequestSendersBulk = new HashMap<NodeCHK, RequestSender>();
		transferringRequestHandlers = new HashSet<Long>();
		recentlyCompletedIDs = new LRUQueue<Long>();
	}
	
	public boolean lockUID(UIDTag tag) {
		return lockUID(tag.uid, tag.isSSK(), tag.isInsert(), tag.isOfferReply(), tag.wasLocal(), tag.realTimeFlag, tag);
	}

	public boolean lockUID(long uid, boolean ssk, boolean insert, boolean offerReply, boolean local, boolean realTimeFlag, UIDTag tag) {
		synchronized(runningUIDs) {
			if(runningUIDs.containsKey(uid)) return false; // Already present.
			runningUIDs.put(uid, tag);
		}
		// If these are switched around, we must remember to remove from both.
		if(offerReply) {
			HashMap<Long,OfferReplyTag> map = getOfferTracker(ssk, realTimeFlag);
			innerLock(map, (OfferReplyTag)tag, uid, ssk, insert, offerReply, local);
		} else if(insert) {
			HashMap<Long,InsertTag> map = getInsertTracker(ssk,local, realTimeFlag);
			innerLock(map, (InsertTag)tag, uid, ssk, insert, offerReply, local);
		} else {
			HashMap<Long,RequestTag> map = getRequestTracker(ssk,local, realTimeFlag);
			innerLock(map, (RequestTag)tag, uid, ssk, insert, offerReply, local);
		}
		return true;
	}

	private<T extends UIDTag> void innerLock(HashMap<Long, T> map, T tag, Long uid, boolean ssk, boolean insert, boolean offerReply, boolean local) {
		synchronized(map) {
			if(logMINOR) Logger.minor(this, "Locking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size(), new Exception("debug"));
			if(map.containsKey(uid)) {
				Logger.error(this, "Already have UID in specific map ("+ssk+","+insert+","+offerReply+","+local+") but not in general map: trying to register "+tag+" but already have "+map.get(uid));
			}
			map.put(uid, tag);
			if(logMINOR) Logger.minor(this, "Locked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size());
		}
	}

	/** Only used by UIDTag. */
	void unlockUID(UIDTag tag, boolean canFail, boolean noRecord) {
		unlockUID(tag.uid, tag.isSSK(), tag.isInsert(), canFail, tag.isOfferReply(), tag.wasLocal(), tag.realTimeFlag, tag, noRecord);
	}

	protected void unlockUID(long uid, boolean ssk, boolean insert, boolean canFail, boolean offerReply, boolean local, boolean realTimeFlag, UIDTag tag, boolean noRecord) {
		if(!noRecord)
			completed(uid);

		if(offerReply) {
			HashMap<Long,OfferReplyTag> map = getOfferTracker(ssk, realTimeFlag);
			innerUnlock(map, (OfferReplyTag)tag, uid, ssk, insert, offerReply, local, canFail);
		} else if(insert) {
			HashMap<Long,InsertTag> map = getInsertTracker(ssk,local, realTimeFlag);
			innerUnlock(map, (InsertTag)tag, uid, ssk, insert, offerReply, local, canFail);
		} else {
			HashMap<Long,RequestTag> map = getRequestTracker(ssk,local, realTimeFlag);
			innerUnlock(map, (RequestTag)tag, uid, ssk, insert, offerReply, local, canFail);
		}

		synchronized(runningUIDs) {
			UIDTag oldTag = runningUIDs.get(uid);
			if(oldTag == null) {
				if(canFail) return;
				throw new IllegalStateException("Could not unlock "+uid+ "! : ssk="+ssk+" insert="+insert+" canFail="+canFail+" offerReply="+offerReply+" local="+local);
			} else if(tag != oldTag) {
				if(canFail) return;
				Logger.error(this, "Removing "+tag+" for "+uid+" but "+tag+" is registered!");
				return;
			} else {
				runningUIDs.remove(uid);
			}
		}
	}

	private<T extends UIDTag> void innerUnlock(HashMap<Long, T> map, T tag, Long uid, boolean ssk, boolean insert, boolean offerReply, boolean local, boolean canFail) {
		synchronized(map) {
			if(logMINOR) Logger.minor(this, "Unlocking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size(), new Exception("debug"));
			if(map.get(uid) != tag) {
				if(canFail) {
					if(logMINOR) Logger.minor(this, "Can fail and did fail: removing "+tag+" got "+map.get(uid)+" for "+uid);
				} else {
					Logger.error(this, "Removing "+tag+" for "+uid+" returned "+map.get(uid));
				}
			} else
				map.remove(uid);
			if(logMINOR) Logger.minor(this, "Unlocked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size());
		}
	}

	public static class CountedRequests {
		public int total;
		public int expectedTransfersOut;
		public int expectedTransfersIn;
		private CountedRequests(int count, int out, int in) {
			total = count;
			expectedTransfersOut = out;
			expectedTransfersIn = in;
		}
		public CountedRequests() {
			// Initially empty.
		}
	}

	public synchronized void countRequests(boolean local, boolean ssk, boolean insert, boolean offer, boolean realTimeFlag, int transfersPerInsert, boolean ignoreLocalVsRemote, CountedRequests counter, CountedRequests counterSourceRestarted) {
		HashMap<Long, ? extends UIDTag> map = getTracker(local, ssk, insert, offer, realTimeFlag);
		synchronized(map) {
			int count = 0;
			int transfersOut = 0;
			int transfersIn = 0;
			int countSR = 0;
			int transfersOutSR = 0;
			int transfersInSR = 0;
			for(Map.Entry<Long, ? extends UIDTag> entry : map.entrySet()) {
				UIDTag tag = entry.getValue();
				int out = tag.expectedTransfersOut(ignoreLocalVsRemote, transfersPerInsert, true);
				int in = tag.expectedTransfersIn(ignoreLocalVsRemote, transfersPerInsert, true);
				count++;
				transfersOut += out;
				transfersIn += in;
				if(counterSourceRestarted != null && tag.countAsSourceRestarted()) {
					countSR++;
					transfersOutSR += out;
					transfersInSR += in;
				}
				if(logDEBUG) Logger.debug(this, "UID "+entry.getKey()+" : out "+transfersOut+" in "+transfersIn);
			}
			counter.total += count;
			counter.expectedTransfersIn += transfersIn;
			counter.expectedTransfersOut += transfersOut;
			if(counterSourceRestarted != null) {
				counterSourceRestarted.total += countSR;
				counterSourceRestarted.expectedTransfersIn += transfersInSR;
				counterSourceRestarted.expectedTransfersOut += transfersOutSR;
			}
		}
	}

	public void countRequests(PeerNode source, boolean requestsToNode, boolean local, boolean ssk, boolean insert, boolean offer, boolean realTimeFlag, int transfersPerInsert, boolean ignoreLocalVsRemote, CountedRequests counter, CountedRequests counterSR) {
		HashMap<Long, ? extends UIDTag> map = getTracker(local, ssk, insert, offer, realTimeFlag);
		synchronized(map) {
		int count = 0;
		int transfersOut = 0;
		int transfersIn = 0;
		int countSR = 0;
		int transfersOutSR = 0;
		int transfersInSR = 0;
		if(!requestsToNode) {
			// If a request is adopted by us as a result of a timeout, it can be in the
			// remote map despite having source == null. However, if a request is in the
			// local map it will always have source == null.
			if(source != null && local) return;
			for(Map.Entry<Long, ? extends UIDTag> entry : map.entrySet()) {
				UIDTag tag = entry.getValue();
				if(tag.getSource() == source) {
					int out = tag.expectedTransfersOut(ignoreLocalVsRemote, transfersPerInsert, true);
					int in = tag.expectedTransfersIn(ignoreLocalVsRemote, transfersPerInsert, true);
					count++;
					transfersOut += out;
					transfersIn += in;
					if(counterSR != null && tag.countAsSourceRestarted()) {
						countSR++;
						transfersOutSR += out;
						transfersInSR += in;
					}
					if(logMINOR) Logger.minor(this, "Counting "+tag+" from "+entry.getKey()+" from "+source+" count now "+count+" out now "+transfersOut+" in now "+transfersIn);
				} else if(logDEBUG) Logger.debug(this, "Not counting "+entry.getKey());
			}
			if(logMINOR) Logger.minor(this, "Returning count: "+count+" in: "+transfersIn+" out: "+transfersOut);
			counter.total += count;
			counter.expectedTransfersIn += transfersIn;
			counter.expectedTransfersOut += transfersOut;
			if(counterSR != null) {
				counterSR.total += countSR;
				counterSR.expectedTransfersIn += transfersInSR;
				counterSR.expectedTransfersOut += transfersOutSR;
			}
		} else {
			// hasSourceRestarted is irrelevant for requests *to* a node.
			// FIXME improve efficiency!
			for(Map.Entry<Long, ? extends UIDTag> entry : map.entrySet()) {
				UIDTag tag = entry.getValue();
				// Ordinary requests can be routed to an offered key.
				// So we *DO NOT* care whether it's an ordinary routed relayed request or a GetOfferedKey, if we are counting outgoing requests.
				if(tag.currentlyFetchingOfferedKeyFrom(source)) {
					if(logMINOR) Logger.minor(this, "Counting "+tag+" to "+entry.getKey());
					transfersOut += tag.expectedTransfersOut(ignoreLocalVsRemote, transfersPerInsert, false);
					transfersIn += tag.expectedTransfersIn(ignoreLocalVsRemote, transfersPerInsert, false);
					count++;
				} else if(tag.currentlyRoutingTo(source)) {
					if(logMINOR) Logger.minor(this, "Counting "+tag+" to "+entry.getKey());
					transfersOut += tag.expectedTransfersOut(ignoreLocalVsRemote, transfersPerInsert, false);
					transfersIn += tag.expectedTransfersIn(ignoreLocalVsRemote, transfersPerInsert, false);
					count++;
				} else if(logDEBUG) Logger.debug(this, "Not counting "+entry.getKey());
			}
			if(logMINOR) Logger.minor(this, "Counted for "+(local?"local":"remote")+" "+(ssk?"ssk":"chk")+" "+(insert?"insert":"request")+" "+(offer?"offer":"")+" : "+count+" of "+map.size()+" for "+source);
			counter.total += count;
			counter.expectedTransfersIn += transfersIn;
			counter.expectedTransfersOut += transfersOut;
		}
		}
	}
	
	/**
	 * @return [0] is the number of local requests waiting for slots, [1] is the
	 * number of remote requests waiting for slots.
	 */
	public int[] countRequestsWaitingForSlots() {
		// FIXME use a counter, but that means make sure it always removes it when something bad happens.
		
		int local = 0;
		int remote = 0;
		synchronized(runningUIDs) {
			for(UIDTag tag : runningUIDs.values()) {
				if(!tag.isWaitingForSlot()) continue;
				if(tag.isLocal())
					local++;
				else
					remote++;
			}
		}
		return new int[] { local, remote };
	}

	void reassignTagToSelf(UIDTag tag) {
		// The tag remains remote, but we flag it as adopted.
		tag.reassignToSelf();
	}

	private synchronized HashMap<Long, ? extends UIDTag> getTracker(boolean local, boolean ssk,
			boolean insert, boolean offer, boolean realTimeFlag) {
		if(offer)
			return getOfferTracker(ssk, realTimeFlag);
		else if(insert)
			return getInsertTracker(ssk, local, realTimeFlag);
		else
			return getRequestTracker(ssk, local, realTimeFlag);
	}


	private HashMap<Long, RequestTag> getRequestTracker(boolean ssk, boolean local, boolean realTimeFlag) {
		if(realTimeFlag) {
			if(ssk) {
				return local ? runningLocalSSKGetUIDsRT : runningSSKGetUIDsRT;
			} else {
				return local ? runningLocalCHKGetUIDsRT : runningCHKGetUIDsRT;
			}
		} else {
			if(ssk) {
				return local ? runningLocalSSKGetUIDsBulk : runningSSKGetUIDsBulk;
			} else {
				return local ? runningLocalCHKGetUIDsBulk : runningCHKGetUIDsBulk;
			}
		}
	}

	private HashMap<Long, InsertTag> getInsertTracker(boolean ssk, boolean local, boolean realTimeFlag) {
		if(realTimeFlag) {
			if(ssk) {
				return local ? runningLocalSSKPutUIDsRT : runningSSKPutUIDsRT;
			} else {
				return local ? runningLocalCHKPutUIDsRT : runningCHKPutUIDsRT;
			}
		} else {
			if(ssk) {
				return local ? runningLocalSSKPutUIDsBulk : runningSSKPutUIDsBulk;
			} else {
				return local ? runningLocalCHKPutUIDsBulk : runningCHKPutUIDsBulk;
			}
		}
	}

	private HashMap<Long, OfferReplyTag> getOfferTracker(boolean ssk, boolean realTimeFlag) {
		if(realTimeFlag)
			return ssk ? runningSSKOfferReplyUIDsRT : runningCHKOfferReplyUIDsRT;
		else
			return ssk ? runningSSKOfferReplyUIDsBulk : runningCHKOfferReplyUIDsBulk;
	}

	// Must include bulk inserts so fairly long.
	// 21 minutes is enough for a fatal timeout.
	static final int TIMEOUT = 21 * 60 * 1000;

	public void startDeadUIDChecker(Ticker ticker) {
		ticker.queueTimedJob(deadUIDChecker, TIMEOUT);
	}

	private Runnable deadUIDChecker = new Runnable() {
		@Override
		public void run() {
			try {
				checkUIDs(runningLocalSSKGetUIDsRT);
				checkUIDs(runningLocalCHKGetUIDsRT);
				checkUIDs(runningLocalSSKPutUIDsRT);
				checkUIDs(runningLocalCHKPutUIDsRT);
				checkUIDs(runningSSKGetUIDsRT);
				checkUIDs(runningCHKGetUIDsRT);
				checkUIDs(runningSSKPutUIDsRT);
				checkUIDs(runningCHKPutUIDsRT);
				checkUIDs(runningSSKOfferReplyUIDsRT);
				checkUIDs(runningCHKOfferReplyUIDsRT);
				checkUIDs(runningLocalSSKGetUIDsBulk);
				checkUIDs(runningLocalCHKGetUIDsBulk);
				checkUIDs(runningLocalSSKPutUIDsBulk);
				checkUIDs(runningLocalCHKPutUIDsBulk);
				checkUIDs(runningSSKGetUIDsBulk);
				checkUIDs(runningCHKGetUIDsBulk);
				checkUIDs(runningSSKPutUIDsBulk);
				checkUIDs(runningCHKPutUIDsBulk);
				checkUIDs(runningSSKOfferReplyUIDsBulk);
				checkUIDs(runningCHKOfferReplyUIDsBulk);
			} finally {
				ticker.queueTimedJob(this, 60*1000);
			}
		}

		private void checkUIDs(HashMap<Long, ? extends UIDTag> map) {
			Long[] uids;
			UIDTag[] tags;
			synchronized(map) {
				uids = map.keySet().toArray(new Long[map.size()]);
				tags = map.values().toArray(new UIDTag[map.size()]);
			}
			long now = System.currentTimeMillis();
			for(int i=0;i<uids.length;i++) {
				tags[i].maybeLogStillPresent(now, uids[i]);
			}
		}
	};
	

	public void onRestartOrDisconnect(PeerNode pn) {
		onRestartOrDisconnect(pn, runningSSKGetUIDsRT);
		onRestartOrDisconnect(pn, runningCHKGetUIDsRT);
		onRestartOrDisconnect(pn, runningSSKPutUIDsRT);
		onRestartOrDisconnect(pn, runningCHKPutUIDsRT);
		onRestartOrDisconnect(pn, runningSSKOfferReplyUIDsRT);
		onRestartOrDisconnect(pn, runningCHKOfferReplyUIDsRT);
		onRestartOrDisconnect(pn, runningSSKGetUIDsBulk);
		onRestartOrDisconnect(pn, runningCHKGetUIDsBulk);
		onRestartOrDisconnect(pn, runningSSKPutUIDsBulk);
		onRestartOrDisconnect(pn, runningCHKPutUIDsBulk);
		onRestartOrDisconnect(pn, runningSSKOfferReplyUIDsBulk);
		onRestartOrDisconnect(pn, runningCHKOfferReplyUIDsBulk);
	}

	private void onRestartOrDisconnect(PeerNode pn,
			HashMap<Long, ? extends UIDTag> uids) {
		synchronized(uids) {
			for(UIDTag tag : uids.values()) {
				if(tag.isSource(pn))
					tag.onRestartOrDisconnectSource();
			}
		}
	}

	public int getTotalRunningUIDs() {
		synchronized(runningUIDs) {
			return runningUIDs.size();
		}
	}

	public void addRunningUIDs(Vector<Long> list) {
		synchronized(runningUIDs) {
			list.addAll(runningUIDs.keySet());
		}
	}

	public int getTotalRunningUIDsAlt() {
		synchronized(runningUIDs) {
			return this.runningCHKGetUIDsRT.size() + this.runningCHKPutUIDsRT.size() + this.runningSSKGetUIDsRT.size() +
			this.runningSSKPutUIDsRT.size() + this.runningSSKOfferReplyUIDsRT.size() + this.runningCHKOfferReplyUIDsRT.size() +
			this.runningCHKGetUIDsBulk.size() + this.runningCHKPutUIDsBulk.size() + this.runningSSKGetUIDsBulk.size() +
			this.runningSSKPutUIDsBulk.size() + this.runningSSKOfferReplyUIDsBulk.size() + this.runningCHKOfferReplyUIDsBulk.size();
		}
	}
	
	public int getNumSSKRequests() {
		int total = 0;
		synchronized(runningSSKGetUIDsBulk) {
			total += runningSSKGetUIDsBulk.size();
		}
		synchronized(runningSSKGetUIDsRT) {
			total += runningSSKGetUIDsRT.size();
		}
		synchronized(runningLocalSSKGetUIDsBulk) {
			total += runningLocalSSKGetUIDsBulk.size();
		}
		synchronized(runningLocalSSKGetUIDsRT) {
			total += runningLocalSSKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumCHKRequests() {
		int total = 0;
		synchronized(runningCHKGetUIDsBulk) {
			total += runningCHKGetUIDsBulk.size();
		}
		synchronized(runningCHKGetUIDsRT) {
			total += runningCHKGetUIDsRT.size();
		}
		synchronized(runningLocalCHKGetUIDsBulk) {
			total += runningLocalCHKGetUIDsBulk.size();
		}
		synchronized(runningLocalCHKGetUIDsRT) {
			total += runningLocalCHKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumSSKInserts() {
		int total = 0;
		synchronized(runningSSKPutUIDsBulk) {
			total += runningSSKPutUIDsBulk.size();
		}
		synchronized(runningSSKPutUIDsRT) {
			total += runningSSKPutUIDsRT.size();
		}
		synchronized(runningLocalSSKPutUIDsBulk) {
			total += runningLocalSSKPutUIDsBulk.size();
		}
		synchronized(runningLocalSSKPutUIDsRT) {
			total += runningLocalSSKPutUIDsRT.size();
		}
		return total;
	}

	public int getNumCHKInserts() {
		int total = 0;
		synchronized(runningCHKPutUIDsBulk) {
			total += runningCHKPutUIDsBulk.size();
		}
		synchronized(runningCHKPutUIDsRT) {
			total += runningCHKPutUIDsRT.size();
		}
		synchronized(runningLocalCHKPutUIDsBulk) {
			total += runningLocalCHKPutUIDsBulk.size();
		}
		synchronized(runningLocalCHKPutUIDsRT) {
			total += runningLocalCHKPutUIDsRT.size();
		}
		return total;
	}

	public int getNumLocalSSKRequests() {
		int total = 0;
		synchronized(runningLocalSSKGetUIDsBulk) {
			total += runningLocalSSKGetUIDsBulk.size();
		}
		synchronized(runningLocalSSKGetUIDsRT) {
			total += runningLocalSSKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumLocalCHKRequests() {
		int total = 0;
		synchronized(runningLocalCHKGetUIDsBulk) {
			total += runningLocalCHKGetUIDsBulk.size();
		}
		synchronized(runningLocalCHKGetUIDsRT) {
			total += runningLocalCHKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumRemoteCHKRequests() {
		int total = 0;
		synchronized(runningCHKGetUIDsBulk) {
			total += runningCHKGetUIDsBulk.size();
		}
		synchronized(runningCHKGetUIDsRT) {
			total += runningCHKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumRemoteSSKRequests() {
		int total = 0;
		synchronized(runningSSKGetUIDsBulk) {
			total += runningSSKGetUIDsBulk.size();
		}
		synchronized(runningSSKGetUIDsRT) {
			total += runningSSKGetUIDsRT.size();
		}
		return total;
	}

	public int getNumRemoteSSKRequests(boolean realTimeFlag) {
		if(realTimeFlag) {
			synchronized(runningSSKGetUIDsRT) {
				return runningSSKGetUIDsRT.size();
			}
		} else {
			synchronized(runningSSKGetUIDsBulk) {
				return runningSSKGetUIDsBulk.size();
			}
		}
	}

	public int getNumLocalCHKInserts() {
		int total = 0;
		synchronized(runningLocalCHKPutUIDsBulk) {
			total += runningLocalCHKPutUIDsBulk.size();
		}
		synchronized(runningLocalCHKPutUIDsRT) {
			total += runningLocalCHKPutUIDsRT.size();
		}
		return total;
	}

	public int getNumLocalSSKInserts() {
		int total = 0;
		synchronized(runningLocalSSKPutUIDsBulk) {
			total += runningLocalSSKPutUIDsBulk.size();
		}
		synchronized(runningLocalSSKPutUIDsRT) {
			total += runningLocalSSKPutUIDsRT.size();
		}
		return total;
	}

	public int getNumRemoteCHKInserts() {
		int total = 0;
		synchronized(runningCHKPutUIDsBulk) {
			total += runningCHKPutUIDsBulk.size();
		}
		synchronized(runningCHKPutUIDsRT) {
			total += runningCHKPutUIDsRT.size();
		}
		return total;
	}

	public int getNumRemoteSSKInserts() {
		int total = 0;
		synchronized(runningSSKPutUIDsRT) {
			total += runningSSKPutUIDsRT.size();
		}
		synchronized(runningSSKPutUIDsBulk) {
			total += runningSSKPutUIDsBulk.size();
		}
		return total;
	}

	public int getNumRemoteCHKRequests(boolean realTimeFlag) {
		return realTimeFlag ? runningCHKGetUIDsRT.size() : runningCHKGetUIDsBulk.size();
	}

	public int getNumLocalSSKInserts(boolean realTimeFlag) {
		return realTimeFlag ? runningLocalSSKPutUIDsRT.size() : runningLocalSSKPutUIDsBulk.size();
	}

	public int getNumLocalCHKInserts(boolean realTimeFlag) {
		return realTimeFlag ? runningLocalCHKPutUIDsRT.size() : runningLocalCHKPutUIDsBulk.size();
	}

	public int getNumLocalCHKRequests(boolean realTimeFlag) {
		return realTimeFlag ? runningLocalCHKGetUIDsRT.size() : runningLocalCHKGetUIDsBulk.size();
	}

	public int getNumLocalSSKRequests(boolean realTimeFlag) {
		return realTimeFlag ? runningLocalSSKGetUIDsRT.size() : runningLocalSSKGetUIDsBulk.size();
	}

	public int getNumRemoteSSKInserts(boolean realTimeFlag) {
		return realTimeFlag ? runningSSKPutUIDsRT.size() : runningSSKPutUIDsBulk.size();
	}

	public int getNumRemoteCHKInserts(boolean realTimeFlag) {
		return realTimeFlag ? runningCHKPutUIDsRT.size() : runningCHKPutUIDsBulk.size();
	}

	public int getNumSSKOfferReplies() {
		int total = 0;
		synchronized(runningSSKOfferReplyUIDsRT) {
			total += runningSSKOfferReplyUIDsRT.size();
		}
		synchronized(runningSSKOfferReplyUIDsBulk) {
			total += runningSSKOfferReplyUIDsBulk.size();
		}
		return total;
	}

	public int getNumCHKOfferReplies() {
		int total = 0;
		synchronized(runningCHKOfferReplyUIDsRT) {
			total += runningCHKOfferReplyUIDsRT.size();
		}
		synchronized(runningCHKOfferReplyUIDsBulk) {
			total += runningCHKOfferReplyUIDsBulk.size();
		}
		return total;
	}

	public int getNumSSKOfferReplies(boolean realTimeFlag) {
		return realTimeFlag ? runningSSKOfferReplyUIDsRT.size() : runningSSKOfferReplyUIDsBulk.size();
	}

	public int getNumCHKOfferReplies(boolean realTimeFlag) {
		return realTimeFlag ? runningCHKOfferReplyUIDsRT.size() : runningCHKOfferReplyUIDsBulk.size();
	}

	/**
	 * Add a transferring RequestSender to our HashMap.
	 */
	public void addTransferringSender(NodeCHK key, RequestSender sender) {
		HashMap<NodeCHK, RequestSender> transferringRequestSenders =
			sender.realTimeFlag ? transferringRequestSendersRT : transferringRequestSendersBulk;
		synchronized(transferringRequestSenders) {
			transferringRequestSenders.put(key, sender);
		}
	}

	void addTransferringRequestHandler(long id) {
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.add(id);
		}
	}

	void removeTransferringRequestHandler(long id) {
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.remove(id);
		}
	}
	
	/**
	 * Remove a sender from the set of currently transferring senders.
	 */
	public void removeTransferringSender(NodeCHK key, RequestSender sender) {
		HashMap<NodeCHK, RequestSender> transferringRequestSenders =
			sender.realTimeFlag ? transferringRequestSendersRT : transferringRequestSendersBulk;
		synchronized(transferringRequestSenders) {
//			RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
//			if(rs != sender) {
//				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
//			}

			// Since there is no request coalescing, we only remove it if it matches,
			// and don't complain if it doesn't.
			if(transferringRequestSenders.get(key) == sender)
				transferringRequestSenders.remove(key);
		}
	}

	public RequestSender getTransferringSender(Key key, boolean realTimeFlag) {
		HashMap<NodeCHK, RequestSender> transferringRequestSenders =
			realTimeFlag ? transferringRequestSendersRT : transferringRequestSendersBulk;
		synchronized(transferringRequestSenders) {
			return transferringRequestSenders.get(key);
		}
	}
	
	public int getNumTransferringRequestSenders() {
		int total = 0;
		synchronized(transferringRequestSendersRT) {
			total += transferringRequestSendersRT.size();
		}
		synchronized(transferringRequestSendersBulk) {
			total += transferringRequestSendersBulk.size();
		}
		return total;
	}

	public int getNumTransferringRequestHandlers() {
		synchronized(transferringRequestHandlers) {
			return transferringRequestHandlers.size();
		}
	}
	
	/**
	 * Has a request completed with this ID recently?
	 */
	public boolean recentlyCompleted(long id) {
		synchronized (recentlyCompletedIDs) {
			return recentlyCompletedIDs.contains(id);
		}
	}

	/**
	 * A request completed (regardless of success).
	 */
	public void completed(long id) {
		Long[] list;
		synchronized (recentlyCompletedIDs) {
			recentlyCompletedIDs.push(id);
			while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
				recentlyCompletedIDs.pop();
			completedBuffer.add(id);
			if(completedBuffer.size() < COMPLETED_THRESHOLD) return;
			list = completedBuffer.toArray(new Long[completedBuffer.size()]);
			completedBuffer.clear();
		}
	}
	
}
