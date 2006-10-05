/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;

import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableUSK;
import freenet.keys.USK;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;

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
	public final Object tokenObject;
	
	final InsertableUSK privUSK;
	final USK pubUSK;
	/** Scanning for latest slot */
	private USKFetcher fetcher;
	/** Insert the actual SSK */
	private SingleBlockInserter sbi;
	private long edition;
	/** Number of collisions while trying to insert so far */
	private int consecutiveCollisions;
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
	private synchronized void scheduleFetcher() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "scheduling fetcher for "+pubUSK.getURI());
		if(finished) return;
		fetcher = ctx.uskManager.getFetcherForInsertDontSchedule(pubUSK, parent.priorityClass, this, parent.getClient());
		fetcher.schedule();
	}

	public synchronized void onFoundEdition(long l, USK key) {
		edition = Math.max(l, edition);
		consecutiveCollisions = 0;
		if((fetcher.lastContentWasMetadata() == isMetadata) && fetcher.hasLastData()
				&& (fetcher.lastCompressionCodec() == compressionCodec)) {
			try {
				byte[] myData = BucketTools.toByteArray(data);
				byte[] hisData = BucketTools.toByteArray(fetcher.getLastData());
				fetcher.freeLastData();
				if(Arrays.equals(myData, hisData)) {
					// Success!
					cb.onEncode(pubUSK.copy(edition), this);
					cb.onSuccess(this);
					finished = true;
					sbi = null;
					return;
				}
			} catch (IOException e) {
				Logger.error(this, "Could not decode: "+e, e);
			}
		}
		fetcher = null;
		scheduleInsert();
	}

	private synchronized void scheduleInsert() {
		long edNo = Math.max(edition, ctx.uskManager.lookup(pubUSK))+1;
		if(finished) return;
		edition = edNo;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "scheduling insert for "+pubUSK.getURI()+" "+edition);
		sbi = new SingleBlockInserter(parent, data, compressionCodec, privUSK.getInsertableSSK(edition).getInsertURI(),
				ctx, this, isMetadata, sourceLength, token, getCHKOnly, false, true /* we don't use it */, tokenObject);
		try {
			sbi.schedule();
		} catch (InserterException e) {
			cb.onFailure(e, this);
		}
	}

	public synchronized void onSuccess(ClientPutState state) {
		cb.onEncode(pubUSK.copy(edition), this);
		cb.onSuccess(this);
		finished = true;
		sbi = null;
		FreenetURI targetURI = pubUSK.getSSK(edition).getURI();
		FreenetURI realURI = ((SingleBlockInserter)state).getURI();
		if(!targetURI.equals(realURI))
			Logger.error(this, "URI should be "+targetURI+" actually is "+realURI);
		else {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "URI should be "+targetURI+" actually is "+realURI);
			ctx.uskManager.update(pubUSK, edition);
		}
		// FINISHED!!!! Yay!!!
	}

	public synchronized void onFailure(InserterException e, ClientPutState state) {
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
			boolean getCHKOnly, boolean addToParent, Object tokenObject) throws MalformedURLException {
		this.tokenObject = tokenObject;
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

	public synchronized void onCancelled() {
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

	public Object getToken() {
		return tokenObject;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	public void onFetchable(ClientPutState state) {
		// Ignore
	}

}
