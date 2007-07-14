/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.Ticker;
import freenet.support.LRUQueue;
import freenet.support.Logger;

/**
 * Tracks the latest version of every known USK.
 * Also does auto-updates.
 */
public class USKManager {

	/** Latest version by blanked-edition-number USK */
	final HashMap latestVersionByClearUSK;
	
	/** Subscribers by clear USK */
	final HashMap subscribersByClearUSK;
	
	/** USKFetcher's by USK. USK includes suggested edition number, so there is one
	 * USKFetcher for each {USK, edition number}. */
	final HashMap fetchersByUSK;
	
	/** Backgrounded USKFetchers by USK. */
	final HashMap backgroundFetchersByClearUSK;
	
	final LRUQueue temporaryBackgroundFetchersLRU;
	
	/** USKChecker's by USK. Deleted immediately on completion. */
	final HashMap checkersByUSK;

	final FetchContext backgroundFetchContext;
	final ClientRequestScheduler chkRequestScheduler;
	final ClientRequestScheduler sskRequestScheduler;
	
	final Ticker ticker;

	
	public USKManager(NodeClientCore core) {
		backgroundFetchContext = core.makeClient(RequestStarter.UPDATE_PRIORITY_CLASS).getFetchContext();
		backgroundFetchContext.followRedirects = false;
		backgroundFetchContext.uskManager = this;
		this.chkRequestScheduler = core.requestStarters.chkFetchScheduler;
		this.sskRequestScheduler = core.requestStarters.sskFetchScheduler;
		latestVersionByClearUSK = new HashMap();
		subscribersByClearUSK = new HashMap();
		fetchersByUSK = new HashMap();
		checkersByUSK = new HashMap();
		backgroundFetchersByClearUSK = new HashMap();
		temporaryBackgroundFetchersLRU = new LRUQueue();
		ticker = core.getTicker();
	}

	/**
	 * Look up the latest known version of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookup(USK usk) {
		Long l = (Long) latestVersionByClearUSK.get(usk.clearCopy());
		if(l != null)
			return l.longValue();
		else return -1;
	}

	public synchronized USKFetcher getFetcher(USK usk, FetchContext ctx,
			ClientRequester requester, boolean keepLastData) {
		USKFetcher f = (USKFetcher) fetchersByUSK.get(usk);
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

	public USKFetcher getFetcherForInsertDontSchedule(USK usk, short prioClass, USKFetcherCallback cb, Object client) {
		USKFetcher f = new USKFetcher(usk, this, backgroundFetchContext, 
				new USKFetcherWrapper(usk, prioClass, chkRequestScheduler, sskRequestScheduler, client), 3, false, true);
		f.addCallback(cb);
		return f;
	}

	public void startTemporaryBackgroundFetcher(USK usk) {
		USK clear = usk.clearCopy();
		USKFetcher sched = null;
		Vector toCancel = null;
		synchronized(this) {
			USKFetcher f = (USKFetcher) backgroundFetchersByClearUSK.get(clear);
			if(f == null) {
				f = new USKFetcher(usk, this, backgroundFetchContext, new USKFetcherWrapper(usk, RequestStarter.UPDATE_PRIORITY_CLASS, chkRequestScheduler, sskRequestScheduler, this), 3, true, false);
				sched = f;
				backgroundFetchersByClearUSK.put(clear, f);
			}
			temporaryBackgroundFetchersLRU.push(clear);
			while(temporaryBackgroundFetchersLRU.size() > NodeClientCore.maxBackgroundUSKFetchers) {
				USK del = (USK) temporaryBackgroundFetchersLRU.pop();
				USKFetcher fetcher = (USKFetcher) backgroundFetchersByClearUSK.get(del.clearCopy());
				if(!fetcher.hasSubscribers()) {
					if(toCancel == null) toCancel = new Vector(2);
					toCancel.add(fetcher);
					backgroundFetchersByClearUSK.remove(fetcher);
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
				USKFetcher fetcher = (USKFetcher) toCancel.get(i);
				fetcher.cancel();
			}
		}
		if(sched != null) sched.schedule();
	}
	
	void update(final USK origUSK, final long number) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Updating "+origUSK.getURI()+" : "+number);
		USK clear = origUSK.clearCopy();
		final USKCallback[] callbacks;
		synchronized(this) {
			Long l = (Long) latestVersionByClearUSK.get(clear);
			if(logMINOR) Logger.minor(this, "Old value: "+l);
			if((l == null) || (number > l.longValue())) {
				l = new Long(number);
				latestVersionByClearUSK.put(clear, l);
				if(logMINOR) Logger.minor(this, "Put "+number);
			} else return;
			callbacks = (USKCallback[]) subscribersByClearUSK.get(clear);
		}
		if(callbacks != null) {
			// Run off-thread, because of locking, and because client callbacks may take some time
			ticker.queueTimedJob(new Runnable() {
				public void run() {
					USK usk = origUSK.copy(number);
					for(int i=0;i<callbacks.length;i++)
						callbacks[i].onFoundEdition(number, usk);
				}
			}, 0);
		}
	}
	
	/**
	 * Subscribe to a given USK. Callback will be notified when it is
	 * updated. Note that this does not imply that the USK will be
	 * checked on a regular basis, unless runBackgroundFetch=true.
	 */
	public void subscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch, Object client) {
		USKFetcher sched = null;
		long ed = origUSK.suggestedEdition;
		long curEd;
		curEd = lookup(origUSK);
		synchronized(this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = (USKCallback[]) subscribersByClearUSK.get(clear);
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
				USKFetcher f = (USKFetcher) backgroundFetchersByClearUSK.get(clear);
				if(f == null) {
					f = new USKFetcher(origUSK, this, backgroundFetchContext, new USKFetcherWrapper(origUSK, RequestStarter.UPDATE_PRIORITY_CLASS, chkRequestScheduler, sskRequestScheduler, client), 10, true, false);
					sched = f;
					backgroundFetchersByClearUSK.put(clear, f);
				}
				f.addSubscriber(cb);
			}
		}
		if(curEd > ed)
			cb.onFoundEdition(curEd, origUSK.copy(curEd));
		final USKFetcher fetcher = sched;
		if(fetcher != null) {
			ticker.queueTimedJob(new Runnable() {
				public void run() {
					fetcher.schedule();
				}
			}, 0);
		}
	}
	
	public void unsubscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch) {
		USKFetcher toCancel = null;
		synchronized(this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = (USKCallback[]) subscribersByClearUSK.get(clear);
			if(callbacks == null){ // maybe we should throw something ? shall we allow multiple unsubscriptions ?
				if(Logger.shouldLog(Logger.MINOR, this)){
					Logger.error(this, "The callback is null! it has been already unsubscribed, hasn't it?");
					new NullPointerException("The callback is null! it has been already unsubscribed, hasn't it?").printStackTrace();
				}
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
				USKFetcher f = (USKFetcher) backgroundFetchersByClearUSK.get(clear);
				f.removeSubscriber(cb);
				if(!f.hasSubscribers()) {
					if(!temporaryBackgroundFetchersLRU.contains(clear)) {
						toCancel = f;
						backgroundFetchersByClearUSK.remove(clear);
					}
				}
			}
		}
		if(toCancel != null) toCancel.cancel();
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
	public USKRetriever subscribeContent(USK origUSK, USKRetrieverCallback cb, boolean runBackgroundFetch, FetchContext fctx, short prio, Object client) {
		USKRetriever ret = new USKRetriever(fctx, prio, chkRequestScheduler, sskRequestScheduler, client, cb);
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
}
