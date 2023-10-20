package freenet.clients.http;

import java.util.List;
import java.util.stream.Collectors;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.async.ClientContext;
import freenet.clients.http.FProxyFetchInProgress.REFILTER_POLICY;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.Logger.LogLevel;

public class FProxyFetchTracker implements Runnable {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	private final MultiValueTable<FreenetURI, FProxyFetchInProgress> fetchers = new MultiValueTable<>();
	final ClientContext context;
	private long fetchIdentifiers;
	private final FetchContext fctx;
	private final RequestClient rc;
	private boolean queuedJob;
	private boolean requeue;

	public FProxyFetchTracker(ClientContext context, FetchContext fctx, RequestClient rc) {
		this.context = context;
		this.fctx = fctx;
		this.rc = rc;
	}
	
	public FProxyFetchWaiter makeFetcher(FreenetURI key, long maxSize, FetchContext fctx, REFILTER_POLICY refilterPolicy) throws FetchException {
		FProxyFetchInProgress progress;
		/* LOCKING:
		 * Call getWaiter() inside the fetchers lock, since we will purge old 
		 * fetchers inside that lock, hence avoid a race condition. FetchInProgress 
		 * lock is always taken last. */
		synchronized(fetchers) {
			FProxyFetchWaiter waiter=makeWaiterForFetchInProgress(key, maxSize, fctx != null ? fctx : this.fctx);
			if(waiter!=null){
				return waiter;
			}
			progress = new FProxyFetchInProgress(this, key, maxSize, fetchIdentifiers++, context, fctx != null ? fctx : this.fctx, rc, refilterPolicy);
			fetchers.put(key, progress);
		}
		try {
			progress.start(context);
		} catch (FetchException e) {
			synchronized(fetchers) {
				fetchers.removeElement(key, progress);
			}
			throw e;
		}
		if(logMINOR) Logger.minor(this, "Created new fetcher: "+progress, new Exception());
		return progress.getWaiter();
		// FIXME promote a fetcher when it is re-used
		// FIXME get rid of fetchers over some age
	}
	
	void removeFetcher(FProxyFetchInProgress progress) {
		synchronized(fetchers) {
			fetchers.removeElement(progress.uri, progress);
		}
	}
	
	public FProxyFetchWaiter makeWaiterForFetchInProgress(FreenetURI key,long maxSize, FetchContext fctx){
		FProxyFetchInProgress progress=getFetchInProgress(key, maxSize, fctx);
		if(progress!=null){
			return progress.getWaiter();
		}
		return null;
	}

	/**
	 * Gets an {@link FProxyFetchInProgress} identified by the URI and having provided max size.
	 * If optional fetch context parameter is specified,
	 * then fetch context in {@link FProxyFetchInProgress} is compared to provided fetch context.
	 * If no such FetchInProgress exists, then returns {@code null}.
	 *
	 * @param key     - The URI of the fetch
	 * @param maxSize - The maxSize of the fetch
	 * @param fctx    - Optional {@link FetchContext} with fetch parameters
	 * @return The FetchInProgress if found, {@code null} otherwise
	 */
	public FProxyFetchInProgress getFetchInProgress(FreenetURI key, long maxSize, FetchContext fctx) {
		synchronized (fetchers) {
			List<FProxyFetchInProgress> fetchList = fetchers.getAllAsList(key);
			if (fetchList == null) {
				return null;
			}
			for (FProxyFetchInProgress fetch : fetchList) {
				if ((fetch.maxSize == maxSize && fetch.notFinishedOrFatallyFinished())
					|| fetch.hasData()
				) {
					if (logMINOR) {
						Logger.minor(this, "Found " + fetch);
					}
					if (fctx != null && !fetch.fetchContextEquivalent(fctx)) {
						if (logMINOR) {
							Logger.minor(this, "Fetch context does not match. Skipping " + fetch);
						}
						continue;
					}
					if (logMINOR) {
						Logger.minor(this, "Using " + fetch);
					}
					return fetch;
				}
				if (logMINOR) {
					Logger.minor(this, "Skipping " + fetch);
				}
			}
		}
		return null;
	}

	public void queueCancel(FProxyFetchInProgress progress) {
		if(logMINOR) Logger.minor(this, "Queueing removal of old FProxyFetchInProgress's");
		synchronized(this) {
			if(queuedJob) {
				requeue = true;
				return;
			}
			queuedJob = true;
		}
		context.ticker.queueTimedJob(this, FProxyFetchInProgress.LIFETIME);
	}

	@Override
	public void run() {
		if (logMINOR) {
			Logger.minor(this, "Removing old FProxyFetchInProgress's");
		}
		List<FProxyFetchInProgress> toRemove;
		boolean needRequeue = false;
		synchronized(fetchers) {
			if(requeue) {
				requeue = false;
				needRequeue = true;
			} else {
				queuedJob = false;
			}
			// Horrible hack, FIXME
			toRemove = fetchers.values().stream()
				// FIXME remove on the fly, although cancel must wait
				.filter(FProxyFetchInProgress::canCancel)
				.collect(Collectors.toList());

			for(FProxyFetchInProgress r : toRemove) {
				if(logMINOR){
					Logger.minor(this,"Removed fetchinprogress:"+r);
				}
				fetchers.removeElement(r.uri, r);
			}
		}
		for(FProxyFetchInProgress r : toRemove) {
			if (logMINOR) {
				Logger.minor(this, "Cancelling for "+r);
			}
			r.finishCancel();
		}
		if(needRequeue) {
			context.ticker.queueTimedJob(this, FProxyFetchInProgress.LIFETIME);
		}
	}

	public int makeRandomElementID() {
		return context.fastWeakRandom.nextInt();
	}

}
