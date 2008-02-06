/**
 * 
 */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.StringArray;

class FailureTableEntry implements TimedOutNodesList {
	
	/** The key */
	Key key; // FIXME should this be stored compressed somehow e.g. just the routing key?
	/** The HTL at which it was requested last time. Any request of higher HTL will be let through. */
	short htl;
	/** Time of creation of this entry */
	long creationTime;
	/** Time we last received a request for the key */
	long receivedTime;
	/** Time we last received a DNF after sending a request for a key */
	long sentTime;
	/** Time at which we can send a request again */
	long timeoutTime;
	/** WeakReference's to PeerNode's who have requested the key */
	WeakReference[] requestorNodes;
	/** Times at which they requested it */
	long[] requestorTimes;
	/** Boot ID when they requested it. We don't send it to restarted nodes, as a 
	 * (weak, but useful if combined with other measures) protection against seizure. */
	long[] requestorBootIDs;
	// FIXME Note that just because a node is in this list doesn't mean it DNFed or RFed.
	// We include *ALL* nodes we routed to here!
	// FIXME also we don't have accurate times for when we routed to them - we only 
	// have the terminal time for the request.
	/** WeakReference's to PeerNode's we have requested it from */
	WeakReference[] requestedNodes;
	/** Their locations when we requested it */
	double[] requestedLocs;
	long[] requestedBootIDs;
	long[] requestedTimes;
	/** Timeouts for each node */
	long[] requestedTimeouts;
	short[] requestedTimeoutHTLs;
	
	static boolean logMINOR;
	
	/** We remember that a node has asked us for a key for up to an hour; after that, we won't offer the key, and
	 * if we receive an offer from that node, we will reject it */
	static final int MAX_TIME_BETWEEN_REQUEST_AND_OFFER = 60 * 60 * 1000;
	
	FailureTableEntry(Key key) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long now = System.currentTimeMillis();
		creationTime = now;
		receivedTime = -1;
		sentTime = -1;
		requestorNodes = new WeakReference[0];
		requestorTimes = new long[0];
		requestorBootIDs = new long[0];
		requestedNodes = new WeakReference[0];
		requestedLocs = new double[0];
		requestedBootIDs = new long[0];
		requestedTimes = new long[0];
		requestedTimeouts = new long[0];
		requestedTimeoutHTLs = new short[0];
	}
	
	FailureTableEntry(Key key2, short htl2, PeerNode[] requestors, PeerNode[] requested) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long now = System.currentTimeMillis();
		this.key = key2;
		this.htl = htl2;
		creationTime = now;
		receivedTime = now;
		sentTime = now;
		if(requestors != null) {
			requestorNodes = new WeakReference[requestors.length];
			requestorTimes = new long[requestors.length];
			requestorBootIDs = new long[requestors.length];
			for(int i=0;i<requestorNodes.length;i++) {
				requestorNodes[i] = requestors[i].myRef;
				requestorTimes[i] = now;
				requestorBootIDs[i] = requestors[i].getBootID();
			}
		} else {
			requestorNodes = new WeakReference[0];
			requestorTimes = new long[0];
			requestorBootIDs = new long[0];
		}
		if(requested != null) {
			requestedNodes = new WeakReference[requested.length];
			requestedLocs = new double[requested.length];
			requestedBootIDs = new long[requested.length];
			requestedTimes = new long[requested.length];
			requestedTimeouts = new long[requested.length];
			requestedTimeoutHTLs = new short[requested.length];
			for(int i=0;i<requestedNodes.length;i++) {
				requestedNodes[i] = requested[i].myRef;
				requestedLocs[i] = requested[i].getLocation();
				requestedBootIDs[i] = requested[i].getBootID();
				requestedTimes[i] = now;
				requestedTimeouts[i] = -1;
				requestedTimeoutHTLs[i] = (short) -1;
			}
		} else {
			requestedNodes = new WeakReference[0];
			requestedLocs = new double[0];
			requestedBootIDs = new long[0];
			requestedTimes = new long[0];
			requestedTimeouts = new long[0];
			requestedTimeoutHTLs = new short[0];
		}
	}
	
	/**
	 * Called when there is a failure which could cause a block to be added: Either a DataNotFound, or a
	 * RecentlyFailed.
	 * @param htl2
	 * @param requestors
	 * @param requestedFrom
	 */
	public void onFailure(short htl2, PeerNode[] requestors, PeerNode[] requestedFrom, int timeout, long now) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+htl2+",requestors="+StringArray.toString(requestors)+",requestedFrom="+StringArray.toString(requestedFrom)+",timeout="+timeout);
		synchronized(this) {
			long newTimeoutTime = now + timeout;
			if(now > timeoutTime /* has expired */ && newTimeoutTime > timeoutTime) {
				htl = htl2;
				timeoutTime = newTimeoutTime;
			}
			if(requestors != null) {
				for(int i=0;i<requestors.length;i++)
					addRequestor(requestors[i], now);
			}
			if(requestedFrom != null) {
				for(int i=0;i<requestedFrom.length;i++)
					addRequestedFrom(requestedFrom[i], now);
			}
		}
	}
	
	public void failedTo(PeerNode routedTo, int timeout, long now, short htl) {
		if(logMINOR) {
			Logger.minor(this, "Failed sending request to "+routedTo.shortToString()+" : timeout "+timeout);
			int idx = addRequestedFrom(routedTo, now);
			long curTimeoutTime = requestedTimeouts[idx];
			long newTimeoutTime = now +  timeout;
			// FIXME htl???
			if(now > curTimeoutTime /* has expired */ && newTimeoutTime > curTimeoutTime) {
				requestedTimeouts[idx] = newTimeoutTime;
				requestedTimeoutHTLs[idx] = htl;
			}
		}
	}

	// These are rather low level, in an attempt to absolutely minimize memory usage...
	// The two methods have almost identical code/logic.
	// Dunno if there's a more elegant way of dealing with this which doesn't significantly increase
	// per entry byte cost.
	// Note also this will generate some churn...
	
	synchronized int addRequestor(PeerNode requestor, long now) {
		if(logMINOR) Logger.minor(this, "Adding requestors: "+requestor+" at "+now);
		receivedTime = now;
		boolean includedAlready = false;
		int nulls = 0;
		int ret = -1;
		for(int i=0;i<requestorNodes.length;i++) {
			PeerNode got = requestorNodes[i] == null ? null : (PeerNode) requestorNodes[i].get();
			// No longer subscribed if they have rebooted, or expired
			if(got == requestor) {
				// Update existing entry
				includedAlready = true;
				requestorTimes[i] = now;
				requestorBootIDs[i] = requestor.getBootID();
				ret = i;
				break;
			} else if(got != null && got.getBootID() != requestorBootIDs[i] ||
					now - requestorTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
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
					return i;
				}
			}
		}
		WeakReference[] newRequestorNodes = new WeakReference[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorTimes = new long[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorBootIDs = new long[requestorNodes.length+notIncluded-nulls];
		int toIndex = 0;
		
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			PeerNode pn = (PeerNode) (ref == null ? null : ref.get());
			if(pn == null) continue;
			if(pn == requestor) ret = i;
			newRequestorNodes[toIndex] = requestorNodes[i];
			newRequestorTimes[toIndex] = requestorTimes[i];
			newRequestorBootIDs[toIndex] = requestorBootIDs[i];
			toIndex++;
		}
		
		if(!includedAlready) {
			newRequestorNodes[toIndex] = requestor.myRef;
			newRequestorTimes[toIndex] = now;
			newRequestorBootIDs[toIndex] = requestor.getBootID();
			ret = toIndex;
			toIndex++;
		}
		
		for(int i=toIndex;i<newRequestorNodes.length;i++) newRequestorNodes[i] = null;
		if(toIndex > newRequestorNodes.length + 2) {
			WeakReference[] newNewRequestorNodes = new WeakReference[toIndex];
			long[] newNewRequestorTimes = new long[toIndex];
			long[] newNewRequestorBootIDs = new long[toIndex];
			System.arraycopy(newRequestorNodes, 0, newNewRequestorNodes, 0, toIndex);
			System.arraycopy(newRequestorTimes, 0, newNewRequestorTimes, 0, toIndex);
			System.arraycopy(newRequestorBootIDs, 0, newNewRequestorBootIDs, 0, toIndex);
			newRequestorNodes = newNewRequestorNodes;
			newRequestorTimes = newNewRequestorTimes;
			newRequestorBootIDs = newNewRequestorBootIDs;
		}
		requestorNodes = newRequestorNodes;
		requestorTimes = newRequestorTimes;
		requestorBootIDs = newRequestorBootIDs;
		
		return ret;
	}

	private synchronized int addRequestedFrom(PeerNode requestedFrom, long now) {
		if(logMINOR) Logger.minor(this, "Adding requested from: "+requestedFrom+" at "+now);
		sentTime = now;
		boolean includedAlready = false;
		int nulls = 0;
		int ret = -1;
		for(int i=0;i<requestedNodes.length;i++) {
			PeerNode got = requestedNodes[i] == null ? null : (PeerNode) requestedNodes[i].get();
			if(got == requestedFrom) {
				// Update existing entry
				includedAlready = true;
				requestedLocs[i] = requestedFrom.getLocation();
				requestedBootIDs[i] = requestedFrom.getBootID();
				requestedTimes[i] = now;
				ret = i;
				break;
			} else if(got == null)
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
					requestedTimes[i] = now;
					requestedTimeouts[i] = -1;
					requestedTimeoutHTLs[i] = (short) -1;
					return ret;
				}
			}
		}
		WeakReference[] newRequestedNodes = new WeakReference[requestedNodes.length+notIncluded-nulls];
		double[] newRequestedLocs = new double[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedBootIDs = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimes = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimeouts = new long[requestedNodes.length+notIncluded-nulls];
		short[] newRequestedTimeoutHTLs = new short[requestedNodes.length+notIncluded-nulls];

		int toIndex = 0;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			PeerNode pn = (PeerNode) (ref == null ? null : ref.get());
			if(pn == null) continue;
			if(pn == requestedFrom) ret = i;
			newRequestedNodes[toIndex] = requestedNodes[i];
			newRequestedTimes[toIndex] = requestedTimes[i];
			newRequestedBootIDs[toIndex] = requestedBootIDs[i];
			newRequestedLocs[toIndex] = requestedLocs[i];
			newRequestedTimeouts[toIndex] = requestedTimeouts[i];
			newRequestedTimeoutHTLs[toIndex] = requestedTimeoutHTLs[i];
			toIndex++;
		}
		
		if(!includedAlready) {
			newRequestedNodes[toIndex] = requestedFrom.myRef;
			newRequestedTimes[toIndex] = now;
			newRequestedBootIDs[toIndex] = requestedFrom.getBootID();
			newRequestedLocs[toIndex] = requestedFrom.getLocation();
			newRequestedTimeouts[toIndex] = -1;
			newRequestedTimeoutHTLs[toIndex] = (short) -1;
			toIndex++;
		}
		
		for(int i=toIndex;i<newRequestedNodes.length;i++) newRequestedNodes[i] = null;
		if(toIndex > newRequestedNodes.length + 2) {
			WeakReference[] newNewRequestedNodes = new WeakReference[toIndex];
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

	public synchronized double bestLiveLocDiff() {
		double bestDiff = 2.0;
		for(int i=0;i<requestedNodes.length;i++) {
			if(requestedNodes[i] == null) continue;
			PeerNode pn = (PeerNode) requestedNodes[i].get();
			if(pn == null) continue;
			if(!(pn.isRoutable() && pn.isRoutingBackedOff())) continue;
			double diff = Location.distance(key.toNormalizedDouble(), requestedLocs[i]);
			if(diff < bestDiff) bestDiff = diff;
		}
		return bestDiff;
	}

	/** Offer this key to all the nodes that have requested it, and all the nodes it has been requested from.
	 * Called after a) the data has been stored, and b) this entry has been removed from the FT */
	public void offer() {
		HashSet set = new HashSet();
		if(logMINOR) Logger.minor(this, "Sending offers to nodes which requested the key from us:");
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestorBootIDs[i]) continue;
			if(!set.add(pn)) {
				Logger.error(this, "Node is in requestorNodes twice: "+pn);
			}
			pn.offer(key);
		}
		if(logMINOR) Logger.minor(this, "Sending offers to nodes which we sent the key to:");
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestedBootIDs[i]) continue;
			if(set.contains(pn)) continue;
			pn.offer(key);
		}
	}

	/**
	 * Has any node asked for this key?
	 */
	public synchronized boolean othersWant(PeerNode peer) {
		boolean anyValid = false;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
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
			requestorNodes = new WeakReference[0];
			requestorTimes = requestorBootIDs = new long[0];
		}
		return anyValid;
	}

	/**
	 * Has this peer asked us for the key?
	 */
	public synchronized boolean askedByPeer(PeerNode peer, long now) {
		boolean anyValid = false;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) {
				requestorNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestorBootIDs[i]) {
				requestorNodes[i] = null;
				continue;
			}
			if(now - requestorTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) return true;
			anyValid = true;
			if(pn == peer) return true;
		}
		if(!anyValid) {
			requestorNodes = new WeakReference[0];
			requestorTimes = requestorBootIDs = new long[0];
		}
		return false;
	}

	/**
	 * Have we asked this peer for the key?
	 */
	public synchronized boolean askedFromPeer(PeerNode peer, long now) {
		boolean anyValid = false;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) {
				requestedNodes[i] = null;
				continue;
			}
			long bootID = pn.getBootID();
			if(bootID != requestedBootIDs[i]) {
				requestedNodes[i] = null;
				continue;
			}
			if(now - requestedTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) return true;
			anyValid = true;
			if(pn == peer) return true;
		}
		if(!anyValid) {
			requestedNodes = new WeakReference[0];
			requestedTimes = requestedBootIDs = requestedTimeouts = new long[0];
			requestedTimeoutHTLs = new short[0];
		}
		return false;
	}

	public synchronized boolean isEmpty(long now) {
		if(timeoutTime > now) return false;
		if(requestedNodes.length > 0) return false;
		if(requestorNodes.length > 0) return false;
		return true;
	}

	public synchronized long getTimeoutTime(PeerNode peer) {
		for(int i=0;i<requestedNodes.length;i++) {
			if(requestedNodes[i].get() == peer) {
				return requestedTimeouts[i];
			}
		}
		return -1; // not timed out
	}

}