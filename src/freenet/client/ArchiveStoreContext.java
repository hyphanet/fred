/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;
import freenet.support.io.Bucket;

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

	private ArchiveManager manager;
	private FreenetURI key;
	private short archiveType;
	
	public ArchiveStoreContext(ArchiveManager manager, FreenetURI key, short archiveType) {
		this.manager = manager;
		this.key = key;
		this.archiveType = archiveType;
		myItems = new DoublyLinkedListImpl();
	}

	/**
	 * Get the metadata for a given archive.
	 * @return A Bucket containing the metadata, in binary format, for the archive.
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, dm, recursionLevel, dontEnterImplicitArchives);
	}

	/**
	 * Fetch a file in an archive.
	 */
	public Bucket get(String internalName, ArchiveContext archiveContext, ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {

		// Do loop detection on the archive that we are about to fetch.
		archiveContext.doLoopDetection(key);
		
		Bucket data;
		
		// Fetch from cache
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Checking cache: "+key+" "+internalName);
		if((data = manager.getCached(key, internalName)) != null) {
			return data;
		}	
		
		return null;
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
	
	byte[] lastHash;
	
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
		ArchiveStoreItem item = null;
		while(true) {
			synchronized (myItems) {
				item = (ArchiveStoreItem) myItems.pop();
			}
			if(item == null) break;
			manager.removeCachedItem(item);
			item.finalize();
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

	public short getArchiveType() {
		return archiveType;
	}
	
	public FreenetURI getKey() {
		return key;
	}

	public void extractToCache(Bucket bucket, ArchiveContext actx) throws ArchiveFailureException, ArchiveRestartException {
		manager.extractToCache(key, archiveType, bucket, actx, this);
	}
}
