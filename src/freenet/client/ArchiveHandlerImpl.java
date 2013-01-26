package freenet.client;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class ArchiveHandlerImpl implements ArchiveHandler {

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
	public Bucket get(String internalName, ArchiveContext archiveContext,
			ArchiveManager manager, ObjectContainer container)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException {

		if(forceRefetchArchive) return null;

		Bucket data;

		// Fetch from cache
		if(logMINOR)
			Logger.minor(this, "Checking cache: "+key+ ' ' +internalName);
		if((data = manager.getCached(key, internalName)) != null) {
			return data;
		}

		return null;
	}

	@Override
	public Bucket getMetadata(ArchiveContext archiveContext,
			ArchiveManager manager, ObjectContainer container) throws ArchiveFailureException,
			ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, manager, container);
	}

	@Override
	public void extractToCache(Bucket bucket, ArchiveContext actx,
			String element, ArchiveExtractCallback callback,
			ArchiveManager manager, ObjectContainer container, ClientContext context) throws ArchiveFailureException,
			ArchiveRestartException {
		forceRefetchArchive = false; // now we don't need to force refetch any more
		ArchiveStoreContext ctx = manager.makeContext(key, archiveType, compressorType, false);
		manager.extractToCache(key, archiveType, compressorType, bucket, actx, ctx, element, callback, container, context);
	}

	@Override
	public ARCHIVE_TYPE getArchiveType() {
		return archiveType;
	}

	public COMPRESSOR_TYPE getCompressorType() {
		return compressorType;
	}

	@Override
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
	@Override
	public void extractPersistentOffThread(Bucket bucket, boolean freeBucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ObjectContainer container, final ClientContext context) {
		assert(element != null); // no callback would be called...
		final ArchiveManager manager = context.archiveManager;
		final ArchiveExtractTag tag = new ArchiveExtractTag(this, bucket, freeBucket, actx, element, callback, context.nodeDBHandle);
		container.store(tag);
		runPersistentOffThread(tag, context, manager, context.persistentBucketFactory);
	}

	private static void runPersistentOffThread(final ArchiveExtractTag tag, final ClientContext context, final ArchiveManager manager, final BucketFactory bf) {
		final ProxyCallback proxyCallback = new ProxyCallback();

		if(logMINOR)
			Logger.minor(ArchiveHandlerImpl.class, "Scheduling off-thread extraction: "+tag.data+" for "+tag.handler.key+" element "+tag.element+" for "+tag.callback, new Exception("debug"));

		context.mainExecutor.execute(new Runnable() {

			@Override
			public void run() {
				try {
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

						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							if(logMINOR)
								Logger.minor(this, "Calling callback for "+tag.data+" for "+tag.handler.key+" element "+tag.element+" for "+tag.callback);
							container.activate(tag.callback, 1);
							if(proxyCallback.data == null)
								tag.callback.notInArchive(container, context);
							else
								tag.callback.gotBucket(data, container, context);
							tag.callback.removeFrom(container);
							if(tag.freeBucket) {
								tag.data.free();
								tag.data.removeFrom(container);
							}
							container.deactivate(tag.callback, 1);
							container.delete(tag);
							return false;
						}

					}, NativeThread.NORM_PRIORITY, false);

				} catch (final ArchiveFailureException e) {

					try {
						context.jobRunner.queue(new DBJob() {

							@Override
							public boolean run(ObjectContainer container, ClientContext context) {
								container.activate(tag.callback, 1);
								tag.callback.onFailed(e, container, context);
								tag.callback.removeFrom(container);
								if(tag.freeBucket) {
									tag.data.free();
									tag.data.removeFrom(container);
								}
								container.delete(tag);
								return false;
							}

						}, NativeThread.NORM_PRIORITY, false);
					} catch (DatabaseDisabledException e1) {
						Logger.error(this, "Extracting off thread but persistence is disabled");
					}

				} catch (final ArchiveRestartException e) {

					try {
						context.jobRunner.queue(new DBJob() {

							@Override
							public boolean run(ObjectContainer container, ClientContext context) {
								container.activate(tag.callback, 1);
								tag.callback.onFailed(e, container, context);
								tag.callback.removeFrom(container);
								if(tag.freeBucket) {
									tag.data.free();
									tag.data.removeFrom(container);
								}
								container.delete(tag);
								return false;
							}

						}, NativeThread.NORM_PRIORITY, false);
					} catch (DatabaseDisabledException e1) {
						Logger.error(this, "Extracting off thread but persistence is disabled");
					}

				} catch (DatabaseDisabledException e) {
					Logger.error(this, "Extracting off thread but persistence is disabled");
				}
			}

		}, "Off-thread extract");
	}

	/** Called from ArchiveManager.init() */
	static void init(ObjectContainer container, ClientContext context, final long nodeDBHandle) {
		ObjectSet<ArchiveExtractTag> set = container.query(new Predicate<ArchiveExtractTag>() {
			final private static long serialVersionUID = 5769839072558476040L;
			@Override
			public boolean match(ArchiveExtractTag tag) {
				return tag.nodeDBHandle == nodeDBHandle;
			}
		});
		while(set.hasNext()) {
			ArchiveExtractTag tag = set.next();
			if(tag.checkBroken(container, context)) continue;
			tag.activateForExecution(container);
			runPersistentOffThread(tag, context, context.archiveManager, context.persistentBucketFactory);
		}
	}

	private static class ProxyCallback implements ArchiveExtractCallback {

		Bucket data;

		@Override
		public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
			this.data = data;
		}

		@Override
		public void notInArchive(ObjectContainer container, ClientContext context) {
			this.data = null;
		}

		@Override
		public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}

		@Override
		public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeFrom(ObjectContainer container) {
			container.delete(this);
		}

	}

	@Override
	public void activateForExecution(ObjectContainer container) {
		container.activate(this, 1);
		container.activate(key, 5);
	}

	@Override
	public ArchiveHandler cloneHandler() {
		return new ArchiveHandlerImpl(key.clone(), archiveType, compressorType, forceRefetchArchive);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		if(key == null) {
			Logger.error(this, "removeFrom() : key = null for "+this+" I exist = "+container.ext().isStored(this)+" I am active: "+container.ext().isActive(this), new Exception("error"));
		} else
			key.removeFrom(container);
		container.delete(this);
	}

}
