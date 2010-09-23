package freenet.node;

import java.util.HashSet;

/**
 * Base class for tags representing a running request. These store enough information
 * to detect whether they are finished; if they are still in the list, this normally
 * represents a bug.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public abstract class UIDTag {
	
	final long createdTime;
	final boolean realTimeFlag;
	final boolean wasLocal;
	// FIXME weak reference? purge on drop?
	// weak reference has the disadvantage that if it's cleared it would be counted as local?
	// Maybe we could compare to the local vs remote on the subclass?
	// in theory when disconnect we will remove it anyway, so i guess it's not a big deal?
	private final PeerNode source;
	
	/** Nodes we have routed to at some point */
	private HashSet<PeerNode> routedTo = null;
	/** Nodes we are currently talking to i.e. which have not yet removed our UID from the
	 * list of active requests. */
	private HashSet<PeerNode> currentlyRoutingTo = null;
	/** Node we are currently doing an offered-key-fetch from */
	private HashSet<PeerNode> fetchingOfferedKeyFrom = null;
	protected boolean notRoutedOnwards;
	
	UIDTag(PeerNode source, boolean realTimeFlag) {
		createdTime = System.currentTimeMillis();
		this.source = source;
		this.realTimeFlag = realTimeFlag;
		wasLocal = source == null;
	}

	public abstract void logStillPresent(Long uid);

	long age() {
		return System.currentTimeMillis() - createdTime;
	}
	
	public synchronized void addRoutedTo(PeerNode peer, boolean offeredKey) {
		if(routedTo == null) routedTo = new HashSet<PeerNode>();
		routedTo.add(peer);
		if(offeredKey) {
			if(fetchingOfferedKeyFrom == null) fetchingOfferedKeyFrom = new HashSet<PeerNode>();
			fetchingOfferedKeyFrom.add(peer);
		} else {
			if(currentlyRoutingTo == null) currentlyRoutingTo = new HashSet<PeerNode>();
			currentlyRoutingTo.add(peer);
		}
	}

	public synchronized boolean hasRoutedTo(PeerNode peer) {
		if(routedTo == null) return false;
		return routedTo.contains(peer);
	}

	public synchronized boolean currentlyRoutingTo(PeerNode peer) {
		if(currentlyRoutingTo == null) return false;
		return currentlyRoutingTo.contains(peer);
	}
	
	// Note that these don't actually get removed until the request is finished, unless there is a disconnection or similar. But that
	// is generally not a problem as they complete quickly and successfully mostly.
	// The alternative would be to remove when the transfer is finished, but that does not
	// guarantee the UID has been freed; we can only safely (for load management purposes)
	// remove once we have an acknowledgement which is sent after the UID is removed.
	
	public synchronized boolean currentlyFetchingOfferedKeyFrom(PeerNode peer) {
		if(fetchingOfferedKeyFrom == null) return false;
		return fetchingOfferedKeyFrom.contains(peer);
	}
	
	public synchronized void removeFetchingOfferedKeyFrom(PeerNode next) {
		if(fetchingOfferedKeyFrom == null) return;
		fetchingOfferedKeyFrom.remove(next);
	}
	
	public synchronized void removeRoutingTo(PeerNode next) {
		if(currentlyRoutingTo == null) return;
		currentlyRoutingTo.remove(next);
	}

	public void postUnlock() {
		PeerNode[] peers;
		synchronized(this) {
			if(routedTo != null)
				peers = routedTo.toArray(new PeerNode[routedTo.size()]);
			else
				peers = null;
		}
		if(peers != null)
			for(PeerNode p : peers)
				p.postUnlock(this);
	}
	
	public abstract int expectedTransfersIn(boolean ignoreLocalVsRemote, int outwardTransfersPerInsert);
	
	public abstract int expectedTransfersOut(boolean ignoreLocalVsRemote, int outwardTransfersPerInsert);
	
	public synchronized void setNotRoutedOnwards() {
		this.notRoutedOnwards = true;
	}

	private boolean reassigned;
	
	/** Get the effective source node (e.g. for load management). This is null if the tag 
	 * was reassigned to us. */
	public synchronized PeerNode getSource() {
		if(reassigned) return null;
		return source;
	}

	/** Reassign the tag to us rather than its original sender. */
	public synchronized void reassignToSelf() {
		if(wasLocal) return;
		reassigned = true;
	}
	
	/** Was the request originated locally? This returns the original answer: It is not
	 * affected by reassigning to self. */
	public boolean wasLocal() {
		return wasLocal;
	}
	
	/** Is the request local now? I.e. was it either originated locally or reassigned to
	 * self? */
	public boolean isLocal() {
		if(wasLocal) return true;
		synchronized(this) {
			return reassigned;
		}
	}
}
