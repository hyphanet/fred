package freenet.client.async;

import java.net.MalformedURLException;

import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableUSK;
import freenet.keys.USK;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * Insert a USK. The algorithm is simply to do a thorough search for the latest edition, and insert at the
 * following slot. Thereafter, if we get a collision, increment our slot; if we get more than 5 consecutive
 * collisions, search for the latest slot again.
 */
public class USKInserter implements ClientPutState, USKFetcherCallback, PutCompletionCallback {

	// Stuff to be passed on to the SingleBlockInserter
	final BaseClientPutter parent;
	final Bucket data;
	final short compressionCodec;
	final InserterContext ctx;
	final PutCompletionCallback cb;
	final boolean isMetadata;
	final int sourceLength;
	final int token;
	final boolean getCHKOnly;
	
	final InsertableUSK privUSK;
	final USK pubUSK;
	/** Scanning for latest slot */
	private USKFetcher fetcher;
	/** Insert the actual SSK */
	private SingleBlockInserter sbi;
	private long edition;
	/** Number of collisions while trying to insert so far */
	private int consecutiveCollisions = 0;
	private boolean finished;
	/** After attempting inserts on this many slots, go back to the Fetcher */
	private static final long MAX_TRIED_SLOTS = 10;
	
	public void schedule() throws InserterException {
		// Caller calls schedule()
		// schedule() calls scheduleFetcher()
		// scheduleFetcher() creates a Fetcher (set up to tell us about author-errors as well as valid inserts)
		// (and starts it)
		// when this completes, onFoundEdition() calls scheduleInsert()
		// scheduleInsert() starts a SingleBlockInserter
		// if that succeeds, we complete
		// if that fails, we increment our index and try again (in the callback)
		// if that continues to fail 5 times, we go back to scheduleFetcher()
		scheduleFetcher();
	}

	/**
	 * Schedule a Fetcher to find us the latest inserted key of the USK.
	 * The Fetcher must be insert-mode, in other words, it must know that we want the latest edition,
	 * including author errors and so on.
	 */
	private void scheduleFetcher() {
		Logger.minor(this, "scheduling fetcher for "+pubUSK.getURI());
		synchronized(this) {
			if(finished) return;
			fetcher = ctx.uskManager.getFetcherForInsertDontSchedule(pubUSK, this);
		}
		fetcher.schedule();
	}

	public void onFoundEdition(long l, USK key) {
		edition = Math.max(l, edition);
		consecutiveCollisions = 0;
		fetcher = null;
		scheduleInsert();
	}

	private void scheduleInsert() {
		synchronized(this) {
			if(finished) return;
			long edNo = Math.max(edition, ctx.uskManager.lookup(pubUSK))+1;
			edition = edNo;
			Logger.minor(this, "scheduling insert for "+pubUSK.getURI()+" "+edition);
			try {
				sbi = new SingleBlockInserter(parent, data, compressionCodec, privUSK.getInsertableSSK(edition).getInsertURI(),
						ctx, this, isMetadata, sourceLength, token, getCHKOnly, false);
			} catch (InserterException e) {
				cb.onFailure(e, this);
				return;
			}
		}
		try {
			sbi.schedule();
		} catch (InserterException e) {
			cb.onFailure(e, this);
		}
	}

	public void onSuccess(ClientPutState state) {
		cb.onEncode(pubUSK.copy(edition), this);
		cb.onSuccess(this);
		synchronized(this) {
			finished = true;
			sbi = null;
		}
		FreenetURI targetURI = pubUSK.getSSK(edition).getURI();
		FreenetURI realURI = ((SingleBlockInserter)state).getURI();
		if(!targetURI.equals(realURI))
			Logger.error(this, "URI should be "+targetURI+" actually is "+realURI);
		else {
			Logger.minor(this, "URI should be "+targetURI+" actually is "+realURI);
			ctx.uskManager.update(pubUSK, edition);
		}
		// FINISHED!!!! Yay!!!
	}

	public void onFailure(InserterException e, ClientPutState state) {
		sbi = null;
		if(e.getMode() == InserterException.COLLISION) {
			// Try the next slot
			edition++;
			if(consecutiveCollisions++ > MAX_TRIED_SLOTS)
				scheduleFetcher();
			else
				scheduleInsert();
		} else {
			cb.onFailure(e, state);
		}
	}

	public USKInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InserterContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, 
			boolean getCHKOnly, boolean addToParent) throws MalformedURLException {
		this.parent = parent;
		this.data = data;
		this.compressionCodec = compressionCodec;
		this.ctx = ctx;
		this.cb = cb;
		this.isMetadata = isMetadata;
		this.sourceLength = sourceLength;
		this.token = token;
		this.getCHKOnly = getCHKOnly;
		if(addToParent) {
			parent.addBlock();
			parent.addMustSucceedBlocks(1);
			parent.notifyClients();
		}
		privUSK = InsertableUSK.createInsertable(uri);
		pubUSK = privUSK.getUSK();
		edition = pubUSK.suggestedEdition;
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public synchronized void cancel() {
		finished = true;
		if(fetcher != null)
			fetcher.cancel();
		if(sbi != null)
			sbi.cancel();
		cb.onFailure(new InserterException(InserterException.CANCELLED), this);
	}

	public void onFailure() {
		Logger.error(this, "Fetcher failed", new Exception("debug"));
		scheduleInsert();
	}

	public void onCancelled() {
		if(finished) return;
		Logger.error(this, "Unexpected onCancelled()", new Exception("error"));
		cancel();
	}

	public void onEncode(BaseClientKey key, ClientPutState state) {
		// Ignore
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		// Shouldn't happen
		Logger.error(this, "Got onTransition("+oldState+","+newState+")");
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		// Shouldn't happen
		Logger.error(this, "Got onMetadata("+m+","+state+")");
	}

	public void onBlockSetFinished(ClientPutState state) {
		// Ignore
	}

}
