/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.KeyDecodeException;
import freenet.keys.USK;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * 
 * On 0.7, this shouldn't however take more than 10 seconds or so; these are SSKs
 * we are talking about. If this is fast enough then people will use the "-" form.
 * 
 * FProxy should cause USKs with negative edition numbers to be redirected to USKs
 * with positive edition numbers.
 * 
 * If the number specified is up to date, we just do the fetch. If a more recent
 * USK can be found, then we fail with an exception with the new version. The
 * client is expected to redirect to this. The point here is that FProxy will then
 * have the correct number in the location bar, so that if the user copies the URL,
 * it will keep the edition number hint.
 * 
 * A positive number fetch triggers a background fetch.
 * 
 * This class does both background fetches and negative number fetches.
 * 
 * It does them in the same way.
 * 
 * There is one USKFetcher for a given USK, at most. They are registered on the
 * USKManager. They have a list of ClientGetState-implementing callbacks; these
 * are all triggered on completion. 
 * 
 * When a new suggestedEdition is added, if that is later than the
 * currently searched-for version, the USKFetcher will try to fetch that as well
 * as its current pointer.
 * 
 * Current algorithm:
 * - Fetch the next 5 editions all at once.
 * - If we have four consecutive editions with DNF, and no later pending fetches,
 *   then we finish with the last known good version. (There are details relating
 *   to other error codes handled below in the relevant method).
 * - We immediately update the USKManager if we successfully fetch an edition.
 * - If a new, higher suggestion comes in, that is also fetched.
 * 
 * Future extensions:
 * - Binary search.
 * - Hierarchical DBRs.
 * - TUKs (when we have TUKs).
 * - Passive requests (when we have passive requests).
 */
public class USKFetcher implements ClientGetState {

	private static boolean logMINOR;
	
	/** USK manager */
	private final USKManager uskManager;
	
	/** The USK to fetch */
	private final USK origUSK;
	
	/** Callbacks */
	private final LinkedList<USKFetcherCallback> callbacks;

	/** Fetcher context */
	final FetchContext ctx;
	
	/** Finished? */
	private boolean completed;
	
	/** Cancelled? */
	private boolean cancelled;

	/** Kill a background poll fetcher when it has lost its last subscriber? */
	private boolean killOnLoseSubscribers;
	
	final ClientRequester parent;

	// We keep the data from the last (highest number) request.
	private Bucket lastRequestData;
	private short lastCompressionCodec;
	private boolean lastWasMetadata;
	
	public synchronized boolean addCallback(USKFetcherCallback cb) {
		if(completed) return false; 
		callbacks.add(cb);
		return true;
	}
	
	class USKAttempt implements USKCheckerCallback {
		/** Edition number */
		long number;
		/** Attempt to fetch that edition number (or null if the fetch has finished) */
		USKChecker checker;
		/** Successful fetch? */
		boolean succeeded;
		/** DNF? */
		boolean dnf;
		boolean cancelled;
		public USKAttempt(long i) {
			this.number = i;
			this.succeeded = false;
			this.dnf = false;
			this.checker = new USKChecker(this, origUSK.getSSK(i), ctx.maxNonSplitfileRetries, ctx, parent);
		}
		public void onDNF() {
			checker = null;
			dnf = true;
			USKFetcher.this.onDNF(this);
		}
		public void onSuccess(ClientSSKBlock block) {
			checker = null;
			succeeded = true;
			USKFetcher.this.onSuccess(this, false, block);
		}
		
		public void onFatalAuthorError() {
			checker = null;
			// Counts as success except it doesn't update
			USKFetcher.this.onSuccess(this, true, null);
		}
		
		public void onNetworkError() {
			checker = null;
			// Not a DNF
			USKFetcher.this.onFail(this);
		}
		
		public void onCancelled() {
			checker = null;
			USKFetcher.this.onCancelled(this);
		}
		
		public void cancel() {
			cancelled = true;
			if(checker != null)
				checker.cancel();
			onCancelled();
		}
		
		public void schedule() {
			if(checker == null) {
				if(logMINOR)
					Logger.minor(this, "Checker == null in schedule() for "+this, new Exception("debug"));
			} else
				checker.schedule();
		}
		
		@Override
		public String toString() {
			return "USKAttempt for "+number+" for "+origUSK.getURI()+" for "+USKFetcher.this;
		}
		
		public short getPriority() {
			if(backgroundPoll) {
				if(minFailures == origMinFailures && minFailures != maxMinFailures) {
					// Either just started, or just advanced, either way boost the priority.
					return progressPollPriority;
				} else {
					return normalPollPriority;
				}
			} else
				return parent.getPriorityClass();
		}
	}
	
	private final Vector<USKAttempt> runningAttempts;
	
	private long lastFetchedEdition;
	private long lastAddedEdition;

	/** Number of keys to probe ahead. If they are all empty, then
	 * we have finished (unless in background poll mode) */
	long minFailures;
	final long origMinFailures;
	
	static final int origSleepTime = 30 * 60 * 1000;
	static final int maxSleepTime = 24 * 60 * 60 * 1000;
	int sleepTime = origSleepTime;

	/** Maximum number of editions to probe ahead. */
	private final long maxMinFailures;
	private final static long DEFAULT_MAX_MIN_FAILURES = 100;

	private long valueAtSchedule;
	
	/** Keep going forever? */
	private final boolean backgroundPoll;
	
	/** Keep the last fetched data? */
	final boolean keepLastData;
	
	private boolean started;
	
	private static short DEFAULT_NORMAL_POLL_PRIORITY = RequestStarter.PREFETCH_PRIORITY_CLASS;
	private short normalPollPriority = DEFAULT_NORMAL_POLL_PRIORITY;
	private static short DEFAULT_PROGRESS_POLL_PRIORITY = RequestStarter.UPDATE_PRIORITY_CLASS;
	private short progressPollPriority = DEFAULT_PROGRESS_POLL_PRIORITY;

	USKFetcher(USK origUSK, USKManager manager, FetchContext ctx, ClientRequester requester, int minFailures, boolean pollForever, boolean keepLastData) {
		this(origUSK, manager, ctx, requester, minFailures, pollForever, DEFAULT_MAX_MIN_FAILURES, keepLastData);
	}
	
	// FIXME use this!
	USKFetcher(USK origUSK, USKManager manager, FetchContext ctx, ClientRequester requester, int minFailures, boolean pollForever, long maxProbeEditions, boolean keepLastData) {
		this.parent = requester;
		this.maxMinFailures = maxProbeEditions;
		this.origUSK = origUSK;
		this.uskManager = manager;
		this.minFailures = this.origMinFailures = minFailures;
		runningAttempts = new Vector<USKAttempt>();
		callbacks = new LinkedList<USKFetcherCallback>();
		subscribers = new HashSet<USKCallback>();
		lastFetchedEdition = -1;
		lastAddedEdition = -1;
		this.ctx = ctx;
		this.backgroundPoll = pollForever;
		this.keepLastData = keepLastData;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	void onDNF(USKAttempt att) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "DNF: "+att);
		boolean finished = false;
		long curLatest = uskManager.lookup(origUSK);
		synchronized(this) {
			if(completed || cancelled) return;
			lastFetchedEdition = Math.max(lastFetchedEdition, att.number);
			runningAttempts.remove(att);
			if(runningAttempts.isEmpty()) {
				if(logMINOR) Logger.minor(this, "latest: "+curLatest+", last fetched: "+lastFetchedEdition+", curLatest+MIN_FAILURES: "+(curLatest+minFailures));
				if(started) {
					finished = true;
				}
			} else if(logMINOR) Logger.minor(this, "Remaining: "+runningAttempts.size());
		}
		if(finished) {
			finishSuccess();
		}
	}
	
	private void finishSuccess() {
		if(backgroundPoll) {
			long valAtEnd = uskManager.lookup(origUSK);
			long end;
			long now = System.currentTimeMillis();
			synchronized(this) {
				started = false; // don't finish before have rescheduled
                
                //Find out when we should check next ('end'), in an increasing delay (unless we make progress).
                int newSleepTime = sleepTime * 2;
				if(newSleepTime > maxSleepTime) newSleepTime = maxSleepTime;
				sleepTime = newSleepTime;
				end = now + ctx.random.nextInt(sleepTime);
                
				if(valAtEnd > valueAtSchedule) {
					// We have advanced; keep trying as if we just started.
					minFailures = origMinFailures;
					sleepTime = origSleepTime;
					end = now;
				} else {
					// We have not found any new version; Increase exponentially but relatively slowly
					long newMinFailures = Math.max(((int)(minFailures * 1.25)), minFailures+1);
					if(newMinFailures > maxMinFailures)
						newMinFailures = maxMinFailures;
					minFailures = newMinFailures;
				}
			}
			schedule(end-now);
		} else {
			long ed = uskManager.lookup(origUSK);
			USKFetcherCallback[] cb;
			synchronized(this) {
				completed = true;
				cb = callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
			}
			for(int i=0;i<cb.length;i++) {
				try {
					cb[i].onFoundEdition(ed, origUSK.copy(ed));
				} catch (Exception e) {
					Logger.error(this, "An exception occured while dealing with a callback:"+cb[i].toString()+"\n"+e.getMessage(),e);
				}
			}
		}
	}

	void onSuccess(USKAttempt att, boolean dontUpdate, ClientSSKBlock block) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		LinkedList<USKAttempt> l = null;
		final long lastEd = uskManager.lookup(origUSK);
		long curLatest;
		boolean decode = false;
		synchronized(this) {
			runningAttempts.remove(att);
			curLatest = att.number;
			if(completed || cancelled) return;
			decode = curLatest >= lastEd && !(dontUpdate && block == null);
			curLatest = Math.max(lastEd, curLatest);
			if(logMINOR) Logger.minor(this, "Latest: "+curLatest);
			long addTo = curLatest + minFailures;
			long addFrom = Math.max(lastAddedEdition + 1, curLatest + 1);
			if(logMINOR) Logger.minor(this, "Adding from "+addFrom+" to "+addTo+" for "+origUSK);
			if(addTo >= addFrom) {
				l = new LinkedList<USKAttempt>();
				for(long i=addFrom;i<=addTo;i++) {
					if(logMINOR) Logger.minor(this, "Adding checker for edition "+i+" for "+origUSK);
					l.add(add(i));
				}
			}
			cancelBefore(curLatest);
		}
		Bucket data = null;
		if(decode) {
			try {
				data = block.decode(ctx.bucketFactory, 1025 /* it's an SSK */, true);
			} catch (KeyDecodeException e) {
				data = null;
			} catch (IOException e) {
				data = null;
				Logger.error(this, "An IOE occured while decoding: "+e.getMessage(),e);
			}
		}
		synchronized(this) {
			if (decode) {
					lastCompressionCodec = block.getCompressionCodec();
					lastWasMetadata = block.isMetadata();
					if(keepLastData)
						lastRequestData = data;
					else
						data.free();
			}
		}
		if(!dontUpdate)
			uskManager.update(origUSK, curLatest);
		if(l == null) return;
		final LinkedList<USKAttempt> toSched = l;
		// If we schedule them here, we don't get icky recursion problems.
		if(!cancelled) {
			ctx.executor.execute(new Runnable() {
				public void run() {
					long last = lastEd;
					for(Iterator<USKAttempt> i=toSched.iterator();i.hasNext();) {
						// We may be called recursively through onSuccess().
						// So don't start obsolete requests.
						USKAttempt a = i.next();
						last = uskManager.lookup(origUSK);
						if((last <= a.number) && !a.cancelled)
							a.schedule();
						else {
							synchronized(this) {
								runningAttempts.remove(a);
							}
						}
					}
				}
			}, "USK scheduler"); // Run on separate thread because otherwise can get loooong recursions
		}
	}

	public void onCancelled(USKAttempt att) {
		synchronized(this) {
			runningAttempts.remove(att);
			if(!runningAttempts.isEmpty()) return;
		
			if(cancelled)
				finishCancelled();
		}
	}

	private void finishCancelled() {
		USKFetcherCallback[] cb;
		synchronized(this) {
			completed = true;
			cb = callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
		}
		for(int i=0;i<cb.length;i++)
			cb[i].onCancelled();
	}

	public void onFail(USKAttempt attempt) {
		// FIXME what else can we do?
		// Certainly we don't want to continue fetching indefinitely...
		// ... e.g. RNFs don't indicate we should try a later slot, none of them
		// really do.
		onDNF(attempt);
	}

	private void cancelBefore(long curLatest) {
		Vector<USKAttempt> v = null;
		int count = 0;
		synchronized(this) {
			for(Iterator<USKAttempt> i=runningAttempts.iterator();i.hasNext();) {
				USKAttempt att = i.next();
				if(att.number < curLatest) {
					if(v == null) v = new Vector<USKAttempt>(runningAttempts.size()-count);
					v.add(att);
					i.remove();
				}
				count++;
			}
		}
		if(v != null) {
			for(int i=0;i<v.size();i++) {
				USKAttempt att = v.get(i);
				att.cancel();
			}
		}
	}

	/**
	 * Add a USKAttempt for another edition number.
	 * Caller is responsible for calling .schedule().
	 */
	private synchronized USKAttempt add(long i) {
		if(cancelled) return null;
		if(logMINOR) Logger.minor(this, "Adding USKAttempt for "+i+" for "+origUSK.getURI());
		if(!runningAttempts.isEmpty()) {
			USKAttempt last = runningAttempts.lastElement();
			if(last.number >= i) {
				if(logMINOR) Logger.minor(this, "Returning because last.number="+i+" for "+origUSK.getURI());
				return null;
			}
		}
		USKAttempt a = new USKAttempt(i);
		runningAttempts.add(a);
		lastAddedEdition = i;
		if(logMINOR) Logger.minor(this, "Added "+a+" for "+origUSK);
		return a;
	}

	public FreenetURI getURI() {
		return origUSK.getURI();
	}

	public boolean isFinished() {
		synchronized (this) {
			return completed || cancelled;			
		}
	}

	public USK getOriginalUSK() {
		return origUSK;
	}
	
	public void schedule(long delay) {
		if (delay<=0) {
			schedule();
		} else {
			ctx.ticker.queueTimedJob(new Runnable() {
				public void run() {
					USKFetcher.this.schedule();
				}
			}, delay);
		}
	}
    
	public void schedule() {
		USKAttempt[] attempts;
		long lookedUp = uskManager.lookup(origUSK);
		synchronized(this) {
			valueAtSchedule = Math.max(lookedUp, valueAtSchedule);
			if(cancelled) return;
			long startPoint = Math.max(origUSK.suggestedEdition, valueAtSchedule);
			for(long i=startPoint;i<startPoint+minFailures;i++)
				add(i);
			attempts = runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
			started = true;
		}
		if(!cancelled) {
			for(int i=0;i<attempts.length;i++) {
				// Race conditions happen here and waste a lot more time than this simple check.
				long lastEd = uskManager.lookup(origUSK);
				if(keepLastData && lastEd == lookedUp)
					lastEd--; // If we want the data, then get it for the known edition, so we always get the data, so USKInserter can compare it and return the old edition if it is identical.
				if(attempts[i].number > lastEd)
					attempts[i].schedule();
				else {
					synchronized(this) {
						runningAttempts.remove(attempts[i]);
					}
				}
			}
		}
	}

	public void cancel() {
		USKAttempt[] attempts;
		synchronized(this) {
			cancelled = true;
			attempts = runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
		}
		for(int i=0;i<attempts.length;i++)
			attempts[i].cancel();
		uskManager.onCancelled(this);
	}

	/** Set of interested USKCallbacks. Note that we don't actually
	 * send them any information - they are essentially placeholders,
	 * an alternative to a refcount. This could be replaced with a 
	 * Bloom filter or whatever, we only need .exists and .count.
	 */
	final HashSet<USKCallback> subscribers;
	
	public void addSubscriber(USKCallback cb) {
		synchronized(this) {
			subscribers.add(cb);
		}
		updatePriorities();
	}

	private void updatePriorities() {
		// FIXME should this be synchronized? IMHO it doesn't matter that much if we get the priority
		// wrong for a few requests... also, we avoid any possible deadlock this way if the callbacks
		// take locks...
		short normalPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
		short progressPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
		USKCallback[] localCallbacks;
		synchronized(this) {
			localCallbacks = subscribers.toArray(new USKCallback[subscribers.size()]);
		}
		if(localCallbacks.length == 0) {
			normalPollPriority = DEFAULT_NORMAL_POLL_PRIORITY;
			progressPollPriority = DEFAULT_PROGRESS_POLL_PRIORITY;
			return;
		}
		
		for(int i=0;i<localCallbacks.length;i++) {
			USKCallback cb = localCallbacks[i];
			short prio = cb.getPollingPriorityNormal();
			if(prio < normalPrio) normalPrio = prio;
			prio = cb.getPollingPriorityProgress();
			if(prio < progressPrio) progressPrio = prio;
		}
		normalPollPriority = normalPrio;
		progressPollPriority = progressPrio;
	}

	public synchronized boolean hasSubscribers() {
		return !subscribers.isEmpty();
	}
	
	public void removeSubscriber(USKCallback cb) {
		synchronized(this) {
			subscribers.remove(cb);
		}
		updatePriorities();
	}

	public synchronized boolean hasLastData() {
		return this.lastRequestData != null;
	}

	public synchronized boolean lastContentWasMetadata() {
		return this.lastWasMetadata;
	}

	public synchronized short lastCompressionCodec() {
		return this.lastCompressionCodec;
	}

	public synchronized Bucket getLastData() {
		return this.lastRequestData;
	}

	public synchronized void freeLastData() {
		if(lastRequestData == null) return;
		lastRequestData.free();
		lastRequestData = null;
	}

	public synchronized void killOnLoseSubscribers() {
		this.killOnLoseSubscribers = true;
	}

	public long getToken() {
		return -1;
	}
	
}
