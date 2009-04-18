/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.NullClientCallback;
import freenet.clients.http.FProxyToadlet;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Executor;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.io.NullBucket;

/**
 * Tracks the latest version of every known USK.
 * Also does auto-updates.
 * 
 * Note that this is a transient class. It is not stored in the database. All fetchers and subscriptions are likewise transient.
 */
public class USKManager implements RequestClient {

	/** Latest version successfully fetched by blanked-edition-number USK */
	final HashMap<USK, Long> latestKnownGoodByClearUSK;
	
	/** Latest SSK slot known to be by the author by blanked-edition-number USK */
	final HashMap<USK, Long> latestSlotByClearUSK;
	
	/** Subscribers by clear USK */
	final HashMap<USK, USKCallback[]> subscribersByClearUSK;
	
	/** USKFetcher's by USK. USK includes suggested edition number, so there is one
	 * USKFetcher for each {USK, edition number}. */
	final HashMap<USK, USKFetcher> fetchersByUSK;
	
	/** Backgrounded USKFetchers by USK. */
	final HashMap<USK, USKFetcher> backgroundFetchersByClearUSK;
	
	/** These must be TEMPORARY, that is, they must NOT poll forever! */
	final LRUQueue<USK> temporaryBackgroundFetchersLRU;
	
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
		fetchersByUSK = new HashMap<USK, USKFetcher>();
		backgroundFetchersByClearUSK = new HashMap<USK, USKFetcher>();
		temporaryBackgroundFetchersLRU = new LRUQueue<USK>();
		executor = core.getExecutor();
	}

	public void init(ObjectContainer container, ClientContext context) {
		this.context = context;
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

	public USKFetcherTag getFetcher(USK usk, FetchContext ctx, boolean keepLast, boolean persistent, 
			USKFetcherCallback callback, boolean ownFetchContext, ObjectContainer container, ClientContext context) {
		return USKFetcherTag.create(usk, callback, context.nodeDBHandle, persistent, container, ctx, keepLast, 0, ownFetchContext);
	}

	synchronized USKFetcher getFetcher(USK usk, FetchContext ctx,
			ClientRequester requester, boolean keepLastData) {
		USKFetcher f = fetchersByUSK.get(usk);
		USK clear = usk.clearCopy();
		if(temporaryBackgroundFetchersLRU.contains(clear))
			temporaryBackgroundFetchersLRU.push(clear);
		if(f != null) {
			if((f.parent.priorityClass == requester.priorityClass) && f.ctx.equals(ctx) && f.keepLastData == keepLastData)
				return f;
		}
		f = new USKFetcher(usk, this, ctx, requester, 3, false, keepLastData);
		fetchersByUSK.put(usk, f);
		return f;
	}
	
	public USKFetcherTag getFetcherForInsertDontSchedule(USK usk, short prioClass, USKFetcherCallback cb, RequestClient client, ObjectContainer container, ClientContext context, boolean persistent) {
		return getFetcher(usk, persistent ? new FetchContext(backgroundFetchContext, FetchContext.IDENTICAL_MASK, false, null) : backgroundFetchContext, true, client.persistent(), cb, true, container, context);
	}

	public void startTemporaryBackgroundFetcher(USK usk, ClientContext context, final FetchContext fctx, boolean prefetchContent) {
		USK clear = usk.clearCopy();
		USKFetcher sched = null;
		Vector<USKFetcher> toCancel = null;
		synchronized(this) {
//			java.util.Iterator i = backgroundFetchersByClearUSK.keySet().iterator();
//			int x = 0;
//			while(i.hasNext()) {
//				System.err.println("Fetcher "+x+": "+i.next());
//				x++;
//			}
			USKFetcher f = backgroundFetchersByClearUSK.get(clear);
			if(f == null) {
				f = new USKFetcher(usk, this, backgroundFetchContext, new USKFetcherWrapper(usk, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, this), 3, false, false);
				sched = f;
				backgroundFetchersByClearUSK.put(clear, f);
			}
			if(prefetchContent)
				f.addCallback(new USKFetcherCallback() {

					public void onCancelled(ObjectContainer container, ClientContext context) {
						// Ok
					}

					public void onFailure(ObjectContainer container, ClientContext context) {
						// Ok
					}

					public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
						FreenetURI uri = key.copy(l).getURI();
						final ClientGetter get = new ClientGetter(new NullClientCallback(), uri, new FetchContext(fctx, FetchContext.IDENTICAL_MASK, false, null), RequestStarter.UPDATE_PRIORITY_CLASS, USKManager.this, new NullBucket(), null);
						try {
							get.start(null, context);
						} catch (FetchException e) {
							// Ignore
						}
					}

					public short getPollingPriorityNormal() {
						return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
					}

					public short getPollingPriorityProgress() {
						return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
					}
					
					
				});
			temporaryBackgroundFetchersLRU.push(clear);
			while(temporaryBackgroundFetchersLRU.size() > NodeClientCore.maxBackgroundUSKFetchers) {
				USK del = temporaryBackgroundFetchersLRU.pop();
				USKFetcher fetcher = backgroundFetchersByClearUSK.get(del.clearCopy());
				if(!fetcher.hasSubscribers()) {
					if(toCancel == null) toCancel = new Vector<USKFetcher>(2);
					toCancel.add(fetcher);
					backgroundFetchersByClearUSK.remove(del);
				} else {
					if(Logger.shouldLog(Logger.MINOR, this))
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
	
	void updateKnownGood(final USK origUSK, final long number, final ClientContext context) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Updating "+origUSK.getURI()+" : "+number);
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
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Updating "+origUSK.getURI()+" : "+number);
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
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Subscribing to "+origUSK+" for "+cb);
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
			if(callbacks == null)
				callbacks = new USKCallback[1];
			else {
				for(int i=0;i<callbacks.length;i++)
					if(callbacks[i] == cb) return;
				USKCallback[] newCallbacks = new USKCallback[callbacks.length+1];
				System.arraycopy(callbacks, 0, newCallbacks, 0, callbacks.length);
				callbacks = newCallbacks;
			}
			callbacks[callbacks.length-1] = cb;
			subscribersByClearUSK.put(clear, callbacks);
			if(runBackgroundFetch) {
				USKFetcher f = backgroundFetchersByClearUSK.get(clear);
				if(f == null) {
					f = new USKFetcher(origUSK, this, backgroundFetchContext, new USKFetcherWrapper(origUSK, RequestStarter.UPDATE_PRIORITY_CLASS, client), 10, true, false);
					sched = f;
					backgroundFetchersByClearUSK.put(clear, f);
				}
				f.addSubscriber(cb);
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
	
	public void unsubscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch) {
		USKFetcher toCancel = null;
		synchronized(this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = subscribersByClearUSK.get(clear);
			if(callbacks == null){ // maybe we should throw something ? shall we allow multiple unsubscriptions ?
				Logger.error(this, "The callback is null! it has been already unsubscribed, hasn't it?", new Exception("debug"));
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
				subscribersByClearUSK.put(clear, callbacks);
			else{
				subscribersByClearUSK.remove(clear);
				fetchersByUSK.remove(origUSK);
			}
			if(runBackgroundFetch) {
				USKFetcher f = backgroundFetchersByClearUSK.get(clear);
				if(f == null) {
					if(newCallbacks.length == 0)
						Logger.minor(this, "Unsubscribing "+cb+" for "+origUSK+" but not already subscribed. No callbacks.", new Exception("debug"));
					else
						Logger.error(this, "Unsubscribing "+cb+" for "+origUSK+" but not already subscribed, remaining "+newCallbacks.length+" callbacks", new Exception("error"));
				} else {
					f.removeSubscriber(cb, context);
					if(!f.hasSubscribers()) {
						if(!temporaryBackgroundFetchersLRU.contains(clear)) {
							toCancel = f;
							backgroundFetchersByClearUSK.remove(clear);
						}
					}
				}
			}
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
	 * @return
	 */
	public USKRetriever subscribeContent(USK origUSK, USKRetrieverCallback cb, boolean runBackgroundFetch, FetchContext fctx, short prio, RequestClient client) {
		USKRetriever ret = new USKRetriever(fctx, prio, client, cb, origUSK);
		subscribe(origUSK, ret, runBackgroundFetch, client);
		return ret;
	}
	
	public void unsubscribeContent(USK origUSK, USKRetriever ret, boolean runBackgroundFetch) {
		unsubscribe(origUSK, ret, runBackgroundFetch);
	}
	
	// REMOVE: DO NOT Synchronize! ... debugging only.
	/**
	 * The result of that method will be displayed on the Statistic Toadlet : it will help catching #1147 
	 * Afterwards it should be removed: it's not usefull :)
	 * @return the number of Fetchers started by USKManager
	 */
	public int getFetcherByUSKSize(){
		return fetchersByUSK.size();
	}
	
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

	public void onCancelled(USKFetcher fetcher) {
		USK clear = fetcher.getOriginalUSK().clearCopy();
		synchronized(this) {
			if(backgroundFetchersByClearUSK.get(clear) == fetcher) {
				backgroundFetchersByClearUSK.remove(clear);
				// This shouldn't happen, it's a sanity check: the only way we get cancelled is from USKManager, which removes us before calling cancel().
				Logger.error(this, "onCancelled for "+fetcher+" - was still registered, how did this happen??", new Exception("debug"));
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
