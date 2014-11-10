package freenet.client.async;

import java.io.Serializable;

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
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads.
 * @author toad
 */
class USKFetcherTag implements ClientGetState, USKFetcherCallback, Serializable {

    private static final long serialVersionUID = 1L;
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
	
	private USKFetcherTag(USK origUSK, USKFetcherCallback callback, boolean persistent, boolean realTime, FetchContext ctx, boolean keepLastData, long token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
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
	 * @param persistent
	 * @param container
	 * @param ctx
	 * @param keepLast
	 * @param token
	 * @return
	 */
	public static USKFetcherTag create(USK usk, USKFetcherCallback callback, boolean persistent, boolean realTime, 
			FetchContext ctx, boolean keepLast, int token, boolean hasOwnFetchContext, boolean checkStoreOnly) {
		USKFetcherTag tag = new USKFetcherTag(usk, callback, persistent, realTime, ctx, keepLast, token, hasOwnFetchContext, checkStoreOnly);
		return tag;
	}
	
	synchronized void updatedEdition(long ed) {
		if(edition < ed) edition = ed;
	}

	public void start(USKManager manager, ClientContext context) {
		USK usk = origUSK;
		if(usk.suggestedEdition < edition)
			usk = usk.copy(edition);
		else if(persistent) // Copy it to avoid deactivation issues
			usk = usk.copy();
		fetcher = manager.getFetcher(usk, ctx, new USKFetcherWrapper(usk, priority, realTimeFlag ? USKManager.rcRT : USKManager.rcBulk), keepLastData, checkStoreOnly);
		fetcher.addCallback(this);
		fetcher.schedule(context); // non-persistent
		if(logMINOR) Logger.minor(this, "Starting "+fetcher+" for "+this);
	}

	@Override
	public void cancel(ClientContext context) {
		USKFetcher f = fetcher;
		if(f != null) fetcher.cancel(context);
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
	public void schedule(ClientContext context) {
		start(context.uskManager, context);
	}

	@Override
	public void onCancelled(ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelled on "+this);
		synchronized(this) {
			finished = true;
		}
		if(persistent) {
		    // This can be called from USKFetcher, in which case we want to run on the 
		    // PersistentJobRunner.
			try {
				context.jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
						callback.onCancelled(context);
						return false;
					}
					
				}, NativeThread.HIGH_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Impossible.
			}
		} else {
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
			callback.onCancelled(context);
		}
	}

	@Override
	public void onFailure(ClientContext context) {
		if(logMINOR) Logger.minor(this, "Failed on "+this);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "onFailure called after finish on "+this, new Exception("error"));
				return;
			}
			finished = true;
		}
		if(persistent) {
			try {
				context.jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
						callback.onFailure(context);
						return true;
					}
					
				}, NativeThread.HIGH_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Impossible.
			}
		} else {
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
			callback.onFailure(context);
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
	public void onFoundEdition(final long l, final USK key, ClientContext context, final boolean metadata, final short codec, final byte[] data, final boolean newKnownGood, final boolean newSlotToo) {
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
			try {
				context.jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						if(callback instanceof USKFetcherTagCallback)
							((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
						callback.onFoundEdition(l, key, context, metadata, codec, data, newKnownGood, newSlotToo);
						return false;
					}
					
				}, NativeThread.HIGH_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Impossible.
			}
		} else {
			if(callback instanceof USKFetcherTagCallback)
				((USKFetcherTagCallback)callback).setTag(USKFetcherTag.this, context);
			callback.onFoundEdition(l, key, context, metadata, codec, data, newKnownGood, newSlotToo);
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
    public void onResume(ClientContext context) {
        if(finished) return;
        start(context.uskManager, context);
    }

    @Override
    public void onShutdown(ClientContext context) {
        // Ignore.
    }
	
}
