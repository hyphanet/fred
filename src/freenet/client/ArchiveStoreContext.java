/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * Tracks all files currently in the cache from a given key.
 * Keeps the last known hash of the key (if this changes in a fetch, we flush the cache, unpack,
 * then throw an ArchiveRestartedException).
 * Provides fetch methods for Fetcher, which try the cache and then fetch if necessary, 
 * subject to the above.
 * 
 * Always take the lock on ArchiveStoreContext before the lock on ArchiveManager, NOT the other way around.
 * 
 * Not normally to be used directly by external packages, but public for
 * ArchiveManager.extractToCache. FIXME.
 */
public class ArchiveStoreContext implements ArchiveHandler {

	private FreenetURI key;
	private short archiveType;
	private boolean forceRefetchArchive;
	/** Archive size */
	private long lastSize = -1;
	/** Archive hash */
	private byte[] lastHash;
	/** Index of still-cached ArchiveStoreItems with this key.
	 * Note that we never ever hold this and then take another lock! In particular
	 * we must not take the ArchiveManager lock while holding this lock. It must be
	 * the inner lock to avoid deadlocks. */
	private final DoublyLinkedListImpl myItems;
	
	ArchiveStoreContext(FreenetURI key, short archiveType, boolean forceRefetchArchive) {
		this.key = key;
		this.archiveType = archiveType;
		myItems = new DoublyLinkedListImpl();
		this.forceRefetchArchive = forceRefetchArchive;
	}

	/**
	 * Get the metadata for a given archive.
	 * @return A Bucket containing the metadata, in binary format, for the archive.
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives, ArchiveManager manager) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, dm, recursionLevel, dontEnterImplicitArchives, manager);
	}

	/**
	 * Fetch a file in an archive.
	 * @return A Bucket containing the data. This will not be freed until the 
	 * client is finished with it i.e. calls free() or it is finalized.
	 */
	public Bucket get(String internalName, ArchiveContext archiveContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives, ArchiveManager manager) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {

		// Do loop detection on the archive that we are about to fetch.
		archiveContext.doLoopDetection(key);
		
		if(forceRefetchArchive) return null;
		
		Bucket data;
		
		// Fetch from cache
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Checking cache: "+key+ ' ' +internalName);
		if((data = manager.getCached(key, internalName)) != null) {
			return data;
		}	
		
		return null;
	}

	/** Returns the size of the archive last time we fetched it, or -1 */
	long getLastSize() {
		return lastSize;
	}
	
	/** Sets the size of the archive - @see getLastSize() */
	void setLastSize(long size) {
		lastSize = size;
	}

	
	/** Returns the hash of the archive last time we fetched it, or null */
	byte[] getLastHash() {
		return lastHash;
	}

	/** Sets the hash of the archive - @see getLastHash() */
	void setLastHash(byte[] realHash) {
		lastHash = realHash;
	}

	/**
	 * Remove all ArchiveStoreItems with this key from the cache.
	 */
	void removeAllCachedItems(ArchiveManager manager) {
		ArchiveStoreItem item = null;
		while(true) {
			synchronized (myItems) {
				item = (ArchiveStoreItem) myItems.pop();
			}
			if(item == null) break;
			manager.removeCachedItem(item);
		}
	}

	/** Notify that a new archive store item with this key has been added to the cache. */
	void addItem(ArchiveStoreItem item) {
		synchronized(myItems) {
			myItems.push(item);
		}
	}

	/** Notify that an archive store item with this key has been expelled from the 
	 * cache. Remove it from our local cache and ask it to free the bucket if 
	 * necessary. */
	void removeItem(ArchiveStoreItem item) {
		synchronized(myItems) {
			if(myItems.remove(item) == null) return; // only removed once
		}
		item.innerClose();
	}

	public short getArchiveType() {
		return archiveType;
	}
	
	public FreenetURI getKey() {
		return key;
	}

	public void extractToCache(Bucket bucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ArchiveManager manager) throws ArchiveFailureException, ArchiveRestartException {
		manager.extractToCache(key, archiveType, bucket, actx, this, element, callback);
	}

	/** Called just before extracting this container to the cache */
	void onExtract() {
		forceRefetchArchive = false;
	}
}
