package freenet.node;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import freenet.keys.Key;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/** Tracks recent requests for a specific key. If we have recently routed to a specific 
 * node, and failed, we should not route to it again, unless it is at a higher HTL. 
 * Different failures cause different timeouts. Similarly we track the nodes that have
 * requested the key, because for both sets of nodes, when we find the data we offer them
 * it; this greatly improves latency and efficiency for polling-based tools. For nodes
 * we have routed to, we keep up to HTL separate entries; for nodes we have received 
 * requests from, we keep only one entry.
 * 
 * SECURITY: All this could be a security risk if not regularly cleared - which it is,
 * of course: We forget about either kind of node after a fixed period, in 
 * cleanupRequested(), which the FailureTable calls regularly. Against a near-omnipotent 
 * attacker able to compromise nodes at will of course it is still a security risk to 
 * track anything but we have bigger problems at that level.
 * @author toad
 */
class FailureTableEntry implements TimedOutNodesList {
	
	/** The key */
	final Key key; // FIXME should this be stored compressed somehow e.g. just the routing key?
	/** Time of creation of this entry */
	long creationTime;
	/** Time we last received a request for the key */
	long receivedTime;
	/** Time we last received a DNF after sending a request for a key */
	long sentTime;
	/** WeakReference's to PeerNode's who have requested the key */
	WeakReference<PeerNode>[] requestorNodes;
	/** Times at which they requested it */
	long[] requestorTimes;
	/** Boot ID when they requested it. We don't send it to restarted nodes, as a 
	 * (weak, but useful if combined with other measures) protection against seizure. */
	long[] requestorBootIDs;
	short[] requestorHTLs;
	
	// FIXME Note that just because a node is in this list doesn't mean it DNFed or RFed.
	// We include *ALL* nodes we routed to here!
	/** WeakReference's to PeerNode's we have requested it from */
	WeakReference<PeerNode>[] requestedNodes;
	/** Their locations when we requested it. This may be needed in the future to
	 * determine whether to let a request through that we would otherwise have
	 * failed with RecentlyFailed, because the node we would route it to is closer
	 * to the target than any we've routed to in the past. */
	double[] requestedLocs;
	long[] requestedBootIDs;
	long[] requestedTimes;
	/** Timeouts for each node */
	long[] requestedTimeouts;
	short[] requestedTimeoutHTLs;
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/** We remember that a node has asked us for a key for up to an hour; after that, we won't offer the key, and
	 * if we receive an offer from that node, we will reject it */
	static final int MAX_TIME_BETWEEN_REQUEST_AND_OFFER = 60 * 60 * 1000;
	
        public static final long[] EMPTY_LONG_ARRAY = new long[0];
        public static final short[] EMPTY_SHORT_ARRAY = new short[0];
        public static final double[] EMPTY_DOUBLE_ARRAY = new double[0];
        @SuppressWarnings("unchecked")
        public static final WeakReference<PeerNode>[] EMPTY_WEAK_REFERENCE = new WeakReference[0];
        
	FailureTableEntry(Key key) {
		this.key = key.archivalCopy();
		if(key == null) throw new NullPointerException();
		long now = System.currentTimeMillis();
		creationTime = now;
		receivedTime = -1;
		sentTime = -1;
		requestorNodes = EMPTY_WEAK_REFERENCE;
		requestorTimes = EMPTY_LONG_ARRAY;
		requestorBootIDs = EMPTY_LONG_ARRAY;
		requestorHTLs = EMPTY_SHORT_ARRAY;
		requestedNodes = EMPTY_WEAK_REFERENCE;
		requestedLocs = EMPTY_DOUBLE_ARRAY;
		requestedBootIDs = EMPTY_LONG_ARRAY;
		requestedTimes = EMPTY_LONG_ARRAY;
		requestedTimeouts = EMPTY_LONG_ARRAY;
		requestedTimeoutHTLs = EMPTY_SHORT_ARRAY;
	}
	
	public synchronized void failedTo(PeerNode routedTo, int timeout, long now, short htl) {
		if(logMINOR) {
			Logger.minor(this, "Failed sending request to "+routedTo.shortToString()+" : timeout "+timeout);
		}
		int idx = addRequestedFrom(routedTo, htl, now);
		long curTimeoutTime = requestedTimeouts[idx];
		long newTimeoutTime = now +  timeout;
		// FIXME htl???
		if(newTimeoutTime > curTimeoutTime) {
			requestedTimeouts[idx] = newTimeoutTime;
			requestedTimeoutHTLs[idx] = htl;
		}
	}

	// These are rather low level, in an attempt to absolutely minimize memory usage...
	// The two methods have almost identical code/logic.
	// Dunno if there's a more elegant way of dealing with this which doesn't significantly increase
	// per entry byte cost.
	// Note also this will generate some churn...
	
	synchronized int addRequestor(PeerNode requestor, long now, short origHTL) {
		if(logMINOR) Logger.minor(this, "Adding requestors: "+requestor+" at "+now);
		receivedTime = now;
		boolean includedAlready = false;
		int nulls = 0;
		int ret = -1;
		for(int i=0;i<requestorNodes.length;i++) {
			PeerNode got = requestorNodes[i] == null ? null : requestorNodes[i].get();
			// No longer subscribed if they have rebooted, or expired
			if(got == requestor) {
				// Update existing entry
				includedAlready = true;
				requestorTimes[i] = now;
				requestorBootIDs[i] = requestor.getBootID();
				requestorHTLs[i] = origHTL;
				ret = i;
				break;
			} else if(got != null && 
					(got.getBootID() != requestorBootIDs[i] || now - requestorTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER)) {
				requestorNodes[i] = null;
				got = null;
			}
			if(got == null)
				nulls++;
		}
		if(nulls == 0 && includedAlready) return ret;
		int notIncluded = includedAlready ? 0 : 1;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(nulls == 1 && !includedAlready) {
			// Nice special case
			for(int i=0;i<requestorNodes.length;i++) {
				if(requestorNodes[i] == null || requestorNodes[i].get() == null) {
					requestorNodes[i] = requestor.myRef;
					requestorTimes[i] = now;
					requestorBootIDs[i] = requestor.getBootID();
					requestorHTLs[i] = origHTL;
					return i;
				}
			}
		}
        @SuppressWarnings("unchecked")
		WeakReference<PeerNode>[] newRequestorNodes = new WeakReference[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorTimes = new long[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorBootIDs = new long[requestorNodes.length+notIncluded-nulls];
		short[] newRequestorHTLs = new short[requestorNodes.length+notIncluded-nulls];
		int toIndex = 0;
		
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			PeerNode pn = ref == null ? null : ref.get();
			if(pn == null) continue;
			if(pn == requestor) ret = toIndex;
			newRequestorNodes[toIndex] = requestorNodes[i];
			newRequestorTimes[toIndex] = requestorTimes[i];
			newRequestorBootIDs[toIndex] = requestorBootIDs[i];
			newRequestorHTLs[toIndex] = requestorHTLs[i];
			toIndex++;
		}
		
		if(!includedAlready) {
			newRequestorNodes[toIndex] = requestor.myRef;
			newRequestorTimes[toIndex] = now;
			newRequestorBootIDs[toIndex] = requestor.getBootID();
			newRequestorHTLs[toIndex] = origHTL;
			ret = toIndex;
			toIndex++;
		}
		
		for(int i=toIndex;i<newRequestorNodes.length;i++) newRequestorNodes[i] = null;
		if(toIndex > newRequestorNodes.length + 2) {
			@SuppressWarnings("unchecked")
			WeakReference<PeerNode>[] newNewRequestorNodes = new WeakReference[toIndex];
			long[] newNewRequestorTimes = new long[toIndex];
			long[] newNewRequestorBootIDs = new long[toIndex];
			short[] newNewRequestorHTLs = new short[toIndex];
			System.arraycopy(newRequestorNodes, 0, newNewRequestorNodes, 0, toIndex);
			System.arraycopy(newRequestorTimes, 0, newNewRequestorTimes, 0, toIndex);
			System.arraycopy(newRequestorBootIDs, 0, newNewRequestorBootIDs, 0, toIndex);
			System.arraycopy(newRequestorHTLs, 0, newNewRequestorHTLs, 0, toIndex);
			newRequestorNodes = newNewRequestorNodes;
			newRequestorTimes = newNewRequestorTimes;
			newRequestorBootIDs = newNewRequestorBootIDs;
			newRequestorHTLs = newNewRequestorHTLs;
		}
		requestorNodes = newRequestorNodes;
		requestorTimes = newRequestorTimes;
		requestorBootIDs = newRequestorBootIDs;
		requestorHTLs = newRequestorHTLs;
		
		return ret;
	}

	/** Add a requested from entry to the node. If there already is one reuse it but only
	 * if the HTL matches. Return the index so we can update timeouts etc.
	 * @param requestedFrom The node we have routed the request to.
	 * @param htl The HTL at which the request was sent.
	 * @param now The current time.
	 * @return The index of the new or old entry.
	 */
	private synchronized int addRequestedFrom(PeerNode requestedFrom, short htl, long now) {
		if(logMINOR) Logger.minor(this, "Adding requested from: "+requestedFrom+" at "+now);
		sentTime = now;
		boolean includedAlready = false;
		int nulls = 0;
		int ret = -1;
		for(int i=0;i<requestedNodes.length;i++) {
			PeerNode got = requestedNodes[i] == null ? null : requestedNodes[i].get();
			if(got == requestedFrom && (requestedTimeouts[i] == -1 || requestedTimeoutHTLs[i] == htl)) {
				includedAlready = true;
				requestedLocs[i] = requestedFrom.getLocation();
				requestedBootIDs[i] = requestedFrom.getBootID();
				requestedTimes[i] = now;
				ret = i;
			} else if(got != null && 
					(got.getBootID() != requestedBootIDs[i] || now - requestedTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER)) {
				requestedNodes[i] = null;
				got = null;
			}
			if(got == null)
				nulls++;
		}
		if(includedAlready && nulls == 0) return ret;
		int notIncluded = includedAlready ? 0 : 1;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(nulls == 1 && !includedAlready) {
			// Nice special case
			for(int i=0;i<requestedNodes.length;i++) {
				if(requestedNodes[i] == null || requestedNodes[i].get() == null) {
					requestedNodes[i] = requestedFrom.myRef;
					requestedLocs[i] = requestedFrom.getLocation();
					requestedBootIDs[i] = requestedFrom.getBootID();
					requestedTimes[i] = now;
					requestedTimeouts[i] = -1;
					requestedTimeoutHTLs[i] = (short) -1;
					return i;
				}
			}
		}
		@SuppressWarnings("unchecked")
		WeakReference<PeerNode>[] newRequestedNodes = new WeakReference[requestedNodes.length+notIncluded-nulls];
		double[] newRequestedLocs = new double[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedBootIDs = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimes = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimeouts = new long[requestedNodes.length+notIncluded-nulls];
		short[] newRequestedTimeoutHTLs = new short[requestedNodes.length+notIncluded-nulls];

		int toIndex = 0;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference<PeerNode> ref = requestedNodes[i];
			PeerNode pn = ref == null ? null : ref.get();
			if(pn == null) continue;
			if(pn == requestedFrom) ret = toIndex;
			newRequestedNodes[toIndex] = requestedNodes[i];
			newRequestedTimes[toIndex] = requestedTimes[i];
			newRequestedBootIDs[toIndex] = requestedBootIDs[i];
			newRequestedLocs[toIndex] = requestedLocs[i];
			newRequestedTimeouts[toIndex] = requestedTimeouts[i];
			newRequestedTimeoutHTLs[toIndex] = requestedTimeoutHTLs[i];
			toIndex++;
		}
		
		if(!includedAlready) {
			ret = toIndex;
			newRequestedNodes[toIndex] = requestedFrom.myRef;
			newRequestedTimes[toIndex] = now;
			newRequestedBootIDs[toIndex] = requestedFrom.getBootID();
			newRequestedLocs[toIndex] = requestedFrom.getLocation();
			newRequestedTimeouts[toIndex] = -1;
			newRequestedTimeoutHTLs[toIndex] = (short) -1;
			ret = toIndex;
			toIndex++;
		}
		
		for(int i=toIndex;i<newRequestedNodes.length;i++) newRequestedNodes[i] = null;
		if(toIndex > newRequestedNodes.length + 2) {
	        @SuppressWarnings("unchecked")
			WeakReference<PeerNode>[] newNewRequestedNodes = new WeakReference[toIndex];
			double[] newNewRequestedLocs = new double[toIndex];
			long[] newNewRequestedBootIDs = new long[toIndex];
			long[] newNewRequestedTimes = new long[toIndex];
			long[] newNewRequestedTimeouts = new long[toIndex];
			short[] newNewRequestedTimeoutHTLs = new short[toIndex];
			System.arraycopy(newRequestedNodes, 0, newNewRequestedNodes, 0, toIndex);
			System.arraycopy(newRequestedLocs, 0, newNewRequestedLocs, 0, toIndex);
			System.arraycopy(newRequestedBootIDs, 0, newNewRequestedBootIDs, 0, toIndex);
			System.arraycopy(newRequestedTimes, 0, newNewRequestedTimes, 0, toIndex);
			System.arraycopy(newRequestedTimeouts, 0, newNewRequestedTimeouts, 0, toIndex);
			System.arraycopy(newRequestedTimeoutHTLs, 0, newNewRequestedTimeoutHTLs, 0, toIndex);
			newRequestedNodes = newNewRequestedNodes;
			newRequestedLocs = newNewRequestedLocs;
			newRequestedBootIDs = newNewRequestedBootIDs;
			newRequestedTimes = newNewRequestedTimes;
			newRequestedTimeouts = newNewRequestedTimeouts;
			newRequestedTimeoutHTLs = newNewRequestedTimeoutHTLs;
		}
		requestedNodes = newRequestedNodes;
		requestedLocs = newRequestedLocs;
		requestedBootIDs = newRequestedBootIDs;
		requestedTimes = newRequestedTimes;
		requestedTimeouts = newRequestedTimeouts;
		requestedTimeoutHTLs = newRequestedTimeoutHTLs;
		
		return ret;
	}

	/** Offer this key to all the nodes that have requested it, and all the nodes it has been requested from.
	 * Called after a) the data has been stored, and b) this entry has been removed from the FT */
	public synchronized void offer() {
		HashSet<PeerNode> set = new HashSet<PeerNode>();
		if(logMINOR) Logger.minor(this, "Sending offers to nodes which requested the key from us: ("+requestorNodes.length+")");
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestorBootIDs[i]) continue;
			if(!set.add(pn)) {
				Logger.error(this, "Node is in requestorNodes twice: "+pn);
			}
			if(logMINOR) Logger.minor(this, "Offering to "+pn);
			pn.offer(key);
		}
		if(logMINOR) Logger.minor(this, "Sending offers to nodes which we sent the key to: ("+requestedNodes.length+")");
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference<PeerNode> ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestedBootIDs[i]) continue;
			if(!set.add(pn)) continue;
			if(logMINOR) Logger.minor(this, "Offering to "+pn);
			pn.offer(key);
		}
	}

	/**
	 * Has any node asked for this key?
	 */
	public synchronized boolean othersWant(PeerNode peer) {
		boolean anyValid = false;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) {
				requestorNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestorBootIDs[i]) {
				requestorNodes[i] = null;
				continue;
			}
			anyValid = true;
		}
		if(!anyValid) {
			requestorNodes = EMPTY_WEAK_REFERENCE;
			requestorTimes = requestorBootIDs = EMPTY_LONG_ARRAY;
			requestorHTLs = EMPTY_SHORT_ARRAY;
		}
		return anyValid;
	}

	/**
	 * Has this peer asked us for the key?
	 */
	public synchronized boolean askedByPeer(PeerNode peer, long now) {
		boolean anyValid = false;
		boolean ret = false;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) {
				requestorNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestorBootIDs[i]) {
				requestorNodes[i] = null;
				continue;
			}
			if(now - requestorTimes[i] < MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
				if(pn == peer) ret = true;
				anyValid = true;
			} 
		}
		if(!anyValid) {
			requestorNodes = EMPTY_WEAK_REFERENCE;
			requestorTimes = requestorBootIDs = EMPTY_LONG_ARRAY;;
			requestorHTLs = EMPTY_SHORT_ARRAY;
		}
		return ret;
	}

	/**
	 * Have we asked this peer for the key?
	 */
	public synchronized boolean askedFromPeer(PeerNode peer, long now) {
		boolean anyValid = false;
		boolean ret = false;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference<PeerNode> ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) {
				requestedNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestedBootIDs[i]) {
				requestedNodes[i] = null;
				continue;
			}
			anyValid = true;
			if(now - requestedTimes[i] < MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
				if(pn == peer) ret = true;
				anyValid = true;
			}
		}
		if(!anyValid) {
			requestedNodes = EMPTY_WEAK_REFERENCE;
			requestedTimes = requestedBootIDs = requestedTimeouts = EMPTY_LONG_ARRAY;
			requestedTimeoutHTLs =EMPTY_SHORT_ARRAY;
		}
		return ret;
	}

	public synchronized boolean isEmpty(long now) {
		if(requestedNodes.length > 0) return false;
		if(requestorNodes.length > 0) return false;
		return true;
	}

	/** Get the timeout time for the given peer, taking HTL into account.
	 * If there was a timeout at HTL 1, and we are now sending a request at
	 * HTL 2, we ignore the timeout. */
	public synchronized long getTimeoutTime(PeerNode peer, short htl, long now) {
		long timeout = -1;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference<PeerNode> ref = requestedNodes[i];
			if(ref != null && ref.get() == peer) {
				if(requestedTimeoutHTLs[i] >= htl) {
					long thisTimeout = requestedTimeouts[i];
					if(thisTimeout > timeout && thisTimeout > now)
						timeout = thisTimeout;
				}
			}
		}
		return timeout;
	}
	
	public synchronized boolean cleanup() {
		long now = System.currentTimeMillis(); // don't pass in as a pass over the whole FT may take a while. get it in the method.
		
		boolean empty = cleanupRequestor(now);
		empty &= cleanupRequested(now);
		return empty;
	}

	private boolean cleanupRequestor(long now) {
		boolean empty = true;
		int x = 0;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) continue;
			long bootID = pn.getBootID();
			if(bootID != requestorBootIDs[i]) continue;
			if(!pn.isConnected()) continue;
			if(now - requestorTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) continue;
			empty = false;
			requestorNodes[x] = requestorNodes[i];
			requestorTimes[x] = requestorTimes[i];
			requestorBootIDs[x] = requestorBootIDs[i];
			requestorHTLs[x] = requestorHTLs[i];
			x++;
		}
		if(x < requestorNodes.length) {
			@SuppressWarnings("unchecked")
			WeakReference<PeerNode>[] newRequestorNodes = new WeakReference[x];
			long[] newRequestorTimes = new long[x];
			long[] newRequestorBootIDs = new long[x];
			short[] newRequestorHTLs = new short[x];
			System.arraycopy(requestorNodes, 0, newRequestorNodes, 0, x);
			System.arraycopy(requestorTimes, 0, newRequestorTimes, 0, x);
			System.arraycopy(requestorBootIDs, 0, newRequestorBootIDs, 0, x);
			System.arraycopy(requestorHTLs, 0, newRequestorHTLs, 0, x);
			requestorNodes = newRequestorNodes;
			requestorTimes = newRequestorTimes;
			requestorBootIDs = newRequestorBootIDs;
			requestorHTLs = newRequestorHTLs;
		}
		
		return empty;
	}
	
	private boolean cleanupRequested(long now) {
		boolean empty = true;
		int x = 0;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference<PeerNode> ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) continue;
			long bootID = pn.getBootID();
			if(bootID != requestedBootIDs[i]) continue;
			if(!pn.isConnected()) continue;
			if(now - requestedTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) continue;
			empty = false;
			requestedNodes[x] = requestedNodes[i];
			requestedTimes[x] = requestedTimes[i];
			requestedBootIDs[x] = requestedBootIDs[i];
			requestedLocs[x] = requestedLocs[i];
			if(now < requestedTimeouts[x]) { 
				requestedTimeouts[x] = requestedTimeouts[i];
				requestedTimeoutHTLs[x] = requestedTimeoutHTLs[i];
			} else {
				requestedTimeouts[x] = -1;
				requestedTimeoutHTLs[x] = (short)-1;
			}
			x++;
		}
		if(x < requestedNodes.length) {
			@SuppressWarnings("unchecked")
			WeakReference<PeerNode>[] newRequestedNodes = new WeakReference[x];
			long[] newRequestedTimes = new long[x];
			long[] newRequestedBootIDs = new long[x];
			double[] newRequestedLocs = new double[x];
			long[] newRequestedTimeouts = new long[x];
			short[] newRequestedTimeoutHTLs = new short[x];
			System.arraycopy(requestedNodes, 0, newRequestedNodes, 0, x);
			System.arraycopy(requestedTimes, 0, newRequestedTimes, 0, x);
			System.arraycopy(requestedBootIDs, 0, newRequestedBootIDs, 0, x);
			System.arraycopy(requestedLocs, 0, newRequestedLocs, 0, x);
			System.arraycopy(requestedTimeouts, 0, newRequestedTimeouts, 0, x);
			System.arraycopy(requestedTimeoutHTLs, 0, newRequestedTimeoutHTLs, 0, x);
			requestedNodes = newRequestedNodes;
			requestedTimes = newRequestedTimes;
			requestedBootIDs = newRequestedBootIDs;
			requestedLocs = newRequestedLocs;
			requestedTimeouts = newRequestedTimeouts;
			requestedTimeoutHTLs = newRequestedTimeoutHTLs;
		}
		return empty;
	}

	public boolean isEmpty() {
		return isEmpty(System.currentTimeMillis());
	}

	public synchronized short minRequestorHTL(short htl) {
		long now = System.currentTimeMillis();
		boolean anyValid = false;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference<PeerNode> ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = ref.get();
			if(pn == null) {
				requestorNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestorBootIDs[i]) {
				requestorNodes[i] = null;
				continue;
			}
			if(now - requestorTimes[i] < MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
				if(requestorHTLs[i] < htl) htl = requestorHTLs[i];
			}
			anyValid = true;
		}
		if(!anyValid) {
			requestorNodes = EMPTY_WEAK_REFERENCE;
			requestorTimes = requestorBootIDs = EMPTY_LONG_ARRAY;;
			requestorHTLs = EMPTY_SHORT_ARRAY;
		}
		return htl;
	}

}
