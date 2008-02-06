/**
 * 
 */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.StringArray;

class FailureTableEntry {
	
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
	
	static boolean logMINOR;
	
	/** We remember that a node has asked us for a key for up to an hour; after that, we won't offer the key, and
	 * if we receive an offer from that node, we will reject it */
	static final int MAX_TIME_BETWEEN_REQUEST_AND_OFFER = 60 * 60 * 1000;
	
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
			for(int i=0;i<requestedNodes.length;i++) {
				requestedNodes[i] = requested[i].myRef;
				requestedLocs[i] = requested[i].getLocation();
				requestedBootIDs[i] = requested[i].getBootID();
				requestedTimes[i] = now;
			}
		} else {
			requestedNodes = new WeakReference[0];
			requestedLocs = new double[0];
			requestedBootIDs = new long[0];
			requestedTimes = new long[0];
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

	// These are rather low level, in an attempt to absolutely minimize memory usage...
	// The two methods have almost identical code/logic.
	// Dunno if there's a more elegant way of dealing with this which doesn't significantly increase
	// per entry byte cost.
	// Note also this will generate some churn...
	
	synchronized int addRequestor(PeerNode requestor, long now) {
		if(logMINOR) Logger.minor(this, "Adding requestors: "+requestor+" at "+now);
		receivedTime = now;
		boolean requestorIncluded = false;
		int nulls = 0;
		PeerNode req = requestor;
		int ret = -1;
		for(int j=0;j<requestorNodes.length;j++) {
			PeerNode got = requestorNodes[j] == null ? null : (PeerNode) requestorNodes[j].get();
			// No longer subscribed if they have rebooted, or expired
			if(got != null && got.getBootID() != requestorBootIDs[j] ||
					now - requestorTimes[j] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
				requestorNodes[j] = null;
				got = null;
			}
			if(got == null)
				nulls++;
			if(got == req) {
				// Update existing entry
				requestorIncluded = true;
				requestorTimes[j] = now;
				requestorBootIDs[j] = req.getBootID();
				ret = j;
				break;
			}
		}
		if(nulls == 0 && requestorIncluded) return ret;
		int notIncluded = requestorIncluded ? 0 : 1;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(nulls == 1 && !requestorIncluded) {
			// Nice special case
			for(int i=0;i<requestorNodes.length;i++) {
				if(requestorNodes[i] == null || requestorNodes[i].get() == null) {
					PeerNode pn = requestor;
					requestorNodes[i] = pn.myRef;
					requestorTimes[i] = now;
					requestorBootIDs[i] = pn.getBootID();
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
		
		if(!requestorIncluded) {
		
			PeerNode pn = requestor;
			if(pn != null) {
				newRequestorNodes[toIndex] = pn.myRef;
				newRequestorTimes[toIndex] = now;
				newRequestorBootIDs[toIndex] = pn.getBootID();
				ret = toIndex;
				toIndex++;
			}
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
		boolean requestorIncluded = false;
		int nulls = 0;
		PeerNode req = requestedFrom;
		int ret = -1;
		for(int j=0;j<requestedNodes.length;j++) {
			PeerNode got = requestedNodes[j] == null ? null : (PeerNode) requestedNodes[j].get();
			if(got == null)
				nulls++;
			if(got == req) {
				// Update existing entry
				requestorIncluded = true;
				requestedLocs[j] = req.getLocation();
				requestedBootIDs[j] = req.getBootID();
				requestedTimes[j] = now;
				ret = j;
				break;
			}
		}
		if(requestorIncluded && nulls == 0) return ret;
		int notIncluded = requestorIncluded ? 0 : 1;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(nulls == 1 && !requestorIncluded) {
			// Nice special case
			for(int i=0;i<requestedNodes.length;i++) {
				if(requestedNodes[i] == null || requestedNodes[i].get() == null) {
					PeerNode pn = requestedFrom;
					requestedNodes[i] = pn.myRef;
					requestedLocs[i] = pn.getLocation();
					requestedTimes[i] = now;
					return ret;
				}
			}
		}
		WeakReference[] newRequestedNodes = new WeakReference[requestedNodes.length+notIncluded-nulls];
		double[] newRequestedLocs = new double[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedBootIDs = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimes = new long[requestedNodes.length+notIncluded-nulls];

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
			toIndex++;
		}
		
		if(!requestorIncluded) {
			PeerNode pn = requestedFrom;
			if(pn != null) {
				newRequestedNodes[toIndex] = pn.myRef;
				newRequestedTimes[toIndex] = now;
				newRequestedBootIDs[toIndex] = pn.getBootID();
				newRequestedLocs[toIndex] = pn.getLocation();
				toIndex++;
			}
		}
		
		for(int i=toIndex;i<newRequestedNodes.length;i++) newRequestedNodes[i] = null;
		if(toIndex > newRequestedNodes.length + 2) {
			WeakReference[] newNewRequestedNodes = new WeakReference[toIndex];
			double[] newNewRequestedLocs = new double[toIndex];
			long[] newNewRequestedBootIDs = new long[toIndex];
			long[] newNewRequestedTimes = new long[toIndex];
			System.arraycopy(newRequestedNodes, 0, newNewRequestedNodes, 0, toIndex);
			System.arraycopy(newRequestedLocs, 0, newNewRequestedLocs, 0, toIndex);
			System.arraycopy(newRequestedBootIDs, 0, newNewRequestedBootIDs, 0, toIndex);
			System.arraycopy(newRequestedTimes, 0, newNewRequestedTimes, 0, toIndex);
			newRequestedNodes = newNewRequestedNodes;
			newRequestedLocs = newNewRequestedLocs;
			newRequestedBootIDs = newNewRequestedBootIDs;
			newRequestedTimes = newNewRequestedTimes;
		}
		requestedNodes = newRequestedNodes;
		requestedLocs = newRequestedLocs;
		requestedBootIDs = newRequestedBootIDs;
		requestedTimes = newRequestedTimes;
		
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
			requestedTimes = requestedBootIDs = new long[0];
		}
		return false;
	}

	public synchronized boolean isEmpty(long now) {
		if(timeoutTime > now) return false;
		if(requestedNodes.length > 0) return false;
		if(requestorNodes.length > 0) return false;
		return true;
	}

}