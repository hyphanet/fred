package freenet.client;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * An element in an archive. Does synchronization (on fetches, to avoid
 * having to do them twice), checks cache, does fetch, adds to cache.
 *
 * DO LOOP DETECTION!
 */
public class ArchiveElement {

	ArchiveElement(ArchiveManager manager, FreenetURI uri, String filename, short archiveType) {
		this.manager = manager;
		this.key = uri;
		this.filename = filename;
		this.archiveType = archiveType;
	}
	
	final ArchiveManager manager;
	final FreenetURI key;
	final String filename;
	final short archiveType;
	
	/**
	 * Fetch the element.
	 * If fetchContext is null, return null unless the data is cached.
	 * @throws ArchiveFailureException If there was a fatal error in the archive extraction. 
	 * @throws ArchiveRestartException If the archive changed, and therefore we need to
	 * restart the request.
	 * @throws FetchException If we could not fetch the key.
	 * @throws MetadataParseException If the key's metadata was invalid.
	 */
	public Bucket get(ArchiveContext archiveContext, FetcherContext fetchContext, boolean inSplitZipManifest) throws ArchiveFailureException, MetadataParseException, FetchException, ArchiveRestartException {
		
		archiveContext.doLoopDetection(ckey);
		// AFTER the loop check (possible deadlocks)
		synchronized(this) {
			// Synchronized during I/O to avoid doing it twice
			Bucket cached = manager.getCached(key, filename);
			if(cached != null) return cached;
			if(fetchContext == null) return null;
			Fetcher fetcher = new Fetcher(key, fetchContext, archiveContext);
			FetchResult result = fetcher.realRun(inSplitZipManifest);
			if(result.succeeded()) {
				manager.extractToCache(key, archiveType, result.data, archiveContext);
				return manager.getCached(key, filename);
			} else
				throw new ArchiveFailureException("Fetch failed");
		}
	}
}
