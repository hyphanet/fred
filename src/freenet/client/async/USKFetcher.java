package freenet.client.async;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetcherContext;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * 
 * On 0.7, this shouldn't however take more than 10 seconds or so; these are SSKs
 * we are talking about. If this is fast enough then people will use the "-" form.
 * 
 * Fproxy should cause USKs with negative edition numbers to be redirected to USKs
 * with negative edition numbers.
 * 
 * If the number specified is up to date, we just do the fetch. If a more recent
 * USK can be found, then we fail with an exception with the new version. The
 * client is expected to redirect to this. The point here is that fproxy will then
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

	final ClientGetter parent;
	
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
		public void onSuccess() {
			checker = null;
			succeeded = true;
			USKFetcher.this.onSuccess(this, false);
		}
		
		public void onFatalAuthorError() {
			checker = null;
			// Counts as success except it doesn't update
			USKFetcher.this.onSuccess(this, true);
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
	}
	
	private final Vector runningAttempts;
	
	private long lastFetchedEdition;
	private long lastAddedEdition;
	
	static final long MIN_FAILURES = 3;
	
	USKFetcher(USK origUSK, USKManager manager, FetcherContext ctx, ClientGetter parent) {
		this.parent = parent;
		this.origUSK = origUSK;
		this.uskManager = manager;
		runningAttempts = new Vector();
		callbacks = new LinkedList();
		lastFetchedEdition = -1;
		lastAddedEdition = -1;
		this.ctx = ctx;
	}
	
	void onDNF(USKAttempt att) {
		Logger.minor(this, "DNF: "+att);
		boolean finished = false;
		synchronized(this) {
			lastFetchedEdition = Math.max(lastFetchedEdition, att.number);
			runningAttempts.remove(att);
			if(runningAttempts.isEmpty()) {
				long curLatest = uskManager.lookup(origUSK);
				Logger.minor(this, "latest: "+curLatest+", last fetched: "+lastFetchedEdition+", curLatest+MIN_FAILURES: "+(curLatest+MIN_FAILURES));
				if(curLatest + MIN_FAILURES >= lastFetchedEdition) {
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
		long ed = uskManager.lookup(origUSK);
		USKFetcherCallback[] cb;
		synchronized(this) {
			completed = true;
			cb = (USKFetcherCallback[]) callbacks.toArray(new USKFetcherCallback[callbacks.size()]);
		}
		for(int i=0;i<cb.length;i++)
			cb[i].onFoundEdition(ed);
	}

	void onSuccess(USKAttempt att, boolean dontUpdate) {
		LinkedList l = null;
		synchronized(this) {
			runningAttempts.remove(att);
			long curLatest = att.number;
			if(!dontUpdate)
				uskManager.update(origUSK, curLatest);
			curLatest = Math.max(uskManager.lookup(origUSK), curLatest);
			Logger.minor(this, "Latest: "+curLatest);
			long addTo = curLatest + MIN_FAILURES;
			long addFrom = Math.max(lastAddedEdition + 1, curLatest + 1);
			if(addTo >= addFrom) {
				l = new LinkedList();
				for(long i=addFrom;i<=addTo;i++)
					l.add(add(i));
			}
			cancelBefore(curLatest);
		}
		if(l == null) return;
		else {
			for(Iterator i=l.iterator();i.hasNext();) {
				USKAttempt a = (USKAttempt) i.next();
				a.schedule();
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
			if(att.number < curLatest)
				att.cancel();
		}
	}

	/**
	 * Add a USKAttempt for another edition number.
	 * Caller is responsible for calling .schedule().
	 */
	private synchronized USKAttempt add(long i) {
		Logger.minor(this, "Adding USKAttempt for "+i+" for "+origUSK.getURI());
		if(!runningAttempts.isEmpty()) {
			USKAttempt last = (USKAttempt) runningAttempts.lastElement();
			if(last.number > i)
				throw new IllegalStateException("Adding "+i+" but last was "+last.number);
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

	public ClientGetter getParent() {
		return parent;
	}

	public void schedule() {
		USKAttempt[] attempts;
		synchronized(this) {
			for(long i=origUSK.suggestedEdition;i<origUSK.suggestedEdition+MIN_FAILURES;i++)
				add(i);
			attempts = (USKAttempt[]) runningAttempts.toArray(new USKAttempt[runningAttempts.size()]);
		}
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

}
