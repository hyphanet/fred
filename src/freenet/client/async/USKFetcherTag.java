package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.USK;
import freenet.node.RequestClient;

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
	transient USKFetcher fetcher;
	
	private USKFetcherTag(USK origUSK, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, ObjectContainer container, FetchContext ctx, boolean keepLastData, long token) {
		this.nodeDBHandle = nodeDBHandle;
		this.callback = callback;
		this.origUSK = origUSK;
		this.edition = origUSK.suggestedEdition;
		this.persistent = persistent;
		this.ctx = ctx;
		this.keepLastData = keepLastData;
		this.token = token;
	}
	
	public static USKFetcherTag create(USK usk, USKFetcherCallback callback, long nodeDBHandle, boolean persistent, 
			ObjectContainer container, FetchContext ctx, boolean keepLast, int token) {
		USKFetcherTag tag = new USKFetcherTag(usk, callback, nodeDBHandle, persistent, container, ctx, keepLast, token);
		if(persistent) container.set(tag);
		return tag;
	}
	
	synchronized void updatedEdition(long ed, ObjectContainer container) {
		if(edition < ed) edition = ed;
		if(persistent) container.set(this); // Update
	}

	private static final RequestClient client = new RequestClient() {

		public boolean persistent() {
			// The actual USK fetch is non-persistent, only the tags survive a restart.
			return false;
		}
		
	};
	
	public void start(USKManager manager, ObjectContainer container, ClientContext context) {
		USK usk = origUSK;
		if(usk.suggestedEdition < edition)
			usk = usk.copy(edition);
		fetcher = manager.getFetcher(usk, ctx, new USKFetcherWrapper(usk, priority, client), keepLastData);
		fetcher.addCallback(this);
		fetcher.schedule(container, context);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(fetcher != null) fetcher.cancel(container, context);
	}

	public long getToken() {
		return token;
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		start(context.uskManager, container, context);
	}

	public void onCancelled(ObjectContainer container, ClientContext context) {
		callback.onCancelled(container, context);
		if(persistent)
			container.delete(this);
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		callback.onFailure(container, context);
		if(persistent)
			container.delete(this);
	}

	public short getPollingPriorityNormal() {
		return callback.getPollingPriorityNormal();
	}

	public short getPollingPriorityProgress() {
		return callback.getPollingPriorityProgress();
	}

	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data) {
		callback.onFoundEdition(l, key, container, context, metadata, codec, data);
		if(persistent)
			container.delete(this);
	}

}
