package freenet.client;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * Handles a single archive for ZIP manifests.
 */
class ArchiveHandler {

	private ArchiveManager manager;
	private FreenetURI key;
	private short archiveType;
	
	public ArchiveHandler(ArchiveManager manager, FreenetURI key, short archiveType) {
		this.manager = manager;
		this.key = key;
		this.archiveType = archiveType;
	}

	public void finalize() {
		// Need to do anything here?
	}

	/**
	 * Get the metadata for this ZIP manifest, as a Bucket.
	 * @throws FetchException If the container could not be fetched.
	 * @throws MetadataParseException If there was an error parsing intermediary metadata.
	 */
	public Bucket getMetadata(ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, fetchContext, dm, recursionLevel);
	}

	/**
	 * Get a file from this ZIP manifest, as a Bucket.
	 * If possible, read it from cache. If necessary, refetch the 
	 * container and extract it. If that fails, throw.
	 * @param inSplitZipManifest If true, indicates that the key points to a splitfile zip manifest,
	 * which means that we need to pass a flag to the fetcher to tell it to pretend it was a straight
	 * splitfile.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 */
	public synchronized Bucket get(String internalName, ArchiveContext archiveContext, FetcherContext fetchContext, ClientMetadata dm, int recursionLevel) throws ArchiveFailureException, ArchiveRestartException, MetadataParseException, FetchException {
		ArchiveElement element = 
			manager.makeElement(key, internalName, archiveType);
		return element.get(fetchContext, dm, recursionLevel, archiveContext);
	}
}
