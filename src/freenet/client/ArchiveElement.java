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

	ArchiveElement(ArchiveManager manager, FreenetURI uri, ClientKey ckey, String filename) {
		this.manager = manager;
		this.key = uri;
		this.ckey = ckey;
		this.filename = filename;
	}
	
	final ArchiveManager manager;
	final FreenetURI key;
	final ClientKey ckey;
	final String filename;
	
	/**
	 * Fetch the element.
	 * @throws ArchiveFailureException 
	 */
	public Bucket get(ArchiveContext archiveContext, FetcherContext fetchContext) throws ArchiveFailureException {
		
		archiveContext.doLoopDetection(ckey);
		// AFTER the loop check (possible deadlocks)
		synchronized(this) {
			// Synchronized during I/O to avoid doing it twice
			Bucket cached = manager.getCached(key, filename);
			if(cached != null) return cached;
			Fetcher fetcher = new Fetcher(key, fetchContext, archiveContext);
			fetcher.realRun();
			manager.extractToCache(key, archiveContext);
		}
	}
}
