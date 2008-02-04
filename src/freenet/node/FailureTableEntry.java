/**
 * 
 */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import freenet.keys.Key;
import freenet.support.Logger;

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
	
	static boolean logMINOR;
	
	/** We remember that a node has asked us for a key for up to an hour; after that, we won't offer the key, and
	 * if we receive an offer from that node, we will reject it */
	static final int MAX_TIME_BETWEEN_REQUEST_AND_OFFER = 60 * 60 * 1000;
	
	FailureTableEntry(Key key2, short htl2, PeerNode[] requestors, PeerNode requested) {
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
			requestedNodes = new WeakReference[] { requested.myRef };
			requestedLocs = new double[] { requested.getLocation() };
			requestedBootIDs = new long[] { requested.getBootID() };
			requestedTimes = new long[] { System.currentTimeMillis() };
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
	 * @param requested
	 */
	public void onFailure(short htl2, PeerNode[] requestors, PeerNode requested, int timeout, long now) {
		synchronized(this) {
			long newTimeoutTime = now + timeout;
			if(now > timeoutTime /* has expired */ && newTimeoutTime > timeoutTime) {
				htl = htl2;
				timeoutTime = newTimeoutTime;
			}
			if(requestors != null)
				addRequestors(requestors, now);
			if(requested != null)
				addRequestedFrom(new PeerNode[] { requested }, now);
		}
	}

	// These are rather low level, in an attempt to absolutely minimize memory usage...
	// The two methods have almost identical code/logic.
	// Dunno if there's a more elegant way of dealing with this which doesn't significantly increase
	// per entry byte cost.
	// Note also this will generate some churn...
	
	synchronized void addRequestors(PeerNode[] requestors, long now) {
		if(logMINOR) Logger.minor(this, "Adding requestors: "+requestors+" at "+now);
		receivedTime = now;
		/** The number of new requestor elements. These are moved to the beginning and the 
		 * rest is nulled out. So this is also the index of the first non-null element in 
		 * requestors. */
		int notIncluded = 0;
		int nulls = 0;
		for(int i=0;i<requestors.length;i++) {
			PeerNode req = requestors[i];
			boolean requestorIncluded = false;
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
					break;
				}
			}
			if(!requestorIncluded) {
				requestors[notIncluded++] = requestors[i];
			} // if it's new, keep it in requestors
		}
		for(int i=notIncluded;i<requestors.length;i++) requestors[i] = null;
		if(logMINOR) Logger.minor(this, "notIncluded="+notIncluded+" nulls="+nulls+" requestors.length="+requestors.length+" requestorNodes.length="+requestorNodes.length);
		if(notIncluded == 0 && nulls == 0) return;
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(notIncluded == nulls) {
			// Nice special case
			int x = 0;
			for(int i=0;i<requestorNodes.length;i++) {
				if(requestorNodes[i] == null || requestorNodes[i].get() == null) {
					PeerNode pn = requestors[x++];
					requestorNodes[i] = pn.myRef;
					requestorTimes[i] = now;
					requestorBootIDs[i] = pn.getBootID();
					if(x == notIncluded) break;
				}
			}
			return;
		}
		WeakReference[] newRequestorNodes = new WeakReference[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorTimes = new long[requestorNodes.length+notIncluded-nulls];
		long[] newRequestorBootIDs = new long[requestorNodes.length+notIncluded-nulls];
		int toIndex = 0;
		
		for(int i=0;i<requestorNodes.length;i++) {
			WeakReference ref = requestorNodes[i];
			if(ref == null || ref.get() == null) continue;
			newRequestorNodes[toIndex] = requestorNodes[i];
			newRequestorTimes[toIndex] = requestorTimes[i];
			newRequestorBootIDs[toIndex] = requestorBootIDs[i];
			toIndex++;
		}
		
		for(int fromIndex=0;fromIndex<notIncluded;fromIndex++) {
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
		if(logMINOR) Logger.minor(this, "Adding requested from: "+requestedFrom+" at "+now);
		sentTime = now;
		/** The number of new requestedFrom elements. These are moved to the beginning and the 
		 * rest is nulled out. So this is also the index of the first non-null element in 
		 * requestedFrom. */
		int notIncluded = 0;
		int nulls = 0;
		for(int i=0;i<requestedFrom.length;i++) {
			PeerNode req = requestedFrom[i];
			boolean requestorIncluded = false;
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
					break;
				}
			}
			if(!requestorIncluded) {
				requestedFrom[notIncluded++] = requestedFrom[i];
			} // if it's new, keep it in requestedFrom, otherwise delete it
		}
		for(int i=notIncluded;i<requestedFrom.length;i++) requestedFrom[i] = null;
		if(notIncluded == 0 && nulls == 0) return;
		if(logMINOR) Logger.minor(this, "notIncluded="+notIncluded+" nulls="+nulls+" requestedFrom.length="+requestedFrom.length+" requestedNodes.length="+requestedNodes.length);
		// Because weak, these can become null; doesn't matter, but we want to minimise memory usage
		if(notIncluded == nulls) {
			// Nice special case
			int x = 0;
			for(int i=0;i<requestedNodes.length;i++) {
				if(requestedNodes[i] == null || requestedNodes[i].get() == null) {
					PeerNode pn = requestedFrom[x++];
					requestedNodes[i] = pn.myRef;
					requestedLocs[i] = pn.getLocation();
					requestedTimes[i] = now;
					if(x == notIncluded) break;
				}
			}
			return;
		}
		WeakReference[] newRequestedNodes = new WeakReference[requestedNodes.length+notIncluded-nulls];
		double[] newRequestedLocs = new double[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedBootIDs = new long[requestedNodes.length+notIncluded-nulls];
		long[] newRequestedTimes = new long[requestedNodes.length+notIncluded-nulls];

		int toIndex = 0;
		for(int i=0;i<requestedNodes.length;i++) {
			WeakReference ref = requestedNodes[i];
			if(ref == null || ref.get() == null) continue;
			newRequestedNodes[toIndex] = requestedNodes[i];
			newRequestedTimes[toIndex] = requestedTimes[i];
			newRequestedBootIDs[toIndex] = requestedBootIDs[i];
			newRequestedLocs[toIndex] = requestedLocs[i];
			toIndex++;
		}
		
		for(int fromIndex=0;fromIndex<notIncluded;fromIndex++) {
			PeerNode pn = requestedFrom[fromIndex];
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