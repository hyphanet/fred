package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.USK;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
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
	private final int hashCode;
	private final boolean realTimeFlag;
	
	private USKFetcherTag(USK origUSK, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, boolean realTime, ObjectContainer container, FetchContext ctx, boolean keepLastData, long token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
		this.nodeDBHandle = nodeDBHandle;
		this.callback = callback;
		this.origUSK = origUSK;
		this.edition = origUSK.suggestedEdition;
		this.persistent = persistent;
		this.ctx = ctx;
		this.keepLastData = keepLastData;
		this.token = token;
		this.ownFetchContext = hasOwnFetchContext;
		this.realTimeFlag = realTime;
		pollingPriorityNormal = callback.getPollingPriorityNormal();
		pollingPriorityProgress = callback.getPollingPriorityProgress();
		priority = pollingPriorityNormal;
		this.checkStoreOnly = checkStoreOnly;
		this.hashCode = super.hashCode();
		if(logMINOR) Logger.minor(this, "Created tag for "+origUSK+" and "+callback+" : "+this);
	}
	
	@Override
	public int hashCode() {
		return hashCode;
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
	public static USKFetcherTag create(USK usk, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, boolean realTime, 
			ObjectContainer container, FetchContext ctx, boolean keepLast, int token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
		USKFetcherTag tag = new USKFetcherTag(usk, callback, nodeDBHandle, persistent, realTime, container, ctx, keepLast, token, hasOwnFetchContext, checkStoreOnly);
		if(persistent) container.store(tag);
		return tag;
	}
	
	synchronized void updatedEdition(long ed, ObjectContainer container) {
		if(edition < ed) edition = ed;
		if(persistent) container.store(this); // Update
	}

	public void start(USKManager manager, ClientContext context, ObjectContainer container) {
		USK usk = origUSK;
		if(persistent)
			container.activate(origUSK, 5);
		if(usk.suggestedEdition < edition)
			usk = usk.copy(edition);
		else if(persistent) // Copy it to avoid deactivation issues
			usk = usk.copy();
		if(persistent)
			container.activate(ctx, 1);
		fetcher = manager.getFetcher(usk, ctx, new USKFetcherWrapper(usk, priority, realTimeFlag ? USKManager.rcRT : USKManager.rcBulk), keepLastData, checkStoreOnly);
		fetcher.addCallback(this);
		fetcher.schedule(null, context); // non-persistent
		if(logMINOR) Logger.minor(this, "Starting "+fetcher+" for "+this);
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		USKFetcher f = fetcher;
		if(f != null) fetcher.cancel(null, context);
		synchronized(this) {
			if(finished) {
				if(logMINOR) Logger.minor(this, "Already cancelled "+this);
				return;
			}
			finished = true;
		}
		if(f != null)
			Logger.error(this, "cancel() for "+fetcher+" did not set finished on "+this+" ???");
	}

	@Override
	public long getToken() {
		return token;
	}

	@Override
	public void schedule(ObjectContainer container, ClientContext context) {
		start(context.uskManager, context, container);
	}

	@Override
	public void onCancelled(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelled on "+this);
		synchronized(this) {
			finished = true;
		}
		if(persistent && container == null) {
			// If cancelled externally, and this function is called from USKFetcher,
			// container may be null even though we are running on the database thread,
			// resulting in a database leak.
			try {
				context.jobRunner.runBlocking(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(callback, 1);
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
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
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
			callback.onCancelled(container, context);
		}
	}

	@Override
	public void onFailure(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Failed on "+this);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "onFailure called after finish on "+this, new Exception("error"));
				return;
			}
			finished = true;
		}
		if(persistent) {
			if(container != null) {
				container.activate(callback, 1);
				if(callback instanceof USKFetcherTagCallback)
					((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
				callback.onFailure(container, context);
				container.deactivate(callback, 1);
				removeFrom(container, context);
			} else {
			try {
				context.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(USKFetcherTag.this, 1);
						container.activate(callback, 1);
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
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
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
			callback.onFailure(container, context);
		}
	}

	@Override
	public short getPollingPriorityNormal() {
		return pollingPriorityNormal;
	}

	@Override
	public short getPollingPriorityProgress() {
		return pollingPriorityProgress;
	}

	@Override
	public void onFoundEdition(final long l, final USK key, ObjectContainer container, ClientContext context, final boolean metadata, final short codec, final byte[] data, final boolean newKnownGood, final boolean newSlotToo) {
		if(logMINOR) Logger.minor(this, "Found edition "+l+" on "+this);
		synchronized(this) {
			if(fetcher == null) {
				Logger.error(this, "onFoundEdition but fetcher is null - isn't onFoundEdition() terminal for USKFetcherCallback's??", new Exception("debug"));
			}
			if(finished) {
				Logger.error(this, "onFoundEdition called after finish on "+this, new Exception("error"));
				return;
			}
			finished = true;
			fetcher = null;
		}
		if(persistent) {
			if(container != null) {
				container.activate(callback, 1);
				if(callback instanceof USKFetcherTagCallback)
					((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
				callback.onFoundEdition(l, key, container, context, metadata, codec, data, newKnownGood, newSlotToo);
				container.deactivate(callback, 1);
				removeFrom(container, context);
			} else {
			try {
				context.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(callback, 1);
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
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
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, container, context);
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
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
//				logDEBUG = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	@Override
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
