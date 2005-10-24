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

	ArchiveElement(ArchiveManager manager, FreenetURI uri, ClientKey ckey, String filename, short archiveType) {
		this.manager = manager;
		this.key = uri;
		this.ckey = ckey;
		this.filename = filename;
		this.archiveType = archiveType;
	}
	
	final ArchiveManager manager;
	final FreenetURI key;
	final ClientKey ckey;
	final String filename;
	final short archiveType;
	
	/**
	 * Fetch the element.
	 * If fetchContext is null, return null unless the data is cached.
	 * @throws ArchiveFailureException 
	 */
	public Bucket get(ArchiveContext archiveContext, FetcherContext fetchContext, boolean inSplitZipManifest) throws ArchiveFailureException {
		
		archiveContext.doLoopDetection(ckey);
		// AFTER the loop check (possible deadlocks)
		synchronized(this) {
			// Synchronized during I/O to avoid doing it twice
			Bucket cached = manager.getCached(key, filename);
			if(cached != null) return cached;
			if(fetchContext == null) return null;
			Fetcher fetcher = new Fetcher(key, fetchContext, archiveContext);
			FetchResult result = fetcher.realRun(inSplitZipManifest);
			if(result.succeeded())
				manager.extractToCache(key, archiveType, result.data, archiveContext);
			else
				throw new ArchiveFailureException("Fetch failed");
		}
	}
}
