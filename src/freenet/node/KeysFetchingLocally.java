package freenet.node;

import freenet.keys.Key;

public interface KeysFetchingLocally {
	
	/**
	 * If we are fairly sure we'd like to send this request, we need to route it
	 * and determine whether it would be rejected with RecentlyFailed. Note that
	 * this method is fairly heavyweight and may involve locking PeerNode's and
	 * possibly other things.
	 * @param key The key to check.
	 * @return 0 if the request can be sent, otherwise the time at which it can
	 * be sent. The caller must incorporate this into the relevant cooldown
	 * structures to avoid an expensive recomputation.
	 */
	public long checkRecentlyFailed(Key key, boolean realTime);
	
	/**
	 * Is this key currently being fetched locally?
	 * LOCKING: This should be safe just about anywhere, the lock protecting it is always taken last.
	 */
	public boolean hasKey(Key key, BaseSendableGet getterWaiting);
	
	/**
	 * Is this request:token pair being executed? FIXME this should be tracked by the inserter 
	 * itself.
	 */
	public boolean hasInsert(SendableRequestItemKey token);

}
