package freenet.client;

import java.io.Serializable;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

class ArchiveHandlerImpl implements ArchiveHandler, Serializable {

    private static final long serialVersionUID = 1L;
    private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ArchiveHandlerImpl.class);
	}

	private final FreenetURI key;
	private boolean forceRefetchArchive;
	ARCHIVE_TYPE archiveType;
	COMPRESSOR_TYPE compressorType;

	ArchiveHandlerImpl(FreenetURI key, ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE ctype, boolean forceRefetchArchive) {
		this.key = key;
		this.archiveType = archiveType;
		this.compressorType = ctype;
		this.forceRefetchArchive = forceRefetchArchive;
	}

	@Override
	public Bucket get(String internalName, ArchiveContext archiveContext, ArchiveManager manager) {
		if (forceRefetchArchive) {
			return null;
		}
		if (logMINOR) {
			Logger.minor(this, "Checking cache: " + key + ' ' + internalName);
		}
		return manager.getCached(key, internalName);
	}

	@Override
	public Bucket getMetadata(ArchiveContext archiveContext, ArchiveManager manager) {
		return get(".metadata", archiveContext, manager);
	}

	@Override
	public void extractToCache(Bucket bucket, ArchiveContext actx,
			String element, ArchiveExtractCallback callback,
			ArchiveManager manager, ClientContext context) throws ArchiveFailureException {
		forceRefetchArchive = false; // now we don't need to force refetch any more
		manager.extractToCache(key, archiveType, compressorType, bucket, actx, element, callback, context);
	}

	@Override
	public ARCHIVE_TYPE getArchiveType() {
		return archiveType;
	}

	@Override
	public FreenetURI getKey() {
		return key;
	}

	@Override
	public ArchiveHandler cloneHandler() {
		return new ArchiveHandlerImpl(key, archiveType, compressorType, forceRefetchArchive);
	}

}
