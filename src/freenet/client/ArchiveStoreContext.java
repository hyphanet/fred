package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.DoublyLinkedListImpl;

/**
 * Tracks all files currently in the cache from a given key.
 * Keeps the last known hash of the key (if this changes in a fetch, we flush the cache, unpack,
 * then throw an ArchiveRestartedException).
 * Provides fetch methods for Fetcher, which try the cache and then fetch if necessary, 
 * subject to the above.
 * 
 * Always take the lock on ArchiveStoreContext before the lock on ArchiveManager, NOT the other way around.
 */
class ArchiveStoreContext implements ArchiveHandler {

	private ArchiveManager manager;
	private FreenetURI key;
	private short archiveType;
	
	public ArchiveStoreContext(ArchiveManager manager, FreenetURI key, short archiveType) {
		this.manager = manager;
		this.key = key;
		this.archiveType = archiveType;
		myItems = new DoublyLinkedListImpl();
	}

	public void finalize() {
		// Need to do anything here?
	}

	/**
	 * Get the metadata for a given archive.
	 * @return A Bucket containing the metadata, in binary format, for the archive.
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, fetchContext, dm, recursionLevel, dontEnterImplicitArchives);
	}

	/**
	 * Fetch a file in an archive. Will check the cache first, then fetch the archive if
	 * necessary.
	 */
	public Bucket get(String internalName, ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {

		// Do loop detection on the archive that we are about to fetch.
		archiveContext.doLoopDetection(key);
		
		Bucket data;

		// Fetch from cache
		if((data = manager.getCached(key, internalName)) != null) {
			return data;
		}
		
		synchronized(this) {
			// Fetch from cache
			if((data = manager.getCached(key, internalName)) != null) {
				return data;
			}
			
			// Not in cache
			
			if(fetchContext == null) return null;
			Fetcher fetcher = new Fetcher(key, fetchContext, archiveContext);
			FetchResult result = fetcher.realRun(dm, recursionLevel, key, dontEnterImplicitArchives);
			manager.extractToCache(key, archiveType, result.data, archiveContext, this);
			return manager.getCached(key, internalName);
		}
	}

	// Archive size
	long lastSize = -1;
	
	/** Returns the size of the archive last time we fetched it, or -1 */
	long getLastSize() {
		return lastSize;
	}
	
	/** Sets the size of the archive - @see getLastSize() */
	public void setLastSize(long size) {
		lastSize = size;
	}

	// Archive hash
	
	byte[] lastHash = null;
	
	/** Returns the hash of the archive last time we fetched it, or null */
	public byte[] getLastHash() {
		return lastHash;
	}

	/** Sets the hash of the archive - @see getLastHash() */
	public void setLastHash(byte[] realHash) {
		lastHash = realHash;
	}
	
	// Index of still-cached ArchiveStoreItems with this key
	
	/** Index of still-cached ArchiveStoreItems with this key */
	final DoublyLinkedListImpl myItems;

	/**
	 * Remove all ArchiveStoreItems with this key from the cache.
	 */
	public void removeAllCachedItems() {
		synchronized(myItems) {
			ArchiveStoreItem item;
			while((item = (ArchiveStoreItem) myItems.pop()) != null) {
				manager.removeCachedItem(item);
				item.finalize();
			}
		}
	}

	/** Notify that a new archive store item with this key has been added to the cache. */
	public void addItem(ArchiveStoreItem item) {
		synchronized(myItems) {
			myItems.push(item);
		}
	}

	/** Notify that an archive store item with this key has been expelled from the cache. */
	public void removeItem(ArchiveStoreItem item) {
		synchronized(myItems) {
			myItems.remove(item);
		}
	}
	
}
