/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.keys.USK;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.RemoveRangeArrayList;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

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
public class USKFetcher implements ClientGetState, USKCallback, HasKeyListener, KeyListener {
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(Logger.MINOR, this);
                logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
            }
        });
    }
	
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
	
	/** Structure tracking which keys we want. */
	private final USKWatchingKeys watchingKeys;
	
	/** Edition number of the first key in keysWatching */
	private long firstKeyWatching = -1;
	/** A list of keys which we are interested in. This a sequence of SSKs starting 
	 * at the last known slot. */
	private final ArrayList<ClientSSK> keysWatching;
	private final ArrayList<USKAttempt> attemptsToStart;
	
	private static final int WATCH_KEYS = 50;
	
	/**
	 * Callbacks are told when the USKFetcher finishes, and unless background poll is
	 * enabled, they are only sent onFoundEdition *once*, on completion.
	 * 
	 * However they do help to determine the fetcher's priority.
	 * 
	 * FIXME: Don't allow callbacks if backgroundPoll is enabled??
	 * @param cb
	 * @return
	 */
	public boolean addCallback(USKFetcherCallback cb) {
		synchronized(this) {
			if(completed) return false; 
			callbacks.add(cb);
		}
		updatePriorities();
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
			this.checker = new USKChecker(this, origUSK.getSSK(i), ctx.maxUSKRetries, ctx, parent);
		}
		public void onDNF(ClientContext context) {
			checker = null;
			dnf = true;
			USKFetcher.this.onDNF(this, context);
		}
		public void onSuccess(ClientSSKBlock block, ClientContext context) {
			checker = null;
			succeeded = true;
			USKFetcher.this.onSuccess(this, false, block, context);
		}
		
		public void onFatalAuthorError(ClientContext context) {
			checker = null;
			// Counts as success except it doesn't update
			USKFetcher.this.onSuccess(this, true, null, context);
		}
		
		public void onNetworkError(ClientContext context) {
			checker = null;
			// Not a DNF
			USKFetcher.this.onFail(this, context);
		}
		
		public void onCancelled(ClientContext context) {
			checker = null;
			USKFetcher.this.onCancelled(this, context);
		}
		
		public void cancel(ObjectContainer container, ClientContext context) {
			assert(container == null);
			cancelled = true;
			if(checker != null)
				checker.cancel(container, context);
			onCancelled(context);
		}
		
		public void schedule(ObjectContainer container, ClientContext context) {
			assert(container == null);
			if(checker == null) {
				if(logMINOR)
					Logger.minor(this, "Checker == null in schedule() for "+this, new Exception("debug"));
			} else {
				assert(!checker.persistent());
				checker.schedule(container, context);
			}
		}
		
		@Override
		public String toString() {
			return "USKAttempt for "+number+" for "+origUSK.getURI()+" for "+USKFetcher.this;
		}
		
		public short getPriority() {
			if(backgroundPoll) {
				if((minFailures == origMinFailures && !firstLoop) && minFailures != maxMinFailures) {
					// Just advanced, boost the priority.
					// Do NOT boost the priority if just started.
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
	boolean firstLoop;
	
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
		firstLoop = true;
		runningAttempts = new Vector<USKAttempt>();
		callbacks = new LinkedList<USKFetcherCallback>();
		subscribers = new HashSet<USKCallback>();
		lastFetchedEdition = -1;
		lastAddedEdition = -1;
		this.ctx = ctx;
		this.backgroundPoll = pollForever;
		this.keepLastData = keepLastData;
		keysWatching = new ArrayList<ClientSSK>();
		watchingKeys = new USKWatchingKeys(origUSK, Math.max(0, uskManager.lookupLatestSlot(origUSK)+1));
		attemptsToStart = new ArrayList<USKAttempt>();
	}
	
	void onDNF(USKAttempt att, ClientContext context) {
		if(logMINOR) Logger.minor(this, "DNF: "+att);
		boolean finished = false;
		long curLatest = uskManager.lookupLatestSlot(origUSK);
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
			finishSuccess(context);
		}
	}
	
	private void finishSuccess(ClientContext context) {
		if(backgroundPoll) {
			long valAtEnd = uskManager.lookupLatestSlot(origUSK);
			long end;
			long now = System.currentTimeMillis();
			synchronized(this) {
				started = false; // don't finish before have rescheduled
                
                //Find out when we should check next ('end'), in an increasing delay (unless we make progress).
                int newSleepTime = sleepTime * 2;
				if(newSleepTime > maxSleepTime) newSleepTime = maxSleepTime;
				sleepTime = newSleepTime;
				end = now + context.random.nextInt(sleepTime);
                
				if(valAtEnd > valueAtSchedule && valAtEnd > origUSK.suggestedEdition) {
					// We have advanced; keep trying as if we just started.
					// Only if we actually DO advance, not if we just confirm our suspicion (valueAtSchedule always starts at 0).
					minFailures = origMinFailures;
					sleepTime = origSleepTime;
					firstLoop = false;
					end = now;
					if(logMINOR)
						Logger.minor(this, "We have advanced: at start, "+valueAtSchedule+" at end, "+valAtEnd);
				} else {
					// We have not found any new version; Increase exponentially but relatively slowly
					long newMinFailures = Math.max(((int)(minFailures * 1.25)), minFailures+1);
					if(newMinFailures > maxMinFailures)
						newMinFailures = maxMinFailures;
					minFailures = newMinFailures;
				}
				if(logMINOR) Logger.minor(this, "Sleep time is "+sleepTime+" this sleep is "+(end-now)+" min failures is "+minFailures+" for "+this);
			}
			schedule(end-now, null, context);
		} else {
			uskManager.unsubscribe(origUSK, this);
			uskManager.onFinished(this);
			context.getSskFetchScheduler().schedTransient.removePendingKeys((KeyListener)this);
			long ed = uskManager.lookupLatestSlot(origUSK);
			USKFetcherCallback[] cb;
			synchronized(this) {
				completed = true;
				cb = callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
			}
			byte[] data;
			if(lastRequestData == null)
				data = null;
			else {
				try {
					data = BucketTools.toByteArray(lastRequestData);
				} catch (IOException e) {
					Logger.error(this, "Unable to turn lastRequestData into byte[]: caught I/O exception: "+e, e);
					data = null;
				}
			}
			for(int i=0;i<cb.length;i++) {
				try {
					cb[i].onFoundEdition(ed, origUSK.copy(ed), null, context, lastWasMetadata, lastCompressionCodec, data, false, false);
				} catch (Exception e) {
					Logger.error(this, "An exception occured while dealing with a callback:"+cb[i].toString()+"\n"+e.getMessage(),e);
				}
			}
		}
	}

	void onSuccess(USKAttempt att, boolean dontUpdate, ClientSSKBlock block, final ClientContext context) {
		onSuccess(att, att.number, dontUpdate, block, context);
	}
	
	void onSuccess(USKAttempt att, long curLatest, boolean dontUpdate, ClientSSKBlock block, final ClientContext context) {
		final long lastEd = uskManager.lookupLatestSlot(origUSK);
		if(logMINOR) Logger.minor(this, "Found edition "+curLatest+" for "+origUSK+" official is "+lastEd);
		boolean decode = false;
		Vector<USKAttempt> killAttempts;
		synchronized(this) {
			if(att != null) runningAttempts.remove(att);
			if(completed || cancelled) return;
			decode = curLatest >= lastEd && !(dontUpdate && block == null);
			curLatest = Math.max(lastEd, curLatest);
			if(logMINOR) Logger.minor(this, "Latest: "+curLatest);
			long addTo = curLatest + minFailures;
			long addFrom = Math.max(lastAddedEdition + 1, curLatest + 1);
			if(logMINOR) Logger.minor(this, "Adding from "+addFrom+" to "+addTo+" for "+origUSK);
			if(addTo >= addFrom) {
				for(long i=addFrom;i<=addTo;i++) {
					if(logMINOR) Logger.minor(this, "Adding checker for edition "+i+" for "+origUSK);
					attemptsToStart.add(add(i));
				}
			}
			killAttempts = cancelBefore(curLatest, context);
			fillKeysWatching(curLatest+1, context);
		}
		finishCancelBefore(killAttempts, context);
		Bucket data = null;
		if(decode && block != null) {
			try {
				data = block.decode(context.getBucketFactory(parent.persistent()), 1025 /* it's an SSK */, true);
			} catch (KeyDecodeException e) {
				data = null;
			} catch (IOException e) {
				data = null;
				Logger.error(this, "An IOE occured while decoding: "+e.getMessage(),e);
			}
		}
		synchronized(this) {
			if (decode) {
				if(block != null) {
					lastCompressionCodec = block.getCompressionCodec();
					lastWasMetadata = block.isMetadata();
					if(keepLastData) {
						if(lastRequestData != null)
							lastRequestData.free();
						lastRequestData = data;
					} else
						data.free();
				} else {
					lastCompressionCodec = -1;
					lastWasMetadata = false;
					lastRequestData = null;
				}
			}
		}
		if(!dontUpdate)
			uskManager.updateSlot(origUSK, curLatest, context);
	}

	void onCancelled(USKAttempt att, ClientContext context) {
		synchronized(this) {
			runningAttempts.remove(att);
			if(!runningAttempts.isEmpty()) return;
		
			if(cancelled)
				finishCancelled(context);
		}
	}

	private void finishCancelled(ClientContext context) {
		USKFetcherCallback[] cb;
		synchronized(this) {
			completed = true;
			cb = callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
		}
		for(int i=0;i<cb.length;i++)
			cb[i].onCancelled(null, context);
	}

	public void onFail(USKAttempt attempt, ClientContext context) {
		// FIXME what else can we do?
		// Certainly we don't want to continue fetching indefinitely...
		// ... e.g. RNFs don't indicate we should try a later slot, none of them
		// really do.
		onDNF(attempt, context);
	}

	private Vector<USKAttempt> cancelBefore(long curLatest, ClientContext context) {
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
		return v;
	}
	
	private void finishCancelBefore(Vector<USKAttempt> v, ClientContext context) {
		if(v != null) {
			for(int i=0;i<v.size();i++) {
				USKAttempt att = v.get(i);
				att.cancel(null, context);
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
	
	public void schedule(long delay, ObjectContainer container, final ClientContext context) {
		assert(container == null);
		if (delay<=0) {
			schedule(container, context);
		} else {
			context.ticker.queueTimedJob(new Runnable() {
				public void run() {
					USKFetcher.this.schedule(null, context);
				}
			}, delay);
		}
	}
    
	public void schedule(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(cancelled) return;
		}
		context.getSskFetchScheduler().schedTransient.addPendingKeys(this);
		updatePriorities();
		uskManager.subscribe(origUSK, this, false, parent.getClient());
		USKAttempt[] attempts;
		long lookedUp = uskManager.lookupLatestSlot(origUSK);
		synchronized(this) {
			valueAtSchedule = Math.max(lookedUp+1, valueAtSchedule);
			if(!cancelled) {
				long startPoint = Math.max(origUSK.suggestedEdition, valueAtSchedule);
				for(long i=startPoint;i<startPoint+minFailures;i++)
					attemptsToStart.add(add(i));
				started = true;
				fillKeysWatching(valueAtSchedule, context);
				return;
			}
		}
		// We have been cancelled.
		uskManager.unsubscribe(origUSK, this);
		context.getSskFetchScheduler().schedTransient.removePendingKeys((KeyListener)this);
		uskManager.onFinished(this, true);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		uskManager.unsubscribe(origUSK, this);
		context.getSskFetchScheduler().schedTransient.removePendingKeys((KeyListener)this);
		assert(container == null);
		USKAttempt[] attempts;
		uskManager.onFinished(this);
		SendableGet storeChecker;
		synchronized(this) {
			cancelled = true;
			attempts = runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
			attemptsToStart.clear();
			storeChecker = runningStoreChecker;
			runningStoreChecker = null;
		}
		for(int i=0;i<attempts.length;i++)
			attempts[i].cancel(container, context);
		if(storeChecker != null)
			// Remove from the store checker queue.
			storeChecker.unregister(container, context, storeChecker.getPriorityClass(container));
	}

	/** Set of interested USKCallbacks. Note that we don't actually
	 * send them any information - they are essentially placeholders,
	 * an alternative to a refcount. This could be replaced with a 
	 * Bloom filter or whatever, we only need .exists and .count.
	 */
	final HashSet<USKCallback> subscribers;
	
	/**
	 * Add a subscriber. Subscribers are not directly sent onFoundEdition()'s by the
	 * USKFetcher, we just use them to determine the priority of our requests and 
	 * whether we should continue to request.
	 * @param cb
	 */
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
		USKFetcherCallback[] fetcherCallbacks;
		synchronized(this) {
			localCallbacks = subscribers.toArray(new USKCallback[subscribers.size()]);
			// Callbacks also determine the fetcher's priority.
			// Otherwise USKFetcherTag would have no way to tell us the priority we should run at.
			fetcherCallbacks = callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
		}
		if(localCallbacks.length == 0 && fetcherCallbacks.length == 0) {
			normalPollPriority = DEFAULT_NORMAL_POLL_PRIORITY;
			progressPollPriority = DEFAULT_PROGRESS_POLL_PRIORITY;
			if(logMINOR) Logger.minor(this, "Updating priorities: normal = "+normalPollPriority+" progress = "+progressPollPriority+" for "+this+" for "+origUSK);
			return;
		}
		
		for(int i=0;i<localCallbacks.length;i++) {
			USKCallback cb = localCallbacks[i];
			short prio = cb.getPollingPriorityNormal();
			if(logDEBUG) Logger.debug(this, "Normal priority for "+cb+" : "+prio);
			if(prio < normalPrio) normalPrio = prio;
			if(logDEBUG) Logger.debug(this, "Progress priority for "+cb+" : "+prio);
			prio = cb.getPollingPriorityProgress();
			if(prio < progressPrio) progressPrio = prio;
		}
		for(int i=0;i<fetcherCallbacks.length;i++) {
			USKFetcherCallback cb = fetcherCallbacks[i];
			short prio = cb.getPollingPriorityNormal();
			if(logDEBUG) Logger.debug(this, "Normal priority for "+cb+" : "+prio);
			if(prio < normalPrio) normalPrio = prio;
			if(logDEBUG) Logger.debug(this, "Progress priority for "+cb+" : "+prio);
			prio = cb.getPollingPriorityProgress();
			if(prio < progressPrio) progressPrio = prio;
		}
		if(logMINOR) Logger.minor(this, "Updating priorities: normal="+normalPrio+" progress="+progressPrio+" for "+this+" for "+origUSK);
		synchronized(this) {
			normalPollPriority = normalPrio;
			progressPollPriority = progressPrio;
		}
	}

	public synchronized boolean hasSubscribers() {
		return !subscribers.isEmpty();
	}
	
	public synchronized boolean hasCallbacks() {
		return !callbacks.isEmpty();
	}
	
	public void removeSubscriber(USKCallback cb, ClientContext context) {
		synchronized(this) {
			subscribers.remove(cb);
		}
		updatePriorities();
	}

	public void removeCallback(USKCallback cb) {
		synchronized(this) {
			subscribers.remove(cb);
		}
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
		lastRequestData.free(); // USKFetcher's cannot be persistent, so no need to removeFrom()
		lastRequestData = null;
	}

	public synchronized void killOnLoseSubscribers() {
		this.killOnLoseSubscribers = true;
	}

	public long getToken() {
		return -1;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing USKFetcher in database", new Exception("error"));
		return false;
	}

	public short getPollingPriorityNormal() {
		throw new UnsupportedOperationException();
	}

	public short getPollingPriorityProgress() {
		throw new UnsupportedOperationException();
	}

	public void onFoundEdition(long ed, USK key, ObjectContainer container, final ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
		if(newKnownGood && !newSlotToo) return; // Only interested in slots
		// Because this is frequently run off-thread, it is actually possible that the looked up edition is not the same as the edition we are being notified of.
		final long lastEd = uskManager.lookupLatestSlot(origUSK);
		boolean decode = false;
		Vector<USKAttempt> killAttempts;
		synchronized(this) {
			if(completed || cancelled) return;
			decode = lastEd == ed && data != null;
			ed = Math.max(lastEd, ed);
			if(logMINOR) Logger.minor(this, "Latest: "+ed);
			long addTo = ed + minFailures;
			long addFrom = Math.max(lastAddedEdition + 1, ed + 1);
			if(logMINOR) Logger.minor(this, "Adding from "+addFrom+" to "+addTo+" for "+origUSK);
			if(addTo >= addFrom) {
				for(long i=addFrom;i<=addTo;i++) {
					if(logMINOR) Logger.minor(this, "Adding checker for edition "+i+" for "+origUSK);
					attemptsToStart.add(add(i));
				}
			}
			killAttempts = cancelBefore(ed, context);
			fillKeysWatching(ed+1, context);
		}
		finishCancelBefore(killAttempts, context);
		synchronized(this) {
			if (decode) {
				lastCompressionCodec = codec;
				lastWasMetadata = metadata;
				if(keepLastData) {
					// FIXME inefficient to convert from bucket to byte[] to bucket
					if(lastRequestData != null)
						lastRequestData.free();
					try {
						lastRequestData = BucketTools.makeImmutableBucket(context.tempBucketFactory, data);
					} catch (IOException e) {
						Logger.error(this, "Caught "+e, e);
					}
				}
			}
		}
	}

	private SendableGet runningStoreChecker = null;
	
	class USKStoreChecker {
		
		final USKWatchingKeys.KeyList.StoreSubChecker[] checkers;

		public USKStoreChecker(USKWatchingKeys.KeyList.StoreSubChecker sub) {
			checkers = new USKWatchingKeys.KeyList.StoreSubChecker[] { sub };
		}

		public USKStoreChecker(USKWatchingKeys.KeyList.StoreSubChecker[] checkers2) {
			checkers = checkers2;
		}

		public Key[] getKeys() {
			if(checkers.length == 0) return new Key[0];
			else if(checkers.length == 1) return checkers[0].keysToCheck;
			else {
				int x = 0;
				for(USKWatchingKeys.KeyList.StoreSubChecker checker : checkers) {
					x += checker.keysToCheck.length;
				}
				Key[] keys = new Key[x];
				int ptr = 0;
				// FIXME more intelligent (cheaper) merging algorithm, e.g. considering the ranges in each.
				HashSet<Key> check = new HashSet<Key>();
				for(USKWatchingKeys.KeyList.StoreSubChecker checker : checkers) {
					if(checker == null) continue;
					for(Key k : checker.keysToCheck) {
						if(!check.add(k)) continue;
						keys[ptr++] = k;
					}
				}
				if(keys.length != ptr) {
					Key[] newKeys = new Key[ptr];
					System.arraycopy(keys, 0, newKeys, 0, ptr);
					keys = newKeys;
				}
				return keys;
			}
		}

		public void checked() {
			for(USKWatchingKeys.KeyList.StoreSubChecker checker : checkers) {
				checker.checked();
			}
		}
		
	}

	private synchronized void fillKeysWatching(long ed, ClientContext context) {
		final USKStoreChecker checker = watchingKeys.getDatastoreChecker(ed);
		if(checker == null) return;
		final Key[] checkStore = checker.getKeys();
			
		SendableGet storeChecker = new SendableGet(parent) {
			
			boolean done = false;

			@Override
			public FetchContext getContext() {
				return ctx;
			}

			@Override
			public long getCooldownWakeup(Object token, ObjectContainer container) {
				return -1;
			}

			@Override
			public long getCooldownWakeupByKey(Key key, ObjectContainer container) {
				return -1;
			}

			@Override
			public ClientKey getKey(Object token, ObjectContainer container) {
				return null;
			}

			@Override
			public boolean ignoreStore() {
				return false;
			}

			@Override
			public Key[] listKeys(ObjectContainer container) {
				return checkStore;
			}

			@Override
			public void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context) {
				// Ignore
			}

			@Override
			public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
				// Ignore
			}

			@Override
			public void resetCooldownTimes(ObjectContainer container) {
				// Ignore
			}

			@Override
			public boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
				return true;
			}

			@Override
			public void preRegister(ObjectContainer container, ClientContext context, boolean toNetwork) {
				unregister(container, context, getPriorityClass(container));
				USKAttempt[] attempts;
				synchronized(USKFetcher.this) {
					if(cancelled) return;
					runningStoreChecker = null;
					// FIXME should we only start the USKAttempt's if the datastore check hasn't made progress?
					attempts = attemptsToStart.toArray(new USKAttempt[attemptsToStart.size()]);
					attemptsToStart.clear();
					done = true;
				}
				checker.checked();
				
				if(logMINOR) Logger.minor(this, "Checked datastore, finishing registration for "+attempts.length+" checkers for "+USKFetcher.this+" for "+origUSK);
				if(attempts.length > 0)
					parent.toNetwork(container, context);
				for(int i=0;i<attempts.length;i++) {
					long lastEd = uskManager.lookupLatestSlot(origUSK);
					// FIXME not sure this condition works, test it!
					if(keepLastData && lastRequestData == null && lastEd == origUSK.suggestedEdition)
						lastEd--; // If we want the data, then get it for the known edition, so we always get the data, so USKInserter can compare it and return the old edition if it is identical.
					if(attempts[i] == null) continue;
					if(attempts[i].number > lastEd)
						attempts[i].schedule(container, context);
					else {
						synchronized(USKFetcher.this) {
							runningAttempts.remove(attempts[i]);
						}
					}
				}
				long lastEd = uskManager.lookupLatestSlot(origUSK);
				// Do not check beyond WATCH_KEYS after the current slot.
				fillKeysWatching(lastEd+1, context);
			}

			@Override
			public SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
				return null;
			}

			@Override
			public long countAllKeys(ObjectContainer container, ClientContext context) {
				return keysWatching.size();
			}

			@Override
			public long countSendableKeys(ObjectContainer container, ClientContext context) {
				return 0;
			}

			@Override
			public RequestClient getClient(ObjectContainer container) {
				return USKFetcher.this.uskManager;
			}

			@Override
			public ClientRequester getClientRequest() {
				return parent;
			}

			@Override
			public short getPriorityClass(ObjectContainer container) {
				return progressPollPriority; // FIXME
			}

			@Override
			public int getRetryCount() {
				return 0;
			}

			@Override
			public boolean isCancelled(ObjectContainer container) {
				return done;
			}

			@Override
			public boolean isSSK() {
				return true;
			}

			@Override
			public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
				return null;
			}

			public boolean isEmpty(ObjectContainer container) {
				return done;
			}
			
		};
		runningStoreChecker = storeChecker;
		try {
			context.getSskFetchScheduler().register(null, new SendableGet[] { storeChecker } , false, null, null, false);
		} catch (KeyListenerConstructionException e1) {
			// Impossible
			runningStoreChecker = null;
		} catch (Throwable t) {
			runningStoreChecker = null;
			Logger.error(this, "Unable to start: "+t, t);
			try {
				storeChecker.unregister(null, context, progressPollPriority);
			} catch (Throwable ignored) {
				// Ignore, hopefully it's already unregistered
			}
		}
	}

	public synchronized boolean isCancelled(ObjectContainer container) {
		return completed || cancelled;
	}

	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context) throws KeyListenerConstructionException {
		return this;
	}

	public void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Failed to construct KeyListener on USKFetcher: "+e, e);
	}

	public synchronized long countKeys() {
		return watchingKeys.size();
	}

	public synchronized short definitelyWantKey(Key key, byte[] saltedKey, ObjectContainer container, ClientContext context) {
		if(!(key instanceof NodeSSK)) return -1;
		NodeSSK k = (NodeSSK) key;
		if(!Arrays.equals(k.getPubKeyHash(), origUSK.pubKeyHash))
			return -1;
		if(watchingKeys.match(k) != -1) return progressPollPriority;
		return -1;
	}

	public HasKeyListener getHasKeyListener() {
		return this;
	}

	public short getPriorityClass(ObjectContainer container) {
		return progressPollPriority;
	}

	public SendableGet[] getRequestsForKey(Key key, byte[] saltedKey, ObjectContainer container, ClientContext context) {
		return new SendableGet[0];
	}

	public boolean handleBlock(Key key, byte[] saltedKey, KeyBlock found, ObjectContainer container, ClientContext context) {
		if(!(found instanceof SSKBlock)) return false;
		long edition = watchingKeys.match((NodeSSK)key);
		if(edition == -1) return false;
		if(logMINOR) Logger.minor(this, "Matched edition "+edition+" for "+origUSK);
		
		ClientSSKBlock data;
		try {
			data = watchingKeys.decode((SSKBlock)found, edition);
		} catch (SSKVerifyException e) {
			data = null;
		}
		onSuccess(null, edition, false, data, context);
		return true;
	}

	public synchronized boolean isEmpty() {
		return cancelled || completed;
	}

	public boolean isSSK() {
		return true;
	}

	public void onRemove() {
		// Ignore
	}

	public boolean persistent() {
		return false;
	}

	public synchronized boolean probablyWantKey(Key key, byte[] saltedKey) {
		if(!(key instanceof NodeSSK)) return false;
		NodeSSK k = (NodeSSK) key;
		if(!Arrays.equals(k.getPubKeyHash(), origUSK.pubKeyHash))
			return false;
		return watchingKeys.match(k) != -1;
	}

	private class USKWatchingKeys {
		
		// Common for whole USK
		final byte[] pubKeyHash;
		final byte cryptoAlgorithm;
		
		// List of slots since the USKManager's current last known good edition.
		private final KeyList fromLastKnownGood;
		/** Starting from origUSK. Will be nulled out when last known good passes it. */
		private KeyList fromOrigUSK;
		//private ArrayList<KeyList> fromCallbacks;
		
		// FIXME add more WeakReference<KeyList>'s: one for the origUSK, one for each subscriber who gave an edition number. All of which should disappear on the subscriber going or on the last known superceding.
		
		public USKWatchingKeys(USK origUSK, long lookedUp) {
			this.pubKeyHash = origUSK.pubKeyHash;
			this.cryptoAlgorithm = origUSK.cryptoAlgorithm;
			if(logMINOR) Logger.minor(this, "Creating KeyList from last known good: "+lookedUp);
			fromLastKnownGood = new KeyList(lookedUp);
			if(origUSK.suggestedEdition > lookedUp)
				fromOrigUSK = new KeyList(origUSK.suggestedEdition);
		}
		
		public long size() {
			return WATCH_KEYS + (fromOrigUSK == null ? 0 : WATCH_KEYS);
			// FIXME change when we add more KeyList's.
		}

		/** A precomputed list of E(H(docname))'s for each slot we might match.
		 * This is from an edition number which might be out of date. */
		class KeyList {

			/** The USK edition number of the first slot */
			long firstSlot;
			/** The precomputed E(H(docname)) for each such slot. */
			private WeakReference<RemoveRangeArrayList<byte[]>> cache;
			/** We have checked the datastore from this point. */
			private long checkedDatastoreFrom = -1;
			/** We have checked the datastore up to this point. */
			private long checkedDatastoreTo = -1;
			
			public KeyList(long slot) {
				if(logMINOR) Logger.minor(this, "Creating KeyList from "+slot+" on "+USKFetcher.this+" "+this, new Exception("debug"));
				firstSlot = slot;
				RemoveRangeArrayList<byte[]> ehDocnames = new RemoveRangeArrayList<byte[]>(WATCH_KEYS);
				cache = new WeakReference<RemoveRangeArrayList<byte[]>>(ehDocnames);
				generate(firstSlot, WATCH_KEYS, ehDocnames);
			}

			public class StoreSubChecker {
				
				/** Keys to check */
				final NodeSSK[] keysToCheck;
				/** The edition from which we will have checked after we have executed this. */
				private final long checkedFrom;
				/** The edition up to which we have checked after we have executed this. */
				private final long checkedTo;
				
				private StoreSubChecker(NodeSSK[] keysToCheck, long checkFrom, long checkTo) {
					this.keysToCheck = keysToCheck;
					this.checkedFrom = checkFrom;
					this.checkedTo = checkTo;
					if(logMINOR) Logger.minor(this, "Checking datastore from "+checkFrom+" to "+checkTo+" for "+USKFetcher.this+" on "+this);
				}

				/** The keys have been checked. */
				void checked() {
					synchronized(KeyList.this) {
						if(checkedDatastoreTo >= checkedFrom && checkedDatastoreFrom <= checkedFrom) {
							// checkedFrom is unchanged
							checkedDatastoreTo = checkedTo;
						} else {
							checkedDatastoreFrom = checkedFrom;
							checkedDatastoreTo = checkedTo;
						}
						if(logMINOR) Logger.minor(this, "Checked from "+checkedFrom+" to "+checkedTo+" (now overall is "+checkedDatastoreFrom+" to "+checkedDatastoreTo+")");
					}
					
				}
				
			}

			/**
			 * Check for WATCH_KEYS from lastSlot, but do not check any slots earlier than checkedDatastoreUpTo.
			 * Re-use the cache if possible, and extend it if necessary; all we need to construct a NodeSSK is the base data and the E(H(docname)), and we have that.
			 */
			public synchronized StoreSubChecker checkStore(long lastSlot) {
				if(logDEBUG) Logger.minor(this, "check store from "+lastSlot+" current first slot "+firstSlot);
				long checkFrom = lastSlot;
				long checkTo = lastSlot + WATCH_KEYS;
				if(checkedDatastoreTo >= checkFrom) {
					checkFrom = checkedDatastoreTo;
				}
				if(checkFrom >= checkTo) return null; // Nothing to check.
				// Update the cache.
				RemoveRangeArrayList<byte[]> ehDocnames = updateCache(lastSlot);
				// Now create NodeSSK[] from the part of the cache that
				// ehDocnames[0] is firstSlot
				// ehDocnames[checkFrom-firstSlot] is checkFrom
				int offset = (int)(checkFrom - firstSlot);
				NodeSSK[] keysToCheck = new NodeSSK[WATCH_KEYS - offset];
				for(int x=0, i=offset; i<WATCH_KEYS; i++, x++) {
					keysToCheck[x] = new NodeSSK(pubKeyHash, ehDocnames.get(i), cryptoAlgorithm);
				}
				return new StoreSubChecker(keysToCheck, checkFrom, checkTo);
			}

			synchronized RemoveRangeArrayList<byte[]> updateCache(long curBaseEdition) {
				if(logDEBUG) Logger.minor(this, "update cache from "+curBaseEdition+" current first slot "+firstSlot);
				RemoveRangeArrayList<byte[]> ehDocnames = null;
				if(cache == null || (ehDocnames = cache.get()) == null) {
					ehDocnames = new RemoveRangeArrayList<byte[]>(WATCH_KEYS);
					cache = new WeakReference<RemoveRangeArrayList<byte[]>>(ehDocnames);
					firstSlot = curBaseEdition;
					generate(firstSlot, WATCH_KEYS, ehDocnames);
					return ehDocnames;
				}
				match(null, curBaseEdition, ehDocnames);
				return ehDocnames;
			}
			
			/** Update the key list if necessary based on the new base edition.
			 * Then try to match the given key. If it matches return the edition number.
			 * @param key The key we are trying to match. If null, just update the cache, do not 
			 * do any matching (used by checkStore(); it is only necessary to update the cache 
			 * if you are actually going to use it).
			 * @param curBaseEdition The new base edition.
			 * @return The edition number for the key, or -1 if the key is not a match.
			 */
			public synchronized long match(NodeSSK key, long curBaseEdition) {
				if(logDEBUG) Logger.minor(this, "match from "+curBaseEdition+" current first slot "+firstSlot);
				RemoveRangeArrayList<byte[]> ehDocnames = null;
				if(cache == null || (ehDocnames = cache.get()) == null) {
					ehDocnames = new RemoveRangeArrayList<byte[]>(WATCH_KEYS);
					cache = new WeakReference<RemoveRangeArrayList<byte[]>>(ehDocnames);
					firstSlot = curBaseEdition;
					generate(firstSlot, WATCH_KEYS, ehDocnames);
					return key == null ? -1 : innerMatch(key, ehDocnames, 0, ehDocnames.size(), firstSlot);
				}
				// Might as well check first.
				long x = innerMatch(key, ehDocnames, 0, ehDocnames.size(), firstSlot);
				if(x != -1) return x;
				return match(key, curBaseEdition, ehDocnames);
			}

			/** Update ehDocnames as needed according to the new curBaseEdition, then innerMatch against *only
			 * the changed parts*. The caller must already have done innerMatch over the passed in ehDocnames.
			 * @param curBaseEdition The edition to check from. If this is different to firstSlot, we will
			 * update ehDocnames. */
			private long match(NodeSSK key, long curBaseEdition, RemoveRangeArrayList<byte[]> ehDocnames) {
				if(logMINOR) Logger.minor(this, "Matching "+key+" cur base edition "+curBaseEdition+" first slot was "+firstSlot);
				if(firstSlot < curBaseEdition) {
					if(firstSlot + ehDocnames.size() <= curBaseEdition) {
						// No overlap. Clear it and start again.
						ehDocnames.clear();
						firstSlot = curBaseEdition;
						generate(curBaseEdition, WATCH_KEYS, ehDocnames);
						return key == null ? -1 : innerMatch(key, ehDocnames, 0, ehDocnames.size(), firstSlot);
					} else {
						// There is some overlap. Delete the first part of the array then add stuff at the end.
						// ehDocnames[i] is slot firstSlot + i
						// We want to get rid of anything before curBaseEdition
						// So the first slot that is useful is the slot at i = curBaseEdition - firstSlot
						// Which is the new [0], whose edition is curBaseEdition
						ehDocnames.removeRange(0, (int)(curBaseEdition - firstSlot));
						int size = ehDocnames.size();
						firstSlot = curBaseEdition;
						generate(curBaseEdition + size, WATCH_KEYS - size, ehDocnames);
						return key == null ? -1 : innerMatch(key, ehDocnames, WATCH_KEYS - size, size, firstSlot);
					}
				} else if(firstSlot > curBaseEdition) {
					// It has regressed???
					Logger.error(this, "First slot was "+firstSlot+" now is "+curBaseEdition+" on "+USKFetcher.this+" "+this, new Exception("debug"));
					firstSlot = curBaseEdition;
					ehDocnames.clear();
					generate(curBaseEdition, WATCH_KEYS, ehDocnames);
					return key == null ? -1 : innerMatch(key, ehDocnames, 0, ehDocnames.size(), firstSlot);
				}
				return -1;
			}

			/** Do the actual match, using the current firstSlot, and a specified offset and length within the array. */
			private long innerMatch(NodeSSK key, RemoveRangeArrayList<byte[]> ehDocnames, int offset, int size, long firstSlot) {
				byte[] data = key.getKeyBytes();
				for(int i=offset;i<(offset+size);i++) {
					if(Arrays.equals(data, ehDocnames.get(i))) return firstSlot+i;
				}
				return -1;
			}

			/** Append a series of E(H(docname))'s to the array.
			 * @param baseEdition The edition to start from.
			 * @param keys The number of keys to add.
			 */
			private void generate(long baseEdition, int keys, RemoveRangeArrayList<byte[]> ehDocnames) {
				if(logMINOR) Logger.minor(this, "generate() from "+baseEdition+" for "+origUSK);
				assert(baseEdition >= 0);
				for(int i=0;i<keys;i++) {
					long ed = baseEdition + i;
					if(logDEBUG) Logger.debug(this, "Slot "+i+" on "+origUSK+" is edition "+ed+" : "+origUSK.getSSK(ed));
					ehDocnames.add(origUSK.getSSK(ed).ehDocname);
				}
			}

		}
		
		public USKStoreChecker getDatastoreChecker(long lastSlot) {
			// Check WATCH_KEYS from last known good slot.
			// FIXME: Take into account origUSK, subscribers, etc.
			if(logMINOR) Logger.minor(this, "Getting datastore checker from "+lastSlot+" for "+origUSK+" on "+USKFetcher.this, new Exception("debug"));
			KeyList.StoreSubChecker sub = 
				fromLastKnownGood.checkStore(lastSlot);
			KeyList.StoreSubChecker subOrig =
				fromOrigUSK == null ? null : fromOrigUSK.checkStore(origUSK.suggestedEdition);
			if(sub == null)
				return null;
			else if(subOrig != null)
				return new USKStoreChecker(new KeyList.StoreSubChecker[] { sub, subOrig });
			else
				return new USKStoreChecker(sub);
		}

		public ClientSSKBlock decode(SSKBlock block, long edition) throws SSKVerifyException {
			ClientSSK csk = origUSK.getSSK(edition);
			assert(Arrays.equals(csk.ehDocname, block.getKey().getKeyBytes()));
			return ClientSSKBlock.construct(block, csk);
		}
		
		public long match(NodeSSK key) {
			long lastSlot = uskManager.lookupLatestSlot(origUSK) + 1;
			long ret = fromLastKnownGood.match(key, lastSlot);
			if(ret != -1 || fromOrigUSK == null) return ret;
			return fromOrigUSK.match(key, origUSK.suggestedEdition);
			// FIXME add more WeakReference<KeyList>'s for each subscriber who gave an edition number. All of which should disappear on the subscriber going or on the last known superceding.
		}
		
	}
	

	
}
