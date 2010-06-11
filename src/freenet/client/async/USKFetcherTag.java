package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Not the actual fetcher. Just a tag associating a USK with the client that should be called when
 * the fetch has been done. Can be included in persistent requests. On startup, all USK fetches are
 * restarted, but this remains the same: the actual USKFetcher's are always transient.
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class USKFetcherTag implements ClientGetState, USKFetcherCallback {

	/** For persistence */
	public final long nodeDBHandle;
	/** The callback */
	public final USKFetcherCallback callback;
	/** The original USK */
	public final USK origUSK;
	/** The edition number found so far */
	protected long edition;
	/** Persistent?? */
	public final boolean persistent;
	/** Context */
	public final FetchContext ctx;
	public final boolean keepLastData;
	/** Priority */
	private short priority;
	private long token;
	private transient USKFetcher fetcher;
	private short pollingPriorityNormal;
	private short pollingPriorityProgress;
	private boolean finished;
	private final boolean ownFetchContext;
	private final boolean checkStoreOnly;
	
	private USKFetcherTag(USK origUSK, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, ObjectContainer container, FetchContext ctx, boolean keepLastData, long token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
		this.nodeDBHandle = nodeDBHandle;
		this.callback = callback;
		this.origUSK = origUSK;
		this.edition = origUSK.suggestedEdition;
		this.persistent = persistent;
		this.ctx = ctx;
		this.keepLastData = keepLastData;
		this.token = token;
		this.ownFetchContext = hasOwnFetchContext;
		pollingPriorityNormal = callback.getPollingPriorityNormal();
		pollingPriorityProgress = callback.getPollingPriorityProgress();
		priority = pollingPriorityNormal;
		this.checkStoreOnly = checkStoreOnly;
	}
	
	/**
	 * For a persistent request, the caller must call removeFromDatabase() when finished. Note that the caller is responsible for
	 * deleting the USKFetcherCallback and the FetchContext.
	 * @param usk
	 * @param callback
	 * @param nodeDBHandle
	 * @param persistent
	 * @param container
	 * @param ctx
	 * @param keepLast
	 * @param token
	 * @return
	 */
	public static USKFetcherTag create(USK usk, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, 
			ObjectContainer container, FetchContext ctx, boolean keepLast, int token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
		USKFetcherTag tag = new USKFetcherTag(usk, callback, nodeDBHandle, persistent, container, ctx, keepLast, token, hasOwnFetchContext, checkStoreOnly);
		if(persistent) container.store(tag);
		return tag;
	}
	
	synchronized void updatedEdition(long ed, ObjectContainer container) {
		if(edition < ed) edition = ed;
		if(persistent) container.store(this); // Update
	}

	private static final RequestClient client = new RequestClient() {

		public boolean persistent() {
			// The actual USK fetch is non-persistent, only the tags survive a restart.
			return false;
		}

		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
		
	};
	
	public void start(USKManager manager, ClientContext context, ObjectContainer container) {
		USK usk = origUSK;
		if(persistent)
			container.activate(origUSK, 5);
		if(usk.suggestedEdition < edition)
			usk = usk.copy(edition);
		else if(persistent) // Copy it to avoid deactivation issues
			usk = usk.clone();
		fetcher = manager.getFetcher(usk, ctx, new USKFetcherWrapper(usk, priority, client), keepLastData, checkStoreOnly);
		fetcher.addCallback(this);
		fetcher.schedule(null, context); // non-persistent
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(fetcher != null) fetcher.cancel(null, context);
		synchronized(this) {
			finished = true;
		}
		// onCancelled() will removeFrom(), so we do NOT want to store(this)
	}

	public long getToken() {
		return token;
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		start(context.uskManager, context, container);
	}

	public void onCancelled(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			finished = true;
		}
		if(persistent && container == null) {
			// If cancelled externally, and this function is called from USKFetcher,
			// container may be null even though we are running on the database thread,
			// resulting in a database leak.
			try {
				context.jobRunner.runBlocking(new DBJob() {

					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(callback, 1);
						callback.onCancelled(container, context);
						removeFrom(container, context);
						container.deactivate(callback, 1);
						return false;
					}
					
				}, NativeThread.HIGH_PRIORITY);
			} catch (DatabaseDisabledException e) {
				// Impossible.
			}
		} else {
			callback.onCancelled(container, context);
		}
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			finished = true;
		}
		if(persistent) {
			if(container != null) {
				container.activate(callback, 1);
				callback.onFailure(container, context);
				container.deactivate(callback, 1);
				removeFrom(container, context);
			} else {
			try {
				context.jobRunner.queue(new DBJob() {

					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(callback, 1);
						callback.onFailure(container, context);
						container.deactivate(callback, 1);
						removeFrom(container, context);
						return true;
					}
					
				}, NativeThread.HIGH_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Impossible.
			}
			}
		} else {
			callback.onFailure(container, context);
		}
	}

	public short getPollingPriorityNormal() {
		return pollingPriorityNormal;
	}

	public short getPollingPriorityProgress() {
		return pollingPriorityProgress;
	}

	public void onFoundEdition(final long l, final USK key, ObjectContainer container, ClientContext context, final boolean metadata, final short codec, final byte[] data, final boolean newKnownGood, final boolean newSlotToo) {
		synchronized(this) {
			if(fetcher == null) {
				Logger.error(this, "onFoundEdition but fetcher is null - isn't onFoundEdition() terminal for USKFetcherCallback's??", new Exception("debug"));
			}
			finished = true;
			fetcher = null;
		}
		if(persistent) {
			if(container != null) {
				container.activate(callback, 1);
				callback.onFoundEdition(l, key, container, context, metadata, codec, data, newKnownGood, newSlotToo);
				container.deactivate(callback, 1);
				removeFrom(container, context);
			} else {
			try {
				context.jobRunner.queue(new DBJob() {

					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(callback, 1);
						callback.onFoundEdition(l, key, container, context, metadata, codec, data, newKnownGood, newSlotToo);
						container.deactivate(callback, 1);
						removeFrom(container, context);
						return false;
					}
					
				}, NativeThread.HIGH_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Impossible.
			}
			}
		} else {
			callback.onFoundEdition(l, key, container, context, metadata, codec, data, newKnownGood, newSlotToo);
		}
	}

	public final boolean isFinished() {
		return finished;
	}

	private static volatile boolean logMINOR;
//	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
//				logDEBUG = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}
	
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Removing "+this);
		container.activate(origUSK, 5);
		origUSK.removeFrom(container);
		if(ownFetchContext) {
			container.activate(ctx, 1);
			ctx.removeFrom(container);
		}
		container.delete(this);
	}
	
	public boolean objectCanDeactivate(ObjectContainer container) {
		return false;
	}
	
//	public void objectOnNew(ObjectContainer container) {
//		if(logDEBUG) Logger.debug(this, "Storing as new: "+this);
//	}
//	
//	public void objectOnUpdate(ObjectContainer container) {
//		if(logDEBUG) Logger.debug(this, "Updating: "+this, new Exception("debug"));
//	}
//	
}
