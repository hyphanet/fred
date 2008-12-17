package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Not the actual fetcher. Just a tag associating a USK with the client that should be called when
 * the fetch has been done. Can be included in persistent requests. On startup, all USK fetches are
 * restarted, but this remains the same: the actual USKFetcher's are always transient.
 * @author toad
 */
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
	
	private USKFetcherTag(USK origUSK, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, ObjectContainer container, FetchContext ctx, boolean keepLastData, long token) {
		this.nodeDBHandle = nodeDBHandle;
		this.callback = callback;
		this.origUSK = origUSK;
		this.edition = origUSK.suggestedEdition;
		this.persistent = persistent;
		this.ctx = ctx;
		this.keepLastData = keepLastData;
		this.token = token;
		pollingPriorityNormal = callback.getPollingPriorityNormal();
		pollingPriorityProgress = callback.getPollingPriorityProgress();
		priority = pollingPriorityNormal;
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
			ObjectContainer container, FetchContext ctx, boolean keepLast, int token) {
		USKFetcherTag tag = new USKFetcherTag(usk, callback, nodeDBHandle, persistent, container, ctx, keepLast, token);
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
		
	};
	
	public void start(USKManager manager, ClientContext context) {
		USK usk = origUSK;
		if(usk.suggestedEdition < edition)
			usk = usk.copy(edition);
		fetcher = manager.getFetcher(usk, ctx, new USKFetcherWrapper(usk, priority, client), keepLastData);
		fetcher.addCallback(this);
		fetcher.schedule(null, context); // non-persistent
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(fetcher != null) fetcher.cancel(container, context);
		finish(context);
	}

	private void finish(ClientContext context) {
		synchronized(this) {
			finished = true;
		}
		if(persistent) {
			context.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.store(USKFetcherTag.this);
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		}
	}

	public long getToken() {
		return token;
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		start(context.uskManager, context);
	}

	public void onCancelled(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			finished = true;
		}
		if(persistent) {
			context.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(callback, 1);
					callback.onCancelled(container, context);
					container.store(this);
					container.deactivate(callback, 1);
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		} else {
			callback.onCancelled(container, context);
		}
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			finished = true;
		}
		if(persistent) {
			context.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(callback, 1);
					callback.onFailure(container, context);
					container.store(this);
					container.deactivate(callback, 1);
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
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

	public void onFoundEdition(final long l, final USK key, ObjectContainer container, ClientContext context, final boolean metadata, final short codec, final byte[] data) {
		synchronized(this) {
			if(fetcher == null) {
				Logger.error(this, "onFoundEdition but fetcher is null - isn't onFoundEdition() terminal for USKFetcherCallback's??", new Exception("debug"));
			}
			finished = true;
			fetcher = null;
		}
		if(persistent) {
			context.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(callback, 1);
					callback.onFoundEdition(l, key, container, context, metadata, codec, data);
					container.store(this);
					container.deactivate(callback, 1);
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		} else {
			callback.onFoundEdition(l, key, container, context, metadata, codec, data);
		}
	}

	public void removeFromDatabase(ObjectContainer container) {
		container.delete(this);
	}

	public final boolean isFinished() {
		return finished;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.delete(this);
	}
	
}
