package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;

public class ArchiveHandlerImpl implements ArchiveHandler {

	private final FreenetURI key;
	private final short archiveType;
	private boolean forceRefetchArchive;
	
	ArchiveHandlerImpl(FreenetURI key, short archiveType, boolean forceRefetchArchive) {
		this.key = key;
		this.archiveType = archiveType;
		this.forceRefetchArchive = forceRefetchArchive;
	}
	
	public Bucket get(String internalName, ArchiveContext archiveContext,
			ClientMetadata dm, int recursionLevel,
			boolean dontEnterImplicitArchives, ArchiveManager manager)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException {
		
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

	public Bucket getMetadata(ArchiveContext archiveContext, ClientMetadata dm,
			int recursionLevel, boolean dontEnterImplicitArchives,
			ArchiveManager manager) throws ArchiveFailureException,
			ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, dm, recursionLevel, dontEnterImplicitArchives, manager);
	}

	public void extractToCache(Bucket bucket, ArchiveContext actx,
			String element, ArchiveExtractCallback callback,
			ArchiveManager manager) throws ArchiveFailureException,
			ArchiveRestartException {
		forceRefetchArchive = false; // now we don't need to force refetch any more
		ArchiveStoreContext ctx = manager.makeContext(key, archiveType, false);
		manager.extractToCache(key, archiveType, bucket, actx, ctx, element, callback);
	}

	public short getArchiveType() {
		return archiveType;
	}

	public FreenetURI getKey() {
		return key;
	}

}
