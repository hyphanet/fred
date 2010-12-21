package freenet.node;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import freenet.support.Logger;

/**
 * Base class for tags representing a running request. These store enough information
 * to detect whether they are finished; if they are still in the list, this normally
 * represents a bug.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public abstract class UIDTag {
	
	final long createdTime;
	final boolean wasLocal;
	private final WeakReference<PeerNode> sourceRef;
	final boolean realTimeFlag;
	private final Node node;
	
	/** Nodes we have routed to at some point */
	private HashSet<PeerNode> routedTo = null;
	/** Nodes we are currently talking to i.e. which have not yet removed our UID from the
	 * list of active requests. */
	private HashSet<PeerNode> currentlyRoutingTo = null;
	/** Node we are currently doing an offered-key-fetch from */
	private HashSet<PeerNode> fetchingOfferedKeyFrom = null;
	protected boolean notRoutedOnwards;
	final long uid;
	
	private boolean unlockedHandler;
	protected boolean noRecordUnlock;
	private boolean hasUnlocked;
	
	UIDTag(PeerNode source, boolean realTimeFlag, long uid, Node node) {
		createdTime = System.currentTimeMillis();
		this.sourceRef = source == null ? null : source.myRef;
		wasLocal = source == null;
		this.realTimeFlag = realTimeFlag;
		this.node = node;
		this.uid = uid;
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
	
	public void removeFetchingOfferedKeyFrom(PeerNode next) {
		boolean noRecordUnlock;
		synchronized(this) {
			if(fetchingOfferedKeyFrom == null) return;
			fetchingOfferedKeyFrom.remove(next);
			if(!mustUnlock()) return;
			noRecordUnlock = this.noRecordUnlock;
		}
		innerUnlock(noRecordUnlock);
	}
	
	public void removeRoutingTo(PeerNode next) {
		boolean noRecordUnlock;
		synchronized(this) {
			if(currentlyRoutingTo == null) return;
			currentlyRoutingTo.remove(next);
			if(!mustUnlock()) return;
			noRecordUnlock = this.noRecordUnlock;
		}
		innerUnlock(noRecordUnlock);
	}
	
	protected final void innerUnlock(boolean noRecordUnlock) {
		node.unlockUID(this, false, noRecordUnlock);
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
		if(wasLocal) return null;
		return sourceRef.get();
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

	public abstract boolean isSSK();

	public abstract boolean isInsert();

	public abstract boolean isOfferReply();
	
	/** Caller must call innerUnlock(noRecordUnlock) immediately if this returns true. 
	 * Hence derived versions should call mustUnlock() only after they have checked their
	 * own unlock blockers. */
	protected synchronized boolean mustUnlock() {
		if(hasUnlocked) return false;
		if(!unlockedHandler) return false;
		if(currentlyRoutingTo != null && !currentlyRoutingTo.isEmpty()) {
			if(!reassigned)
				Logger.error(this, "Unlocked handler but still routing to "+currentlyRoutingTo.size()+" yet not reassigned on "+this);
			return false;
		}
		if(fetchingOfferedKeyFrom != null && !fetchingOfferedKeyFrom.isEmpty()) {
			if(!reassigned)
				Logger.error(this, "Unlocked handler but still fetching offered keys from "+fetchingOfferedKeyFrom.size()+" yet not reassigned on "+this);
			return false;
		}
		Logger.normal(this, "Unlocking "+this, new Exception("debug"));
		hasUnlocked = true;
		return true;
	}
	
	public void unlockHandler(boolean noRecord) {
		boolean canUnlock;
		synchronized(this) {
			if(unlockedHandler) return;
			noRecordUnlock = noRecord;
			unlockedHandler = true;
			canUnlock = mustUnlock();
		}
		if(canUnlock)
			innerUnlock(noRecordUnlock);
		else {
			Logger.error(this, "Cannot unlock yet in unlockHandler, still sending requests");
		}
	}

	public void unlockHandler() {
		unlockHandler(false);
	}


}
