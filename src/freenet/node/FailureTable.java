/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.support.LRUHashtable;
import freenet.support.Logger;

// FIXME it is ESSENTIAL that we delete the ULPR data on requestors etc once we have found the key.
// Otherwise it will be much too easy to trace a request if an attacker busts the node afterwards.
// We can use an HMAC or something to authenticate offers.

/**
 * Tracks recently DNFed keys, where they were routed to, what the location was at the time, who requested them.
 * Implements Ultra-Lightweight Persistent Requests: Refuse requests for a key for 10 minutes after it's DNFed 
 * (UNLESS we find a better route for the request), and when it is found, offer it to those who've asked for it
 * in the last hour.
 * @author toad
 */
public class FailureTable {
	
	/** FailureTableEntry's by key. Note that we push an entry only when sentTime changes. */
	private final LRUHashtable entriesByKey;
	/** BlockOfferList by key */
	private final LRUHashtable blockOfferListByKey;
	/** Peers */
	private final PeerManager peers;
	private final Node node;
	
	/** Maximum number of keys to track */
	static final int MAX_ENTRIES = 20*1000;
	/** Maximum number of offers to track */
	static final int MAX_OFFERS = 10*1000;
	/** Terminate a request if there was a DNF on the same key less than 10 minutes ago */
	static final int REJECT_TIME = 10*60*1000;
	/** After 1 hour we forget about an entry completely */
	static final int MAX_LIFETIME = 60*60*1000;
	/** Offers expire after 10 minutes */
	static final int OFFER_EXPIRY_TIME = 10*60*1000;
	/** HMAC key for the offer authenticator */
	final byte[] offerAuthenticatorKey;
	
	static boolean logMINOR;
	static boolean logDEBUG;
	
	FailureTable(PeerManager peers, Node node) {
		entriesByKey = new LRUHashtable();
		blockOfferListByKey = new LRUHashtable();
		this.peers = peers;
		this.node = node;
		offerAuthenticatorKey = new byte[32];
		node.random.nextBytes(offerAuthenticatorKey);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
	}
	
	/**
	 * Called when we route to a node and it fails for some reason, but we continue the request.
	 * Normally the timeout will be the time it took to route to that node and wait for its 
	 * response / timeout waiting for its response.
	 * @param key
	 * @param routedTo
	 * @param htl
	 * @param timeout
	 */
	public void onFailed(Key key, PeerNode routedTo, short htl, int timeout) {
		long now = System.currentTimeMillis();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				entry = new FailureTableEntry(key);
				entriesByKey.push(key, entry);
				return;
			} else {
				entriesByKey.push(key, entry);
			}
			trimEntries(now);
		}
		entry.failedTo(routedTo, timeout, now, htl);
	}
	
	public void onFinalFailure(Key key, PeerNode routedTo, short htl, int timeout, PeerNode requestor) {
		long now = System.currentTimeMillis();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				entry = new FailureTableEntry(key);
				entriesByKey.push(key, entry);
				return;
			} else {
				entriesByKey.push(key, entry);
			}
			trimEntries(now);
		}
		if(routedTo != null)
			entry.failedTo(routedTo, timeout, now, htl);
		if(requestor != null)
			entry.addRequestor(requestor, now);
	}
	
	private void trimEntries(long now) {
		while(entriesByKey.size() > MAX_ENTRIES) {
			entriesByKey.popKey();
		}
		while(true) {
			FailureTableEntry e = (FailureTableEntry) entriesByKey.peekValue();
			if(now - e.creationTime > MAX_LIFETIME) entriesByKey.popKey();
			else break;
		}
	}

	/**
	 * Called when a request is made. Determine whether we should fail the request, and add the requestors to the list
	 * of interested nodes.
	 * @param key The key to fetch.
	 * @param htl The HTL it will be fetched at.
	 * @param requestor The node requesting it.
	 * @return True if the request should be failed with an FNPRecentlyFailed.
	 */
	public synchronized boolean shouldFail(Key key, short htl, PeerNode requestor) {
		long now = System.currentTimeMillis();
		FailureTableEntry entry = (FailureTableEntry) entriesByKey.get(key);
		if(entry == null) {
			// Don't know anything about the key
			return false;
		}
		entry.addRequestor(requestor, now);
		if(htl > entry.htl) {
			// If the HTL is higher this time, let it through
			entriesByKey.push(key, entry);
			return false;
		}
		if(now > entry.timeoutTime) {
			// If it's more than 10 minutes since we sent a request, let it through
			return false;
		}
		/*
		 * If the best node available now is closer than the best location we have routed to so far, out of those
		 * nodes which are still connected, then accept the request.
		 * 
		 * Note that this means we can route to the same node twice - but only if its location improves.
		 */
		double bestLiveLocDiff = entry.bestLiveLocDiff();
		
		PeerNode p = peers.closerPeer(requestor, null, null, key.toNormalizedDouble(), true, false, 0, null, bestLiveLocDiff);
		
		if(p != null) return false; // there is a better route now / we want to retry an old one
		
		return true; // kill the request
	}
	
	private final class BlockOfferList {
		private BlockOffer[] offers;
		final FailureTableEntry entry;
		
		BlockOfferList(FailureTableEntry entry, BlockOffer offer) {
			this.entry = entry;
			this.offers = new BlockOffer[] { offer };
		}

		public synchronized long expires() {
			long last = 0;
			for(int i=0;i<offers.length;i++) {
				if(offers[i].offeredTime > last) last = offers[i].offeredTime;
			}
			return last + OFFER_EXPIRY_TIME;
		}

		public synchronized boolean isEmpty(long now) {
			for(int i=0;i<offers.length;i++) {
				if(!offers[i].isExpired(now)) return false;
			}
			return true;
		}

		public void deleteOffer(BlockOffer offer) {
			if(logMINOR) Logger.minor(this, "Deleting "+offer+" from "+this);
			synchronized(this) {
				int idx = -1;
				for(int i=0;i<offers.length;i++) {
					if(offers[i] == offer) idx = i;
				}
				if(idx == -1) return;
				BlockOffer[] newOffers = new BlockOffer[offers.length-1];
				if(idx > 0)
					System.arraycopy(offers, 0, newOffers, 0, idx);
				if(idx < newOffers.length)
					System.arraycopy(offers, idx+1, newOffers, idx, offers.length-idx);
				offers = newOffers;
			}
			if(offers.length == 0) {
				synchronized(FailureTable.this) {
					blockOfferListByKey.removeKey(entry.key);
				}
				node.clientCore.dequeueOfferedKey(entry.key);
			}
		}

		public synchronized void addOffer(BlockOffer offer) {
			BlockOffer[] newOffers = new BlockOffer[offers.length+1];
			System.arraycopy(offers, 0, newOffers, 0, offers.length);
			newOffers[offers.length] = offer;
			offers = newOffers;
		}
		
		public String toString() {
			return super.toString()+"("+offers.length+")";
		}
	}
	
	final class BlockOffer {
		final long offeredTime;
		/** Either offered by or offered to this node */
		final WeakReference nodeRef;
		/** Authenticator */
		final byte[] authenticator;
		/** Boot ID when the offer was made */
		final long bootID;
		
		BlockOffer(PeerNode pn, long now, byte[] authenticator, long bootID) {
			this.nodeRef = pn.myRef;
			this.offeredTime = now;
			this.authenticator = authenticator;
			this.bootID = bootID;
		}

		public PeerNode getPeerNode() {
			return (PeerNode) nodeRef.get();
		}

		public boolean isExpired(long now) {
			return now > (offeredTime + OFFER_EXPIRY_TIME);
		}

		public boolean isExpired() {
			return isExpired(System.currentTimeMillis());
		}
	}
	
	/**
	 * Called when a data block is found (after it has been stored; there is a good chance of its being available in the
	 * near future). If there are nodes waiting for it, we will offer it to them.
	 */
	public void onFound(KeyBlock block) {
		Key key = block.getKey();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) return; // Nobody cares
			entriesByKey.removeKey(key);
			blockOfferListByKey.removeKey(key);
		}
		entry.offer();
	}
	
	/**
	 * Called when we get an offer for a key. If this is an SSK, we will only accept it if we have previously asked for it.
	 * If it is a CHK, we will accept it if we want it.
	 * @param key The key we are being offered.
	 * @param peer The node offering it.
	 * @param authenticator 
	 */
	void onOffer(Key key, PeerNode peer, byte[] authenticator) {
		if(logMINOR)
			Logger.minor(this, "Offered key "+key+" by peer "+peer);
		if(node.hasKey(key)) {
			Logger.minor(this, "Already have key");
			return;
		}
		FailureTableEntry entry;
		long now = System.currentTimeMillis();
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				if(logMINOR) Logger.minor(this, "We didn't ask for the key");
				return; // we haven't asked for it
			}
			
			/*
			 * Accept (subject to later checks) if we asked for it.
			 * Should we accept it if we were asked for it? This is "bidirectional propagation".
			 * It's good because it makes the whole structure much more reliable; it's bad because
			 * it's not entirely under our control - we didn't choose to route it to the node, the node
			 * routed it to us. Now it's found it before we did...
			 * 
			 * Attacks:
			 * - Frost spamming etc: Is it easier to offer data to our peers rather than inserting it? Will
			 * it result in it being propagated further? The peer node would then do the request, rather than
			 * this node doing an insert. Is that beneficial?
			 * 
			 * Not relevant with CHKs anyway.
			 * 
			 * On the plus side, propagation to nodes that have asked is worthwhile because reduced polling 
			 * cost enables more secure messaging systems e.g. outbox polling...
			 * - Social engineering: If a key is unpopular, you can put a different copy of it on different 
			 * nodes. You can then use this to trace the requestor - identify that he is or isn't on the target. 
			 * You can't do this with a regular insert because it will often go several nodes even at htl 0. 
			 * With subscriptions, you might be able to bypass this - but only if you know no other nodes in the
			 * neighbourhood are subscribed. Easier with SSKs; with CHKs you have only binary information of 
			 * whether the person got the key (with social engineering). Hard to exploit on darknet; if you're 
			 * that close to the suspect there are easier ways to get at them e.g. correlation attacks.
			 * 
			 * Conclusion: We should accept the request if:
			 * - We asked for it from that node. (Note that a node might both have asked us and been asked).
			 * - That node asked for it, and it's a CHK.
			 */
			
			boolean weAsked = entry.askedFromPeer(peer, now);
			boolean heAsked = entry.askedByPeer(peer, now);
			if(!(weAsked || ((key instanceof NodeCHK) && heAsked))) {
				if(logMINOR) Logger.minor(this, "Not propagating key: weAsked="+weAsked+" heAsked="+heAsked);
				if(entry.isEmpty(now)) entriesByKey.removeKey(key);
				return;
			}
			if(entry.isEmpty(now)) entriesByKey.removeKey(key);
			
			// Valid offer.
			
			// Add to offers list
			
			if(logMINOR) Logger.minor(this, "Valid offer");
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.get(key);
			BlockOffer offer = new BlockOffer(peer, now, authenticator, peer.getBootID());
			if(bl == null) {
				bl = new BlockOfferList(entry, offer);
			} else {
				bl.addOffer(offer);
			}
			blockOfferListByKey.push(key, bl);
			trimOffersList(now);
		}
		
		// Now, does anyone want it?
		
		node.clientCore.maybeQueueOfferedKey(key, entry.othersWant(peer));
	}

	private synchronized void trimOffersList(long now) {
		while(true) {
			if(blockOfferListByKey.isEmpty()) return;
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.peekValue();
			if(bl.isEmpty(now) || bl.expires() < now || blockOfferListByKey.size() > MAX_OFFERS) {
				if(logMINOR) Logger.minor(this, "Removing block offer list "+bl+" list size now "+blockOfferListByKey.size());
				blockOfferListByKey.popKey();
			} else {
				return;
			}
		}
	}

	/**
	 * We offered a key, a node has responded to the offer. Note that this runs on the incoming
	 * packets thread so should allocate a new thread if it does anything heavy.
	 * @param key The key to send.
	 * @param isSSK Whether it is an SSK.
	 * @param uid The UID.
	 * @param source The node that asked for the key.
	 * @throws NotConnectedException If the sender ceases to be connected.
	 */
	public void sendOfferedKey(Key key, boolean isSSK, boolean needPubKey, long uid, PeerNode source) throws NotConnectedException {
		if(isSSK) {
			SSKBlock block = node.fetch((NodeSSK)key, false);
			if(block == null) {
				// Don't have the key
				source.sendAsync(DMT.createFNPGetOfferedKeyInvalid(uid, DMT.GET_OFFERED_KEY_REJECTED_NO_KEY), null, 0, null);
				return;
			}
			Message df = DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
			source.sendAsync(df, null, 0, null);
			if(needPubKey) {
				Message pk = DMT.createFNPSSKPubKey(uid, block.getPubKey());
				source.sendAsync(pk, null, 0, null);
			}
		} else {
			CHKBlock block = node.fetch((NodeCHK)key, false);
			if(block == null) {
				// Don't have the key
				source.sendAsync(DMT.createFNPGetOfferedKeyInvalid(uid, DMT.GET_OFFERED_KEY_REJECTED_NO_KEY), null, 0, null);
				return;
			}
			Message df = DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
			source.sendAsync(df, null, 0, null);
        	PartiallyReceivedBlock prb =
        		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
        	BlockTransmitter bt =
        		new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, null);
        	bt.sendAsync(node.executor);
		}
	}
	
	class OfferList {

		OfferList(BlockOfferList offerList) {
			this.offerList = offerList;
			recentOffers = new Vector();
			expiredOffers = new Vector();
			long now = System.currentTimeMillis();
			BlockOffer[] offers = offerList.offers;
			for(int i=0;i<offers.length;i++) {
				if(!offers[i].isExpired(now))
					recentOffers.add(offers[i]);
				else
					expiredOffers.add(offers[i]);
			}
			if(logMINOR)
				Logger.minor(this, "Offers: "+recentOffers.size()+" recent "+expiredOffers.size()+" expired");
		}
		
		private final BlockOfferList offerList;
		
		private final Vector recentOffers;
		private final Vector expiredOffers;
		
		/** The last offer we returned */
		private BlockOffer lastOffer;
		
		public BlockOffer getFirstOffer() {
			if(lastOffer != null) {
				throw new IllegalStateException("Last offer not dealt with");
			}
			if(!recentOffers.isEmpty()) {
				int x = node.random.nextInt(recentOffers.size());
				return lastOffer = (BlockOffer) recentOffers.remove(x);
			}
			if(!expiredOffers.isEmpty()) {
				int x = node.random.nextInt(expiredOffers.size());
				return lastOffer = (BlockOffer) expiredOffers.remove(x);
			}
			// No more offers.
			return null;
		}
		
		/**
		 * Delete the last offer - we have used it, successfully or not.
		 */
		public void deleteLastOffer() {
			offerList.deleteOffer(lastOffer);
			lastOffer = null;
		}

		/**
		 * Keep the last offer - we weren't able to use it e.g. because of RejectedOverload.
		 * Maybe it will be useful again in the future.
		 */
		public void keepLastOffer() {
			lastOffer = null;
		}
		
	}

	public OfferList getOffers(Key key) {
		BlockOfferList bl;
		synchronized(this) {
			bl = (BlockOfferList) blockOfferListByKey.get(key);
			if(bl == null) return null;
		}
		return new OfferList(bl);
	}

	/** Called when a node disconnects */
	public void onDisconnect(final PeerNode pn) {
		// FIXME do something (off thread if expensive)
	}
}
