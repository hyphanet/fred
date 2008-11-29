/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableUSK;
import freenet.keys.USK;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
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
	final InsertContext ctx;
	final PutCompletionCallback cb;
	final boolean isMetadata;
	final int sourceLength;
	final int token;
	final boolean getCHKOnly;
	public final Object tokenObject;
	
	final InsertableUSK privUSK;
	final USK pubUSK;
	/** Scanning for latest slot */
	private USKFetcherTag fetcher;
	/** Insert the actual SSK */
	private SingleBlockInserter sbi;
	private long edition;
	/** Number of collisions while trying to insert so far */
	private int consecutiveCollisions;
	private boolean finished;
	/** After attempting inserts on this many slots, go back to the Fetcher */
	private static final long MAX_TRIED_SLOTS = 10;
	private boolean freeData;
	
	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		// Caller calls schedule()
		// schedule() calls scheduleFetcher()
		// scheduleFetcher() creates a Fetcher (set up to tell us about author-errors as well as valid inserts)
		// (and starts it)
		// when this completes, onFoundEdition() calls scheduleInsert()
		// scheduleInsert() starts a SingleBlockInserter
		// if that succeeds, we complete
		// if that fails, we increment our index and try again (in the callback)
		// if that continues to fail 5 times, we go back to scheduleFetcher()
		scheduleFetcher(container, context);
	}

	/**
	 * Schedule a Fetcher to find us the latest inserted key of the USK.
	 * The Fetcher must be insert-mode, in other words, it must know that we want the latest edition,
	 * including author errors and so on.
	 */
	private void scheduleFetcher(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "scheduling fetcher for "+pubUSK.getURI());
			if(finished) return;
			fetcher = context.uskManager.getFetcherForInsertDontSchedule(pubUSK, parent.priorityClass, this, parent.getClient(), container, context);
		}
		fetcher.schedule(container, context);
	}

	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean lastContentWasMetadata, short codec, byte[] hisData) {
		boolean alreadyInserted = false;
		synchronized(this) {
			edition = Math.max(l, edition);
			consecutiveCollisions = 0;
			if((lastContentWasMetadata == isMetadata) && hisData != null
					&& (codec == compressionCodec)) {
				try {
					byte[] myData = BucketTools.toByteArray(data);
					if(Arrays.equals(myData, hisData)) {
						// Success
						alreadyInserted = true;
						finished = true;
						sbi = null;
					}
				} catch (IOException e) {
					Logger.error(this, "Could not decode: "+e, e);
				}
			}
			if(!alreadyInserted) {
				if(parent.persistent())
					fetcher.removeFromDatabase(container);
				fetcher = null;
			}
		}
		if(alreadyInserted) {
			// Success!
			cb.onEncode(pubUSK.copy(edition), this, container, context);
			parent.addMustSucceedBlocks(1, container);
			parent.completedBlock(true, container, context);
			cb.onSuccess(this, container, context);
			if(freeData) data.free();
		} else {
			scheduleInsert(container, context);
		}
	}

	private void scheduleInsert(ObjectContainer container, ClientContext context) {
		long edNo = Math.max(edition, context.uskManager.lookup(pubUSK)+1);
		synchronized(this) {
			if(finished) return;
			edition = edNo;
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "scheduling insert for "+pubUSK.getURI()+ ' ' +edition);
			sbi = new SingleBlockInserter(parent, data, compressionCodec, privUSK.getInsertableSSK(edition).getInsertURI(),
					ctx, this, isMetadata, sourceLength, token, getCHKOnly, false, true /* we don't use it */, tokenObject, container, context, parent.persistent(), freeData);
		}
		try {
			sbi.schedule(container, context);
		} catch (InsertException e) {
			cb.onFailure(e, this, container, context);
			synchronized(this) {
				finished = true;
			}
			if(freeData)
				data.free();
		}
	}

	public synchronized void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		cb.onEncode(pubUSK.copy(edition), this, container, context);
		cb.onSuccess(this, container, context);
		finished = true;
		sbi = null;
		FreenetURI targetURI = pubUSK.getSSK(edition).getURI();
		FreenetURI realURI = ((SingleBlockInserter)state).getURI(container, context);
		if(!targetURI.equals(realURI))
			Logger.error(this, "URI should be "+targetURI+" actually is "+realURI);
		else {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "URI should be "+targetURI+" actually is "+realURI);
			context.uskManager.update(pubUSK, edition, context);
		}
		// FINISHED!!!! Yay!!!
	}

	public synchronized void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		sbi = null;
		if(e.getMode() == InsertException.COLLISION) {
			// Try the next slot
			edition++;
			if(consecutiveCollisions++ > MAX_TRIED_SLOTS)
				scheduleFetcher(container, context);
			else
				scheduleInsert(container, context);
		} else {
			cb.onFailure(e, state, container, context);
		}
	}

	public USKInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, 
			boolean getCHKOnly, boolean addToParent, Object tokenObject, ObjectContainer container, ClientContext context, boolean freeData) throws MalformedURLException {
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
			parent.addBlock(container);
			parent.addMustSucceedBlocks(1, container);
			parent.notifyClients(container, context);
		}
		privUSK = InsertableUSK.createInsertable(uri);
		pubUSK = privUSK.getUSK();
		edition = pubUSK.suggestedEdition;
		this.freeData = freeData;
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(fetcher != null)
			fetcher.cancel(container, context);
		if(sbi != null)
			sbi.cancel(container, context);
		synchronized(this) {
			finished = true;
			fetcher = null;
		}
		if(freeData)
			data.free();
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		Logger.error(this, "Fetcher failed", new Exception("debug"));
		scheduleInsert(container, context);
	}

	public synchronized void onCancelled(ObjectContainer container, ClientContext context) {
		if(fetcher != null) {
			if(parent.persistent())
				fetcher.removeFromDatabase(container);
			fetcher = null;
		}
		if(finished) return;
		Logger.error(this, "Unexpected onCancelled()", new Exception("error"));
		cancel(container, context);
	}

	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		// Shouldn't happen
		Logger.error(this, "Got onTransition("+oldState+ ',' +newState+ ')');
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Shouldn't happen
		Logger.error(this, "Got onMetadata("+m+ ',' +state+ ')');
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public Object getToken() {
		return tokenObject;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	public short getPollingPriorityNormal() {
		return parent.getPriorityClass();
	}

	public short getPollingPriorityProgress() {
		return parent.getPriorityClass();
	}

}
