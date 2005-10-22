package freenet.client;

import freenet.keys.ClientKey;
import freenet.support.Bucket;

/**
 * Handles a single archive for ZIP manifests.
 */
class ArchiveHandler {

	private ArchiveManager manager;
	private ClientKey key;
	
	public ArchiveHandler(ArchiveManager manager, ClientKey key, short archiveType) {
		this.manager = manager;
		this.key = key;
	}

	public void finalize() {
		// Need to do anything here?
	}

	/**
	 * Get the metadata for this ZIP manifest, as a Bucket.
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, FetcherContext fetchContext) throws ArchiveFailureException, ArchiveRestartException {
		return get(".metadata", archiveContext, fetchContext);
	}

	/**
	 * Get a file from this ZIP manifest, as a Bucket.
	 * If possible, read it from cache. If necessary, refetch the 
	 * container and extract it. If that fails, throw.
	 */
	public synchronized Bucket get(String internalName, ArchiveContext archiveContext, FetcherContext fetchContext) throws ArchiveFailureException, ArchiveRestartException {
		ArchiveElement element = 
			manager.makeElement(key, internalName);
		return element.get(archiveContext, fetchContext);
	}
}
