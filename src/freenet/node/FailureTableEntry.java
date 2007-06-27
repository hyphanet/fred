/**
 * 
 */
package freenet.node;

import java.lang.ref.WeakReference;

import freenet.keys.Key;

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
	/** WeakReference's to PeerNode's we have requested it from */
	WeakReference[] requestedNodes;
	/** Their locations when we requested it */
	double[] requestedLocs;
	long[] requestedBootIDs;
	long[] requestedTimes;
	
	/** We remember that a node has asked us for a key for up to an hour; after that, we won't offer the key, and
	 * if we receive an offer from that node, we will reject it */
	static final int MAX_TIME_BETWEEN_REQUEST_AND_OFFER = 60 * 60 * 1000;
	
	FailureTableEntry(Key key2, short htl2, PeerNode[] requestors, PeerNode requested) {
		long now = System.currentTimeMillis();
		this.key = key2;
		this.htl = htl2;
		creationTime = now;
		receivedTime = now;
		sentTime = now;
		requestorNodes = new WeakReference[requestors.length];
		requestorTimes = new long[requestors.length];
		requestorBootIDs = new long[requestors.length];
		for(int i=0;i<requestorNodes.length;i++) {
			requestorNodes[i] = new WeakReference(requestors[i]);
			requestorTimes[i] = now;
			requestorBootIDs[i] = requestors[i].getBootID();
		}
		requestedNodes = new WeakReference[] { new WeakReference(requested) };
		requestedLocs = new double[] { requested.getLocation().getValue() };
		requestedBootIDs = new long[] { requested.getBootID() };
	}
	
	/**
	 * Called when there is a failure which could cause a block to be added: Either a DataNotFound, or a
	 * RecentlyFailed.
	 * @param htl2
	 * @param requestors
	 * @param requested
	 */
	public void onFailure(short htl2, PeerNode[] requestors, PeerNode requested, int timeout, long now) {
		synchronized(this) {
			long newTimeoutTime = now + timeout;
			if(now > timeoutTime /* has expired */ || newTimeoutTime > timeoutTime) {
				htl = htl2;
				timeoutTime = newTimeoutTime;
			}
			addRequestors(requestors, now);
			addRequestedFrom(new PeerNode[] { requested }, now);
		}
	}

	// These are rather low level, in an attempt to absolutely minimize memory usage...
	// The two methods have almost identical code/logic.
	// Dunno if there's a more elegant way of dealing with this which doesn't significantly increase
	// per entry byte cost.
	// Note also this will generate some churn...
	
	synchronized void addRequestors(PeerNode[] requestors, long now) {
		receivedTime = now;
		int notIncluded = 0;
		int nulls = 0;
		int ptr = 0;
		for(int i=0;i<requestors.length;i++) {
			PeerNode req = requestors[i];
			boolean requestorIncluded = false;
			for(int j=0;j<requestorNodes.length;j++) {
				PeerNode got = requestorNodes[i] == null ? null : (PeerNode) requestorNodes[i].get();
				// No longer subscribed if they have rebooted, or expired
				if(got.getBootID() != requestorBootIDs[i] ||
						now - requestorTimes[i] > MAX_TIME_BETWEEN_REQUEST_AND_OFFER) {
					requestorNodes[i] = null;
					got = null;
				}
				if(got == null)
					nulls++;
				if(got == req) {
					// Update existing entry
					requestorIncluded = true;
					requestorTimes[j] = now;
					requestorBootIDs[j] = req.getBootID();
					break;
				}
			}
			if(!requestorIncluded) {
				notIncluded++;
				requestors[ptr++] = requestors[i];
			} // if it's new, keep it in requestors
		}
		for(int i=ptr;i<requestors.length;i++) requestors[i] = null;
		if(notIncluded == 0 && nulls == 0) return;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(notIncluded == nulls && notIncluded > 0) {
			// Nice special case
			int x = 0;
			for(int i=0;i<requestorNodes.length;i++) {
				if(requestorNodes[i].get() == null) {
					PeerNode pn = requestors[x++];
					requestorNodes[i] = pn.myRef;
					requestorTimes[i] = now;
					requestorBootIDs[i] = pn.getBootID();
					if(x == ptr) break;
				}
			}
			return;
		}
		WeakReference[] newRequestorNodes = new WeakReference[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorTimes = new long[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorBootIDs = new long[requestorNodes.length+notIncluded-nulls];
		int fromIndex = 0;
		int toIndex = 0;
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null || ref.get() == null) {
				while(fromIndex < ptr) {
					PeerNode pn = requestors[fromIndex];
					if(pn != null) {
						newRequestorNodes[toIndex] = pn.myRef;
						newRequestorTimes[toIndex] = now;
						newRequestorBootIDs[toIndex] = pn.getBootID();
						toIndex++;
						break;
					}
					fromIndex++;
				}
			} else {
				newRequestorNodes[toIndex] = requestorNodes[i];
				newRequestorTimes[toIndex] = requestorTimes[i];
				newRequestorBootIDs[toIndex] = requestorBootIDs[i];
				toIndex++;
			}
		}
		for(;fromIndex<ptr;fromIndex++) {
			PeerNode pn = requestors[fromIndex];
			if(pn != null) {
				newRequestorNodes[toIndex] = pn.myRef;
				newRequestorTimes[toIndex] = now;
				newRequestorBootIDs[toIndex] = pn.getBootID();
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
	}

	private synchronized void addRequestedFrom(PeerNode[] requestedFrom, long now) {
		sentTime = now;
		int notIncluded = 0;
		int nulls = 0;
		int ptr = 0;
		for(int i=0;i<requestedFrom.length;i++) {
			PeerNode req = requestedFrom[i];
			boolean requestorIncluded = false;
			for(int j=0;j<requestedNodes.length;j++) {
				PeerNode got = requestedNodes[i] == null ? null : (PeerNode) requestedNodes[i].get();
				if(got == null)
					nulls++;
				if(got == req) {
					// Update existing entry
					requestorIncluded = true;
					requestedLocs[j] = req.getLocation().getValue();
					requestedBootIDs[j] = req.getBootID();
					requestedTimes[j] = now;
					break;
				}
			}
			if(!requestorIncluded) {
				notIncluded++;
				requestedFrom[ptr++] = requestedFrom[i];
			} // if it's new, keep it in requestedFrom, otherwise delete it
		}
		for(int i=ptr;i<requestedFrom.length;i++) requestedFrom[i] = null;
		if(notIncluded == 0 && nulls == 0) return;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(notIncluded == nulls && notIncluded > 0) {
			// Nice special case
			int x = 0;
			for(int i=0;i<requestedNodes.length;i++) {
				if(requestedNodes[i].get() == null) {
					PeerNode pn = requestedFrom[x++];
					requestedNodes[i] = pn.myRef;
					requestedLocs[i] = pn.getLocation().getValue();
					requestedTimes[i] = now;
					if(x == ptr) break;
				}
			}
			return;
		}
		WeakReference[] newRequestedNodes = new WeakReference[requestedNodes.length+notIncluded-nulls];
		double[] newRequestedLocs = new double[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedBootIDs = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimes = new long[requestedNodes.length+notIncluded-nulls];

		int fromIndex = 0;
		int toIndex = 0;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			if(ref == null || ref.get() == null) {
				while(fromIndex < ptr) {
					PeerNode pn = requestedFrom[fromIndex];
					if(pn != null) {
						newRequestedNodes[toIndex] = pn.myRef;
						newRequestedLocs[toIndex] = pn.getLocation().getValue();
						newRequestedBootIDs[toIndex] = pn.getBootID();
						newRequestedTimes[toIndex] = now;
						toIndex++;
					}
					fromIndex++;
				}
			} else {
				newRequestedNodes[toIndex] = requestedNodes[i];
				newRequestedLocs[toIndex] = requestedLocs[i];
				newRequestedBootIDs[toIndex] = requestedBootIDs[i];
				newRequestedTimes[toIndex] = requestedTimes[i];
				toIndex++;
			}
		}
		for(;fromIndex<ptr;fromIndex++) {
			PeerNode pn = requestedFrom[fromIndex];
			if(pn != null) {
				newRequestedNodes[toIndex] = pn.myRef;
				newRequestedLocs[toIndex] = pn.getLocation().getValue();
				newRequestedBootIDs[toIndex] = pn.getBootID();
				newRequestedTimes[toIndex] = now;
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
	}

	public double bestLiveLocDiff() {
		WeakReference[] nodes;
		synchronized(this) {
			nodes = requestedNodes;
		}
		double bestDiff = 2.0;
		for(int i=0;i<nodes.length;i++) {
			if(nodes[i] == null) continue;
			PeerNode pn = (PeerNode) nodes[i].get();
			if(pn == null) continue;
			if(!(pn.isRoutable() && pn.isRoutingBackedOff())) continue;
			double diff = PeerManager.distance(key.toNormalizedDouble(), pn.getLocation().getValue());
			if(diff < bestDiff) bestDiff = diff;
		}
		return bestDiff;
	}

	/** Offer this node to all the nodes that have requested it, and all the nodes it has been requested from.
	 * Called after a) the data has been stored, and b) this entry has been removed from the FT */
	public void offer() {
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestorBootIDs[i]) continue;
			pn.offer(key);
		}
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			if(ref == null) continue;
			PeerNode pn = (PeerNode) ref.get();
			if(pn == null) continue;
			if(pn.getBootID() != requestedBootIDs[i]) continue;
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
			if(pn == peer) return true;
		}
		if(!anyValid) {
			requestorNodes = new WeakReference[0];
			requestorTimes = requestorBootIDs = new long[0];
		}
		return false;
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