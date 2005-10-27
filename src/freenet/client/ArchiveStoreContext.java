package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * Tracks all files currently in the cache from a given key.
 * Keeps the last known hash of the key (if this changes in a fetch, we flush the cache, unpack,
 * then throw an ArchiveRestartedException).
 * Provides fetch methods for Fetcher, which try the cache and then fetch if necessary, 
 * subject to the above.
 */
class ArchiveStoreContext implements ArchiveHandler {

	private ArchiveManager manager;
	private FreenetURI key;
	private short archiveType;
	
	public ArchiveStoreContext(ArchiveManager manager, FreenetURI key, short archiveType) {
		this.manager = manager;
		this.key = key;
		this.archiveType = archiveType;
	}

	public void finalize() {
		// Need to do anything here?
	}

	/* (non-Javadoc)
	 * @see freenet.client.ArchiveHandler#getMetadata(freenet.client.ArchiveContext, freenet.client.FetcherContext, freenet.client.ClientMetadata, int)
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, fetchContext, dm, recursionLevel);
	}

	/* (non-Javadoc)
	 * @see freenet.client.ArchiveHandler#get(java.lang.String, freenet.client.ArchiveContext, freenet.client.FetcherContext, freenet.client.ClientMetadata, int)
	 */
	public Bucket get(String internalName, ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {

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
			FetchResult result = fetcher.realRun(dm, recursionLevel, key);
			manager.extractToCache(key, archiveType, result.data, archiveContext);
			return manager.getCached(key, internalName);
		}
	}
}
