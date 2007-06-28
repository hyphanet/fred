/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.lang.ref.WeakReference;

import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.support.LRUHashtable;

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
	
	FailureTable(PeerManager peers, Node node) {
		entriesByKey = new LRUHashtable();
		blockOfferListByKey = new LRUHashtable();
		this.peers = peers;
		this.node = node;
	}
	
	/**
	 * Called when a key DNFs, or is killed by a RecentlyFailed message. Either way this can create a 
	 * FailureTableEntry.
	 * @param key The key that was fetched.
	 * @param htl The HTL it was fetched at.
	 * @param requestors The nodes requesting it (if any).
	 * @param requested The single node it was forwarded to, which DNFed.
	 * @param now The time at which the request was sent.
	 * @param timeout The number of millis from when the request was sent to when the failure block times out.
	 * I.e. between 0 and REJECT_TIME.
	 */
	public void onFailure(Key key, short htl, PeerNode[] requestors, PeerNode requested, int timeout, long now) {
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				entry = new FailureTableEntry(key, htl, requestors, requested);
				entriesByKey.push(key, entry);
				return;
			} else {
				entriesByKey.push(key, entry);
			}
			trimEntries(now);
		}
		entry.onFailure(htl, requestors, requested, timeout, now);
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
		entry.addRequestors(new PeerNode[] { requestor }, now);
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

		public long expires() {
			long last = 0;
			for(int i=0;i<offers.length;i++) {
				if(offers[i].offeredTime > last) last = offers[i].offeredTime;
			}
			return last;
		}

		public boolean isEmpty(long now) {
			for(int i=0;i<offers.length;i++) {
				if(offers[i].offeredTime > now) return false;
			}
			return true;
		}
	}
	
	private final class BlockOffer {
		final long offeredTime;
		/** Either offered by or offered to this node */
		final WeakReference nodeRef;
		
		BlockOffer(PeerNode pn, long now) {
			this.nodeRef = pn.myRef;
			this.offeredTime = now;
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
		}
		entry.offer();
	}
	
	/**
	 * Called when we get an offer for a key. If this is an SSK, we will only accept it if we have previously asked for it.
	 * If it is a CHK, we will accept it if we want it.
	 * @param key The key we are being offered.
	 * @param peer The node offering it.
	 */
	public void onOffer(Key key, PeerNode peer) {
		if(wantOffer(key, peer)) {
			// Okay, we want the offer. Now what?
			// Two ClientRequestScheduler's? Then you'd have to remove the key from two different RGA's :(
			// Anyway, we don't want a key to be requested just because another key in the same group has an offer.
			// So what we want is a list of keys at each level which have been offered.
			// These would be considered before the other keys at that level, but only if the offer is still valid.
		}
	}
	
	boolean wantOffer(Key key, PeerNode peer) {
		FailureTableEntry entry;
		long now = System.currentTimeMillis();
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) return false; // we haven't asked for it
			
			/*
			 * Accept (subject to later checks) if we asked for it.
			 * Should we accept it if we were asked for it? This is "bidirectional propagation".
			 * It's good because it makes the whole structure much more reliable; it's bad because
			 * it's not entirely under our control - we didn't choose to route it to the node, the node
			 * routed it to us. Now it's found it before we did...
			 * 
			 * Attacks:
			 * - Frost spamming etc: Is it easier to offer data to our peers rather than inserting it? Will
			 * it result in it being propagated further? Probably not. Propagation to nodes that have asked is
			 * worthwhile in general partly because reduced polling cost enables more secure messaging systems
			 * e.g. outbox polling... Not relevant with CHKs.
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
			
			if(!(entry.askedFromPeer(peer, now) || 
					((key instanceof NodeCHK) && entry.askedByPeer(peer, now)))) {
				if(entry.isEmpty(now)) entriesByKey.removeKey(key);
				return false;
			}
			if(entry.isEmpty(now)) entriesByKey.removeKey(key);
			
			// Valid offer.
			
			// Add to offers list
			
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.get(key);
			BlockOffer offer = new BlockOffer(peer, now);
			if(bl == null) {
				bl = new BlockOfferList(entry, offer);
			}
			blockOfferListByKey.push(key, offer);
			trimOffersList(now);
		}
		
		// Now, does anyone want it?
		// Firstly, do we want it?
		if(!node.clientCore.clientWantKey(key)) return true;
		if(entry.othersWant(peer)) return true;
		return false;
	}

	private synchronized void trimOffersList(long now) {
		while(true) {
			if(blockOfferListByKey.isEmpty()) return;
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.peekValue();
			if(bl.isEmpty(now) || bl.expires() < now || blockOfferListByKey.size() > MAX_OFFERS) {
				blockOfferListByKey.popKey();
			} else {
				return;
			}
		}
	}
}
