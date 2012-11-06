/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;


/**
 * A key-value cache with a fixed size and expiration time.
 * The least recently used item is removed if the cache is full and a new entry is added.
 * 
 * Existing entries are only returned if they are not expired.
 * 
 * Notice that expired items are ONLY removed when trying to get() them or when they fall out during push() because they are the
 * least-recently-used.
 * Therefore, this cache is intended for small sizes (or large amounts of small items).
 * 
 * If you want to do a large cache and therefore need periodical garbage collection, please email me and I might implement it.
 * 
 * Pushing and getting are executed in O(lg N) using a tree, to avoid hash collision DoS'es.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class LRUCache<Key extends Comparable<Key>, Value> {
	
	private final int mSizeLimit;
	private final long mExpirationDelay;
	
	private final class Entry {
		private final Value mValue;
		private final long mExpirationDate;
		
		public Entry(final Value myValue) {
			mValue = myValue;
			mExpirationDate = CurrentTimeUTC.getInMillis() + mExpirationDelay; 
		}
		
		public boolean expired(final long time) {
			return mExpirationDate < time;
		}
		
		public boolean expired() {
			return expired(CurrentTimeUTC.getInMillis());
		}
		
		public Value getValue() {
			return mValue;
		}
	}
	
	private final LRUMap<Key, Entry> mCache;


	/**
	 * @param sizeLimit The maximal amount of items which the cache should hold.
	 * @param expirationDelay The amount of milliseconds after which an entry expires.
	 */
	public LRUCache(final int sizeLimit, final long expirationDelay) {
		mCache = LRUMap.createSafeMap();
		mSizeLimit = sizeLimit;
		mExpirationDelay = expirationDelay;
	}
	
	/**
	 * Removes the least recently used items until a free space of the given capacity is available
	 */
	private void freeCapacity(final int capacity) {
		assert(capacity <= mSizeLimit);
		
		final int limit = mSizeLimit - capacity;
		while(mCache.size() > limit)
			mCache.popValue();
	}

	/**
	 * Puts a value in the cache. If an entry for the key already exists, its value is updated and its
	 * expiration time is increased.
	 * 
	 * If the size limit of this cache is exceeded the least recently used entry is removed.
	 */
	public void put(final Key key, final Value value) {
		mCache.push(key, new Entry(value)); 
		freeCapacity(0);
	}
	
	/**
	 * Gets a value from the cache. Returns null if there is no entry for the given key or if the entry is
	 * expired. If an expired entry was found, it is removed from the cache.
	 */
	public Value get(final Key key) {
		final Entry entry = mCache.get(key);
		if(entry == null)
			return null;
		
		if(entry.expired()) {
			mCache.removeKey(key);
			return null;
		}
		
		return entry.getValue();
	}
}
