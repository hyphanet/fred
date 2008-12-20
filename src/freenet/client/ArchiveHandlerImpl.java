package freenet.client;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

class ArchiveHandlerImpl implements ArchiveHandler {

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
			ArchiveManager manager, ObjectContainer container, ClientContext context) throws ArchiveFailureException,
			ArchiveRestartException {
		forceRefetchArchive = false; // now we don't need to force refetch any more
		ArchiveStoreContext ctx = manager.makeContext(key, archiveType, compressorType, false);
		manager.extractToCache(key, archiveType, compressorType, bucket, actx, ctx, element, callback, container, context);
	}

	public ARCHIVE_TYPE getArchiveType() {
		return archiveType;
	}
	
	public COMPRESSOR_TYPE getCompressorType() {
		return compressorType;
	}

	public FreenetURI getKey() {
		return key;
	}

	/**
	 * Unpack a fetched archive on a separate thread for a persistent caller.
	 * This involves:
	 * - Add a tag to the database so that it will be restarted on a crash.
	 * - Run the actual unpack on a separate thread.
	 * - Copy the data to a persistent bucket.
	 * - Schedule a database job.
	 * - Call the callback.
	 * @param bucket
	 * @param actx
	 * @param element
	 * @param callback
	 * @param container
	 * @param context
	 */
	public void extractPersistentOffThread(Bucket bucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ObjectContainer container, final ClientContext context) {
		assert(element != null); // no callback would be called...
		final ArchiveManager manager = context.archiveManager;
		final ArchiveExtractTag tag = new ArchiveExtractTag(this, bucket, actx, element, callback, context.nodeDBHandle);
		container.store(tag);
		runPersistentOffThread(tag, context, manager, context.persistentBucketFactory);
	}

	private static void runPersistentOffThread(final ArchiveExtractTag tag, final ClientContext context, final ArchiveManager manager, final BucketFactory bf) {
		final ProxyCallback proxyCallback = new ProxyCallback();
		
		if(Logger.shouldLog(Logger.MINOR, ArchiveHandlerImpl.class))
			Logger.minor(ArchiveHandlerImpl.class, "Scheduling off-thread extraction: "+tag.data+" for "+tag.handler.key+" element "+tag.element+" for "+tag.callback);
		
		context.mainExecutor.execute(new Runnable() {

			public void run() {
				try {
					final boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
					if(logMINOR)
						Logger.minor(this, "Extracting off-thread: "+tag.data+" for "+tag.handler.key+" element "+tag.element+" for "+tag.callback);
					tag.handler.extractToCache(tag.data, tag.actx, tag.element, proxyCallback, manager, null, context);
					if(logMINOR)
						Logger.minor(this, "Extracted");
					final Bucket data;
					if(proxyCallback.data == null)
						data = null;
					else {
						try {
							if(logMINOR)
								Logger.minor(this, "Copying data...");
							data = bf.makeBucket(proxyCallback.data.size());
							BucketTools.copy(proxyCallback.data, data);
							proxyCallback.data.free();
							if(logMINOR)
								Logger.minor(this, "Copied and freed original");
						} catch (IOException e) {
							throw new ArchiveFailureException("Failure copying data to persistent storage", e);
						}
					}
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							if(logMINOR)
								Logger.minor(this, "Calling callback for "+tag.data+" for "+tag.handler.key+" element "+tag.element+" for "+tag.callback);
							container.delete(tag);
							container.activate(tag.callback, 1);
							if(proxyCallback.data == null)
								tag.callback.notInArchive(container, context);
							else
								tag.callback.gotBucket(data, container, context);
							container.deactivate(tag.callback, 1);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				} catch (final ArchiveFailureException e) {
					
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(tag);
							container.activate(tag.callback, 1);
							tag.callback.onFailed(e, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				} catch (final ArchiveRestartException e) {
					
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(tag);
							container.activate(tag.callback, 1);
							tag.callback.onFailed(e, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				}
			}
			
		}, "Off-thread extract");
	}

	/** Called from ArchiveManager.init() */
	static void init(ObjectContainer container, ClientContext context, final long nodeDBHandle) {
		ObjectSet<ArchiveExtractTag> set = container.query(new Predicate<ArchiveExtractTag>() {
			public boolean match(ArchiveExtractTag tag) {
				return tag.nodeDBHandle == nodeDBHandle;
			}
		});
		while(set.hasNext()) {
			ArchiveExtractTag tag = set.next();
			tag.activateForExecution(container);
			runPersistentOffThread(tag, context, context.archiveManager, context.persistentBucketFactory);
		}
	}
	
	private static class ProxyCallback implements ArchiveExtractCallback {

		Bucket data;
		
		public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
			this.data = data;
		}

		public void notInArchive(ObjectContainer container, ClientContext context) {
			this.data = null;
		}

		public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}

		public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}
		
	}

	public void activateForExecution(ObjectContainer container) {
		container.activate(this, 1);
		container.activate(key, 5);
	}

	public ArchiveHandler cloneHandler() {
		return new ArchiveHandlerImpl(key.clone(), archiveType, compressorType, forceRefetchArchive);
	}

	public void removeFrom(ObjectContainer container) {
		key.removeFrom(container);
		container.delete(this);
	}
	
}

class ArchiveExtractTag {
	
	final ArchiveHandlerImpl handler;
	final Bucket data;
	final ArchiveContext actx;
	final String element;
	final ArchiveExtractCallback callback;
	final long nodeDBHandle;
	
	ArchiveExtractTag(ArchiveHandlerImpl handler, Bucket data, ArchiveContext actx, String element, ArchiveExtractCallback callback, long nodeDBHandle) {
		this.handler = handler;
		this.data = data;
		this.actx = actx;
		this.element = element;
		this.callback = callback;
		this.nodeDBHandle = nodeDBHandle;
	}

	public void activateForExecution(ObjectContainer container) {
		container.activate(this, 1);
		container.activate(data, 5);
		handler.activateForExecution(container);
		container.activate(actx, 5);
		container.activate(callback, 1);
	}
	
}