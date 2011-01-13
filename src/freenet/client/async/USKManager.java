/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.WeakHashMap;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.NullClientCallback;
import freenet.clients.http.FProxyToadlet;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Executor;
import freenet.support.LRUHashtable;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NullBucket;

/**
 * Tracks the latest version of every known USK.
 * Also does auto-updates.
 * 
 * Note that this is a transient class. It is not stored in the database. All fetchers and subscriptions are likewise transient.
 * 
 * Plugin authors: Don't construct it yourself, get it from ClientContext from NodeClientCore.
 */
public class USKManager {

	private static volatile boolean logDEBUG;
	private static volatile boolean logMINOR;
	
	static RequestClient rcRT = new RequestClient() {

		public boolean persistent() {
			return false;
		}

		public boolean realTimeFlag() {
			return true;
		}

		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
		
	};
	
	static RequestClient rcBulk = new RequestClient() {

		public boolean persistent() {
			return false;
		}

		public boolean realTimeFlag() {
			return false;
		}

		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
		
	};
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	/** Latest version successfully fetched by blanked-edition-number USK */
	final HashMap<USK, Long> latestKnownGoodByClearUSK;
	
	/** Latest SSK slot known to be by the author by blanked-edition-number USK */
	final HashMap<USK, Long> latestSlotByClearUSK;
	
	/** Subscribers by clear USK */
	final HashMap<USK, USKCallback[]> subscribersByClearUSK;
	
	/** Backgrounded USKFetchers by USK. These have pollForever=true and are only
	 * created when subscribe(,true) is called. */
	final HashMap<USK, USKFetcher> backgroundFetchersByClearUSK;
	
	/** Temporary fetchers, started when a USK (with a positive edition number) is 
	 * fetched. These have pollForever=false. Keyed by the clear USK, i.e. one per 
	 * USK, not one per {USK, start edition}, unlike fetchersByUSK. */
	final LRUHashtable<USK, USKFetcher> temporaryBackgroundFetchersLRU;
	
	/** Temporary fetchers where we have been asked to prefetch content. We track
	 * the time we last had a new last-slot, so that if there is no new last-slot
	 * found in 60 seconds, we start prefetching. We delete the entry when the 
	 * fetcher finishes. */
	final WeakHashMap<USK, Long> temporaryBackgroundFetchersPrefetch;
	
	final FetchContext backgroundFetchContext;
	/** This one actually fetches data */
	final FetchContext realFetchContext;
	
	final Executor executor;
	
	private ClientContext context;
	
	public USKManager(NodeClientCore core) {
		HighLevelSimpleClient client = core.makeClient(RequestStarter.UPDATE_PRIORITY_CLASS);
		client.setMaxIntermediateLength(FProxyToadlet.MAX_LENGTH);
		client.setMaxLength(FProxyToadlet.MAX_LENGTH);
		backgroundFetchContext = client.getFetchContext();
		backgroundFetchContext.followRedirects = false;
		realFetchContext = client.getFetchContext();
		latestKnownGoodByClearUSK = new HashMap<USK, Long>();
		latestSlotByClearUSK = new HashMap<USK, Long>();
		subscribersByClearUSK = new HashMap<USK, USKCallback[]>();
		backgroundFetchersByClearUSK = new HashMap<USK, USKFetcher>();
		temporaryBackgroundFetchersLRU = new LRUHashtable<USK, USKFetcher>();
		temporaryBackgroundFetchersPrefetch = new WeakHashMap<USK, Long>();
		executor = core.getExecutor();
	}

	public void init(ClientContext context) {
		this.context = context;
	}

	public void init(ObjectContainer container) {
		if(container != null)
			USKManagerPersistent.init(this, container, context);
	}
	
	/**
	 * Look up the latest known working version of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookupKnownGood(USK usk) {
		Long l = latestKnownGoodByClearUSK.get(usk.clearCopy());
		if(l != null)
			return l.longValue();
		else return -1;
	}

	/**
	 * Look up the latest SSK slot, whether the data it links to has been successfully
	 * fetched or not, of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookupLatestSlot(USK usk) {
		Long l = latestSlotByClearUSK.get(usk.clearCopy());
		if(l != null)
			return l.longValue();
		else return -1;
	}

	public USKFetcherTag getFetcher(USK usk, FetchContext ctx, boolean keepLast, boolean persistent, boolean realTime, 
			USKFetcherCallback callback, boolean ownFetchContext, ObjectContainer container, ClientContext context, boolean checkStoreOnly) {
		return USKFetcherTag.create(usk, callback, context.nodeDBHandle, persistent, realTime, container, ctx, keepLast, 0, ownFetchContext, checkStoreOnly || ctx.localRequestOnly);
	}

	USKFetcher getFetcher(USK usk, FetchContext ctx,
			ClientRequester requester, boolean keepLastData, boolean checkStoreOnly) {
		return new USKFetcher(usk, this, ctx, requester, 3, false, keepLastData, checkStoreOnly);
	}
	
	public USKFetcherTag getFetcherForInsertDontSchedule(USK usk, short prioClass, USKFetcherCallback cb, RequestClient client, ObjectContainer container, ClientContext context, boolean persistent) {
		return getFetcher(usk, persistent ? new FetchContext(backgroundFetchContext, FetchContext.IDENTICAL_MASK, false, null) : backgroundFetchContext, true, client.persistent(), client.realTimeFlag(), cb, true, container, context, false);
	}
	
	/**
	 * A non-authoritative hint that a specific edition *might* exist. At the moment,
	 * we just fetch the block. We do not fetch the contents, and it is possible that
	 * USKFetcher's are also fetching the block. FIXME would it be more efficient to
	 * pass it along to a USKFetcher?
	 * @param usk
	 * @param edition
	 * @param context
	 */
	public void hintUpdate(USK usk, long edition, ClientContext context) {
		if(edition < lookupLatestSlot(usk)) return;
		FreenetURI uri = usk.copy(edition).getURI();
		final ClientGetter get = new ClientGetter(new NullClientCallback(), uri, new FetchContext(backgroundFetchContext, FetchContext.IDENTICAL_MASK, false, null), RequestStarter.UPDATE_PRIORITY_CLASS, rcBulk, new NullBucket(), null);
		try {
			get.start(null, context);
		} catch (FetchException e) {
			// Ignore
		}
	}

	/**
	 * A non-authoritative hint that a specific edition *might* exist. At the moment,
	 * we just fetch the block. We do not fetch the contents, and it is possible that
	 * USKFetcher's are also fetching the block. FIXME would it be more efficient to
	 * pass it along to a USKFetcher?
	 * @param context
	 * @throws MalformedURLException If the uri passed in is not a USK.
	 */
	public void hintUpdate(FreenetURI uri, ClientContext context) throws MalformedURLException {
		if(uri.getSuggestedEdition() < lookupLatestSlot(USK.create(uri))) return;
		final ClientGetter get = new ClientGetter(new NullClientCallback(), uri, new FetchContext(backgroundFetchContext, FetchContext.IDENTICAL_MASK, false, null), RequestStarter.UPDATE_PRIORITY_CLASS, rcBulk, new NullBucket(), null);
		try {
			get.start(null, context);
		} catch (FetchException e) {
			// Ignore
		}
	}

	public void startTemporaryBackgroundFetcher(USK usk, ClientContext context, final FetchContext fctx, boolean prefetchContent, boolean realTimeFlag) {
		final USK clear = usk.clearCopy();
		USKFetcher sched = null;
		Vector<USKFetcher> toCancel = null;
		synchronized(this) {
//			java.util.Iterator i = backgroundFetchersByClearUSK.keySet().iterator();
//			int x = 0;
//			while(i.hasNext()) {
//				System.err.println("Fetcher "+x+": "+i.next());
//				x++;
//			}
			USKFetcher f = temporaryBackgroundFetchersLRU.get(clear);
			if(f == null) {
				f = new USKFetcher(usk, this, backgroundFetchContext, new USKFetcherWrapper(usk, RequestStarter.UPDATE_PRIORITY_CLASS, realTimeFlag ? rcRT : rcBulk), 3, false, false, false);
				sched = f;
				temporaryBackgroundFetchersLRU.push(clear, f);
			} else {
				f.addHintEdition(usk.suggestedEdition);
			}
			if(prefetchContent) {
				long fetchTime = -1;
				// If nothing in 60 seconds, try fetching the last known slot.
				long slot = lookupLatestSlot(clear);
				long good = lookupKnownGood(clear);
				if(slot > -1 && good != slot)
					fetchTime = System.currentTimeMillis();
				temporaryBackgroundFetchersPrefetch.put(clear, fetchTime);
				if(logMINOR) Logger.minor(this, "Prefetch: set "+fetchTime+" for "+clear);
				schedulePrefetchChecker();
			}
			temporaryBackgroundFetchersLRU.push(clear, f);
			while(temporaryBackgroundFetchersLRU.size() > NodeClientCore.getMaxBackgroundUSKFetchers()) {
				USKFetcher fetcher = temporaryBackgroundFetchersLRU.popValue();
				temporaryBackgroundFetchersPrefetch.remove(fetcher.getOriginalUSK().clearCopy());
				if(!fetcher.hasSubscribers()) {
					if(toCancel == null) toCancel = new Vector<USKFetcher>(2);
					toCancel.add(fetcher);
				} else {
					if(logMINOR)
						Logger.minor(this, "Allowing temporary background fetcher to continue as it has subscribers... "+fetcher);
					// It will burn itself out anyway as it's a temp fetcher, so no big harm here.
					fetcher.killOnLoseSubscribers();
				}
			}
		}
		if(toCancel != null) {
			for(int i=0;i<toCancel.size();i++) {
				USKFetcher fetcher = toCancel.get(i);
				fetcher.cancel(null, context);
			}
		}
		if(sched != null) sched.schedule(null, context);
	}
	
	static final int PREFETCH_DELAY = 60*1000;
	
	private void schedulePrefetchChecker() {
		context.ticker.queueTimedJob(prefetchChecker, "Check for USKs to prefetch", PREFETCH_DELAY, false, true);
	}
	
	private final Runnable prefetchChecker = new Runnable() {

		public void run() {
			if(logDEBUG) Logger.debug(this, "Running prefetch checker...");
			ArrayList<USK> toFetch = null;
			long now = System.currentTimeMillis();
			boolean empty = true;
			synchronized(USKManager.this) {
				for(Map.Entry<USK, Long> entry : temporaryBackgroundFetchersPrefetch.entrySet()) {
					empty = false;
					if(entry.getValue() > 0 && now - entry.getValue() >= PREFETCH_DELAY) {
						if(toFetch == null)
							toFetch = new ArrayList<USK>();
						USK clear = entry.getKey();
						long l = lookupLatestSlot(clear);
						if(lookupKnownGood(clear) < l)
							toFetch.add(clear.copy(l));
						entry.setValue(-1L); // Reset counter until new data comes in
					} else {
						if(logMINOR) Logger.minor(this, "Not prefetching: "+entry.getKey()+" : "+entry.getValue());
					}
				}
			}
			if(toFetch == null) return;
			for(final USK key : toFetch) {
				final long l = key.suggestedEdition;
				if(logMINOR) Logger.minor(this, "Prefetching content for background fetch for edition "+l+" on "+key);
				FetchContext fctx = new FetchContext(realFetchContext, FetchContext.IDENTICAL_MASK, false, null);
				final ClientGetter get = new ClientGetter(new ClientGetCallback() {
					
					public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
						if(e.newURI != null) {
							if(logMINOR) Logger.minor(this, "Prefetch succeeded with redirect for "+key);
							updateKnownGood(key, l, context);
							return;
						} else {
							if(logMINOR) Logger.minor(this, "Prefetch failed later: "+e+" for "+key, e);
							// Ignore
						}
					}
					
					public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
						if(logMINOR) Logger.minor(this, "Prefetch succeeded for "+key);
						result.asBucket().free();
						updateKnownGood(key, l, context);
					}
					
					public void onMajorProgress(ObjectContainer container) {
						// Ignore
					}
				}, key.getURI().sskForUSK() /* FIXME add getSSKURI() */, fctx, RequestStarter.UPDATE_PRIORITY_CLASS, rcBulk, new NullBucket(), null);
				try {
					get.start(null, context);
				} catch (FetchException e) {
					if(logMINOR) Logger.minor(this, "Prefetch failed: "+e, e);
					// Ignore
				}
			}
			if(!empty)
				schedulePrefetchChecker();
		}
		
	};

	void updateKnownGood(final USK origUSK, final long number, final ClientContext context) {
		if(logMINOR) Logger.minor(this, "Updating (known good) "+origUSK.getURI()+" : "+number);
		USK clear = origUSK.clearCopy();
		final USKCallback[] callbacks;
		boolean newSlot = false;
		synchronized(this) {
			Long l = latestKnownGoodByClearUSK.get(clear);
			if(logMINOR) Logger.minor(this, "Old known good: "+l);
			if((l == null) || (number > l.longValue())) {
				l = Long.valueOf(number);
				latestKnownGoodByClearUSK.put(clear, l);
				if(logMINOR) Logger.minor(this, "Put "+number);
			} else
				return; // If it's in KnownGood, it will also be in Slot
			
			l = latestSlotByClearUSK.get(clear);
			if(logMINOR) Logger.minor(this, "Old slot: "+l);
			if((l == null) || (number > l.longValue())) {
				l = Long.valueOf(number);
				latestSlotByClearUSK.put(clear, l);
				if(logMINOR) Logger.minor(this, "Put "+number);
				newSlot = true;
			} 
			
			callbacks = subscribersByClearUSK.get(clear);
		}
		if(callbacks != null) {
			// Run off-thread, because of locking, and because client callbacks may take some time
					final USK usk = origUSK.copy(number);
					final boolean newSlotToo = newSlot;
					for(final USKCallback callback : callbacks)
						context.mainExecutor.execute(new Runnable() {
							public void run() {
								callback.onFoundEdition(number, usk, null, // non-persistent
										context, false, (short)-1, null, true, newSlotToo);
							}
						}, "USKManager callback executor for " +callback);
				}
	}
	
	void updateSlot(final USK origUSK, final long number, final ClientContext context) {
		if(logMINOR) Logger.minor(this, "Updating (slot) "+origUSK.getURI()+" : "+number);
		USK clear = origUSK.clearCopy();
		final USKCallback[] callbacks;
		synchronized(this) {
			Long l = latestSlotByClearUSK.get(clear);
			if(logMINOR) Logger.minor(this, "Old slot: "+l);
			if((l == null) || (number > l.longValue())) {
				l = Long.valueOf(number);
				latestSlotByClearUSK.put(clear, l);
				if(logMINOR) Logger.minor(this, "Put "+number);
			} else
				return;
			
			callbacks = subscribersByClearUSK.get(clear);
			if(temporaryBackgroundFetchersPrefetch.containsKey(clear)) {
				temporaryBackgroundFetchersPrefetch.put(clear, System.currentTimeMillis());
				schedulePrefetchChecker();
			}
		}
		if(callbacks != null) {
			// Run off-thread, because of locking, and because client callbacks may take some time
					final USK usk = origUSK.copy(number);
					for(final USKCallback callback : callbacks)
						context.mainExecutor.execute(new Runnable() {
							public void run() {
								callback.onFoundEdition(number, usk, null, // non-persistent
										context, false, (short)-1, null, false, false);
							}
						}, "USKManager callback executor for " +callback);
				}
	}
	
	/**
	 * Subscribe to a given USK. Callback will be notified when it is
	 * updated. Note that this does not imply that the USK will be
	 * checked on a regular basis, unless runBackgroundFetch=true.
	 */
	public void subscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch, RequestClient client) {
		if(logMINOR) Logger.minor(this, "Subscribing to "+origUSK+" for "+cb);
		if(client.persistent()) throw new UnsupportedOperationException("USKManager subscriptions cannot be persistent");
		USKFetcher sched = null;
		long ed = origUSK.suggestedEdition;
		if(ed < 0) {
			Logger.error(this, "Subscribing to USK with negative edition number: "+ed);
			ed = -ed;
		}
		long curEd;
		curEd = lookupLatestSlot(origUSK);
		long goodEd;
		goodEd = lookupKnownGood(origUSK);
		synchronized(this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = subscribersByClearUSK.get(clear);
			if(callbacks == null) {
				callbacks = new USKCallback[] { cb };
			} else {
				boolean mustAdd = true;
				for(int i=0;i<callbacks.length;i++) {
					if(callbacks[i] == cb) {
						// Already subscribed.
						// But it may still be waiting for the callback.
						if(!(curEd > ed || goodEd > ed)) return;
						mustAdd = false;
					}
				}
				if(mustAdd) {
					USKCallback[] newCallbacks = new USKCallback[callbacks.length+1];
					System.arraycopy(callbacks, 0, newCallbacks, 0, callbacks.length);
					newCallbacks[callbacks.length] = cb;
					callbacks = newCallbacks;
				}
			}
			subscribersByClearUSK.put(clear, callbacks);
			if(runBackgroundFetch) {
				USKFetcher f = backgroundFetchersByClearUSK.get(clear);
				if(f == null) {
					f = new USKFetcher(origUSK, this, backgroundFetchContext, new USKFetcherWrapper(origUSK, RequestStarter.UPDATE_PRIORITY_CLASS, client), 5, true, false, false);
					sched = f;
					backgroundFetchersByClearUSK.put(clear, f);
				}
				f.addSubscriber(cb, origUSK.suggestedEdition);
			}
		}
		if(goodEd > ed)
			cb.onFoundEdition(goodEd, origUSK.copy(curEd), null, context, false, (short)-1, null, true, curEd > ed);
		else if(curEd > ed)
			cb.onFoundEdition(curEd, origUSK.copy(curEd), null, context, false, (short)-1, null, false, false);
		final USKFetcher fetcher = sched;
		if(fetcher != null) {
			executor.execute(new Runnable() {
				public void run() {
					fetcher.schedule(null, context);
				}
			}, "USKManager.schedule for "+fetcher);
		}
	}
	
	public void unsubscribe(USK origUSK, USKCallback cb) {
		USKFetcher toCancel = null;
		synchronized(this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = subscribersByClearUSK.get(clear);
			if(callbacks == null){ // maybe we should throw something ? shall we allow multiple unsubscriptions ?
				if(logMINOR) Logger.minor(this, "No longer subscribed");
				return;
			}
			int j=0;
			for(int i=0;i<callbacks.length;i++) {
				USKCallback c = callbacks[i];
				if((c != null) && (c != cb)) {
					callbacks[j++] = c;
				}
			}
			USKCallback[] newCallbacks = new USKCallback[j];
			System.arraycopy(callbacks, 0, newCallbacks, 0, j);
			if(newCallbacks.length > 0)
				subscribersByClearUSK.put(clear, newCallbacks);
			else{
				subscribersByClearUSK.remove(clear);
			}
			USKFetcher f = backgroundFetchersByClearUSK.get(clear);
			if(f != null) {
				f.removeSubscriber(cb, context);
				if(!f.hasSubscribers()) {
						toCancel = f;
						backgroundFetchersByClearUSK.remove(clear);
				}
			}
			// Temporary background fetchers run once and then die.
			// They do not care about callbacks.
		}
		if(toCancel != null) toCancel.cancel(null, context);
	}
	
	/**
	 * Subscribe to a USK. When it is updated, the content will be fetched (subject to the limits in fctx),
	 * and returned to the callback.
	 * @param origUSK The USK to poll.
	 * @param cb Callback, called when we have downloaded a new key.
	 * @param runBackgroundFetch If true, start a background fetcher for the key, which will run
	 * forever until we unsubscribe.
	 * @param fctx Fetcher context for actually fetching the keys. Not used by the USK polling.
	 * @param prio Priority for fetching the content (see constants in RequestScheduler).
	 * @return
	 */
	public USKRetriever subscribeContent(USK origUSK, USKRetrieverCallback cb, boolean runBackgroundFetch, FetchContext fctx, short prio, RequestClient client) {
		USKRetriever ret = new USKRetriever(fctx, prio, client, cb, origUSK);
		subscribe(origUSK, ret, runBackgroundFetch, client);
		return ret;
	}
	
	public void unsubscribeContent(USK origUSK, USKRetriever ret, boolean runBackgroundFetch) {
		unsubscribe(origUSK, ret);
	}
	
	// REMOVE: DO NOT Synchronize! ... debugging only.
	/**
	 * The result of that method will be displayed on the Statistic Toadlet : it will help catching #1147 
	 * Afterwards it should be removed: it's not usefull :)
	 * @return the number of BackgroundFetchers started by USKManager
	 */
	public int getBackgroundFetcherByUSKSize(){
		return backgroundFetchersByClearUSK.size();
	}
	
	/**
	 * The result of that method will be displayed on the Statistic Toadlet : it will help catching #1147 
	 * Afterwards it should be removed: it's not usefull :)
	 * @return the size of temporaryBackgroundFetchersLRU
	 */
	public int getTemporaryBackgroundFetchersLRU(){
		return temporaryBackgroundFetchersLRU.size();
	}

	public void onFinished(USKFetcher fetcher) {
		onFinished(fetcher, false);
	}
	
	public void onFinished(USKFetcher fetcher, boolean ignoreError) {
		USK orig = fetcher.getOriginalUSK();
		USK clear = orig.clearCopy();
		synchronized(this) {
			if(backgroundFetchersByClearUSK.get(clear) == fetcher) {
				backgroundFetchersByClearUSK.remove(clear);
				if(!ignoreError) {
					// This shouldn't happen, it's a sanity check: the only way we get cancelled is from USKManager, which removes us before calling cancel().
					Logger.error(this, "onCancelled for "+fetcher+" - was still registered, how did this happen??", new Exception("debug"));
				}
			}
			if(temporaryBackgroundFetchersLRU.get(clear) == fetcher) {
				temporaryBackgroundFetchersLRU.removeKey(clear);
				temporaryBackgroundFetchersPrefetch.remove(clear);
			}
		}
	}

	public boolean persistent() {
		return false;
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
}
