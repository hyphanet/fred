/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
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
	
	/** Edition number of the first key in keysWatching */
	private long firstKeyWatching = -1;
	/** A list of keys which we are interested in. This a sequence of SSKs starting 
	 * at the last known slot. */
	private final ArrayList<ClientSSK> keysWatching;
	private long checkedDatastoreUpTo = -1;
	private final ArrayList<USKAttempt> attemptsToStart;
	
	private static final int WATCH_KEYS = 50;
	
	/**
	 * Callbacks are told when the USKFetcher finishes, and unless background poll is
	 * enabled, they are only sent onFoundEdition *once*, on completion.
	 * 
	 * FIXME: Don't allow callbacks if backgroundPoll is enabled??
	 * @param cb
	 * @return
	 */
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
		keysWatching = new ArrayList<ClientSSK>();
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
		final long lastEd = uskManager.lookupLatestSlot(origUSK);
		long curLatest;
		boolean decode = false;
		Vector<USKAttempt> killAttempts;
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
		if(decode) {
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
					lastCompressionCodec = block.getCompressionCodec();
					lastWasMetadata = block.isMetadata();
					if(keepLastData) {
						if(lastRequestData != null)
							lastRequestData.free();
						lastRequestData = data;
					} else
						data.free();
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
			valueAtSchedule = Math.max(lookedUp, valueAtSchedule);
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
		synchronized(this) {
			localCallbacks = subscribers.toArray(new USKCallback[subscribers.size()]);
		}
		if(localCallbacks.length == 0) {
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
	
	private synchronized void fillKeysWatching(long ed, ClientContext context) {
		if(logMINOR) Logger.minor(this, "fillKeysWatching from "+ed+" for "+this+" : "+origUSK, new Exception("debug"));
//		if(firstKeyWatching == -1) {
//			firstKeyWatching = ed;
//			for(int i=0;i<WATCH_KEYS;i++) {
//				keysWatching.add(origUSK.getSSK(ed + i));
//			}
//		} else {
//			long first = firstKeyWatching;
//			long last = firstKeyWatching + keysWatching.size() - 1;
//			if(last < ed) {
				keysWatching.clear();
				for(int i=0;i<WATCH_KEYS;i++) {
					keysWatching.add(origUSK.getSSK(ed + i));
				}
//			} else {
//				int drop = (int) (ed - first);
//				ClientSSK[] keep = new ClientSSK[keysWatching.size() - drop];
//				for(int i=drop;i<keysWatching.size();i++)
//					keep[i-drop] = keysWatching.get(i);
//				keysWatching.clear();
//				for(ClientSSK ssk : keep)
//					keysWatching.add(ssk);
//				for(long l = last + 1; l < (ed + WATCH_KEYS); l++) {
//					keysWatching.add(origUSK.getSSK(l));
//				}
//			}
			firstKeyWatching = ed;
//		}
		if(runningStoreChecker != null) return;
		long firstCheck = Math.max(firstKeyWatching, checkedDatastoreUpTo + 1);
		final long lastCheck = firstKeyWatching + keysWatching.size() - 1;
		if(logMINOR) Logger.minor(this, "firstCheck="+firstCheck+" lastCheck="+lastCheck);
		if(lastCheck < firstCheck) return;
		int checkCount = (int) (lastCheck - firstCheck + 1);
		int offset = (int) (firstCheck - firstKeyWatching);
		final Key[] checkStore = new Key[checkCount];
		for(int i=0;i<checkStore.length;i++) {
			checkStore[i] = keysWatching.get(i+offset).getNodeKey(true);
		}
		assert(offset + checkStore.length == keysWatching.size());
		assert(keysWatching.get(keysWatching.size()-1).getURI().uskForSSK().getSuggestedEdition() == lastCheck);
		if(logMINOR) Logger.minor(this, "Checking from "+firstCheck+" to "+lastCheck+" for "+this+" : "+origUSK);
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
					checkedDatastoreUpTo = lastCheck;
				}
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
						synchronized(this) {
							runningAttempts.remove(attempts[i]);
						}
					}
				}
				long lastEd = uskManager.lookupLatestSlot(origUSK);
				// Do not check beyond WATCH_KEYS after the current slot.
				fillKeysWatching(lastEd, context);
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
		return keysWatching.size();
	}

	public synchronized short definitelyWantKey(Key key, byte[] saltedKey, ObjectContainer container, ClientContext context) {
		if(!(key instanceof NodeSSK)) return -1;
		NodeSSK k = (NodeSSK) key;
		if(!Arrays.equals(k.getPubKeyHash(), origUSK.pubKeyHash))
			return -1;
		for(ClientSSK ssk : keysWatching)
			if(ssk.getNodeKey(false).equals(key)) return progressPollPriority;
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
		ClientSSK realKey = null;
		long edition = -1;
		synchronized(this) {
			for(int i=0;i<keysWatching.size();i++) {
				ClientSSK ssk = keysWatching.get(i);
				if(ssk.getNodeKey(false).equals(key)) {
					realKey = ssk;
					edition = firstKeyWatching + i;
					break;
				}
			}
			if(realKey == null) return false;
		}
		// FIXME remove
		assert(edition == realKey.getURI().uskForSSK().getSuggestedEdition());
		onFoundEdition(edition, origUSK, container, context, false, (short)-1, null, false, false);
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
		for(ClientSSK ssk : keysWatching)
			if(ssk.getNodeKey(false).equals(key)) return true;
		return false;
	}

}
