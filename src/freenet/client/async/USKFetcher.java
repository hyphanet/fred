package freenet.client.async;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.client.FetcherContext;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.KeyDecodeException;
import freenet.keys.USK;
import freenet.node.RequestStarter;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * 
 * On 0.7, this shouldn't however take more than 10 seconds or so; these are SSKs
 * we are talking about. If this is fast enough then people will use the "-" form.
 * 
 * FProxy should cause USKs with negative edition numbers to be redirected to USKs
 * with negative edition numbers.
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

	/** USK manager */
	private final USKManager uskManager;
	
	/** The USK to fetch */
	private final USK origUSK;
	
	/** Callbacks */
	private final LinkedList callbacks;

	/** Fetcher context */
	final FetcherContext ctx;
	
	/** Finished? */
	private boolean completed;
	
	/** Cancelled? */
	private boolean cancelled;
	
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
		boolean cancelled = false;
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
			if(checker != null) checker.cancel();
		}
		
		public void schedule() {
			if(checker == null)
				Logger.error(this, "Checker == null in schedule()", new Exception("error"));
			else
				checker.schedule();
		}
		
		public String toString() {
			return "USKAttempt for "+number+" for "+origUSK.getURI();
		}
		
		public short getPriority() {
			if(backgroundPoll)
				return RequestStarter.UPDATE_PRIORITY_CLASS;
			else
				return parent.getPriorityClass();
		}
	}
	
	private final Vector runningAttempts;
	
	private long lastFetchedEdition;
	private long lastAddedEdition;

	/** Number of keys to probe ahead. If they are all empty, then
	 * we have finished (unless in background poll mode) */
	long minFailures;
	final long origMinFailures;
	
	final long origSleepTime = 1000;
	final long maxSleepTime = 60 * 60 * 1000;
	long sleepTime = origSleepTime;

	// At most, probe 1000 editions ahead!
	private static final long MAX_MIN_FAILURES = 1000;

	private long valueAtSchedule;
	
	/** Keep going forever? */
	private final boolean backgroundPoll;
	
	private boolean started = false;
	
	USKFetcher(USK origUSK, USKManager manager, FetcherContext ctx, ClientRequester parent, int minFailures, boolean pollForever) {
		this.parent = parent;
		this.origUSK = origUSK;
		this.uskManager = manager;
		this.minFailures = this.origMinFailures = minFailures;
		runningAttempts = new Vector();
		callbacks = new LinkedList();
		subscribers = new HashSet();
		lastFetchedEdition = -1;
		lastAddedEdition = -1;
		this.ctx = ctx;
		this.backgroundPoll = pollForever;
	}
	
	void onDNF(USKAttempt att) {
		Logger.minor(this, "DNF: "+att);
		boolean finished = false;
		synchronized(this) {
			if(completed || cancelled) return;
			lastFetchedEdition = Math.max(lastFetchedEdition, att.number);
			runningAttempts.remove(att);
			if(runningAttempts.isEmpty()) {
				long curLatest = uskManager.lookup(origUSK);
				Logger.minor(this, "latest: "+curLatest+", last fetched: "+lastFetchedEdition+", curLatest+MIN_FAILURES: "+(curLatest+minFailures));
				if(started) {
					finished = true;
				}
			} else 
				Logger.minor(this, "Remaining: "+runningAttempts.size());
		}
		if(finished) {
			finishSuccess();
		}
	}
	
	private void finishSuccess() {
		if(backgroundPoll) {
			synchronized(this) {
				started = false; // don't finish before have rescheduled
				long valAtEnd = uskManager.lookup(origUSK);
				if(valAtEnd > valueAtSchedule) {
					// Have advanced.
					minFailures = origMinFailures;
					sleepTime = origSleepTime;
				} else {
					long newMinFailures = minFailures * 2;
					if(newMinFailures > MAX_MIN_FAILURES)
						newMinFailures = MAX_MIN_FAILURES;
					minFailures = newMinFailures;
				}
				long newSleepTime = sleepTime * 2;
				if(newSleepTime > maxSleepTime) newSleepTime = maxSleepTime;
				sleepTime = newSleepTime;
				long now = System.currentTimeMillis();
				long end = now + sleepTime;
				long newValAtEnd = valAtEnd;
				// FIXME do this without occupying a thread
				while(now < end && ((newValAtEnd = uskManager.lookup(origUSK)) == valAtEnd)) {
					long d = end - now;
					if(d > 0)
						try {
							wait(d);
						} catch (InterruptedException e) {
							// Maybe break? Go around loop.
						}
					now = System.currentTimeMillis();
				}
				if(newValAtEnd != valAtEnd) {
					minFailures = origMinFailures;
					sleepTime = origSleepTime;
				}
			}
			schedule();
		} else {
			long ed = uskManager.lookup(origUSK);
			USKFetcherCallback[] cb;
			synchronized(this) {
				completed = true;
				cb = (USKFetcherCallback[]) callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
			}
			for(int i=0;i<cb.length;i++)
				cb[i].onFoundEdition(ed, origUSK.copy(ed));
		}
	}

	void onSuccess(USKAttempt att, boolean dontUpdate, ClientSSKBlock block) {
		LinkedList l = null;
		synchronized(this) {
			runningAttempts.remove(att);
			long curLatest = att.number;
			long lastEd = uskManager.lookup(origUSK);
			if(!dontUpdate)
				uskManager.update(origUSK, curLatest);
			if(completed || cancelled) return;
			if(curLatest >= lastEd) {
				try {
					this.lastRequestData = block.decode(ctx.bucketFactory, 1025 /* it's an SSK */, true);
					this.lastCompressionCodec = block.getCompressionCodec();
					this.lastWasMetadata = block.isMetadata();
				} catch (KeyDecodeException e) {
					lastRequestData = null;
				} catch (IOException e) {
					lastRequestData = null;
				}
			}
			curLatest = Math.max(lastEd, curLatest);
			Logger.minor(this, "Latest: "+curLatest);
			long addTo = curLatest + minFailures;
			long addFrom = Math.max(lastAddedEdition + 1, curLatest + 1);
			if(addTo >= addFrom) {
				l = new LinkedList();
				for(long i=addFrom;i<=addTo;i++) {
					Logger.minor(this, "Adding checker for edition "+i);
					l.add(add(i));
				}
			}
			cancelBefore(curLatest);
			if(l == null) return;
			// If we schedule them here, we don't get icky recursion problems.
			else if(!cancelled) {
				for(Iterator i=l.iterator();i.hasNext();) {
					// We may be called recursively through onSuccess().
					// So don't start obsolete requests.
					USKAttempt a = (USKAttempt) i.next();
					lastEd = uskManager.lookup(origUSK);
					if(lastEd <= a.number && !a.cancelled)
						a.schedule();
				}
			}
		}
	}

	public void onCancelled(USKAttempt att) {
		synchronized(this) {
			runningAttempts.remove(att);
			if(!runningAttempts.isEmpty()) return;
		}
		if(cancelled)
			finishCancelled();
	}

	private void finishCancelled() {
		USKFetcherCallback[] cb;
		synchronized(this) {
			completed = true;
			cb = (USKFetcherCallback[]) callbacks.toArray(new USKCheckerCallback[callbacks.size()]);
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

	private synchronized void cancelBefore(long curLatest) {
		for(Iterator i=runningAttempts.iterator();i.hasNext();) {
			USKAttempt att = (USKAttempt) (i.next());
			if(att.number < curLatest) {
				att.cancel();
				i.remove();
			}
		}
	}

	/**
	 * Add a USKAttempt for another edition number.
	 * Caller is responsible for calling .schedule().
	 */
	private synchronized USKAttempt add(long i) {
		if(cancelled) return null;
		Logger.minor(this, "Adding USKAttempt for "+i+" for "+origUSK.getURI());
		if(!runningAttempts.isEmpty()) {
			USKAttempt last = (USKAttempt) runningAttempts.lastElement();
			if(last.number >= i)
				return null;
		}
		USKAttempt a = new USKAttempt(i);
		runningAttempts.add(a);
		lastAddedEdition = i;
		return a;
	}

	public FreenetURI getURI() {
		return origUSK.getURI();
	}

	public boolean isFinished() {
		return completed || cancelled;
	}

	public USK getOriginalUSK() {
		return origUSK;
	}

	public void schedule() {
		USKAttempt[] attempts;
		synchronized(this) {
			if(cancelled) return;
			valueAtSchedule = uskManager.lookup(origUSK);
			long startPoint = Math.max(origUSK.suggestedEdition, valueAtSchedule);
			for(long i=startPoint;i<startPoint+minFailures;i++)
				add(i);
			attempts = (USKAttempt[]) runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
			started = true;
		}
		if(!cancelled)
			for(int i=0;i<attempts.length;i++)
				attempts[i].schedule();
	}

	public void cancel() {
		USKAttempt[] attempts;
		synchronized(this) {
			cancelled = true;
			attempts = (USKAttempt[]) runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
		}
		for(int i=0;i<attempts.length;i++)
			attempts[i].cancel();
	}

	/** Set of interested USKCallbacks. Note that we don't actually
	 * send them any information - they are essentially placeholders,
	 * an alternative to a refcount. This could be replaced with a 
	 * Bloom filter or whatever, we only need .exists and .count.
	 */
	final HashSet subscribers;
	
	public synchronized void addSubscriber(USKCallback cb) {
		subscribers.add(cb);
	}

	public synchronized boolean hasSubscribers() {
		return !subscribers.isEmpty();
	}
	
	public synchronized void removeSubscriber(USKCallback cb) {
		subscribers.remove(cb);
	}

	public boolean hasLastData() {
		return this.lastRequestData != null;
	}

	public boolean lastContentWasMetadata() {
		return this.lastWasMetadata;
	}

	public short lastCompressionCodec() {
		return this.lastCompressionCodec;
	}

	public Bucket getLastData() {
		return this.lastRequestData;
	}

	public void freeLastData() {
		lastRequestData.free();
		lastRequestData = null;
	}

}
