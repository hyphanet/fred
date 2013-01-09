/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * Insert a USK. The algorithm is simply to do a thorough search for the latest edition, and insert at the
 * following slot. Thereafter, if we get a collision, increment our slot; if we get more than 5 consecutive
 * collisions, search for the latest slot again.
 */
public class USKInserter implements ClientPutState, USKFetcherCallback, PutCompletionCallback {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	// Stuff to be passed on to the SingleBlockInserter
	final BaseClientPutter parent;
	Bucket data;
	final short compressionCodec;
	final InsertContext ctx;
	final PutCompletionCallback cb;
	final boolean isMetadata;
	final int sourceLength;
	final int token;
	final boolean getCHKOnly;
	public final Object tokenObject;
	final boolean persistent;
	final boolean realTimeFlag;
	
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
	final int hashCode;
	private final int extraInserts;
	final byte cryptoAlgorithm;
	final byte[] forceCryptoKey;
	
	@Override
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
		if(persistent)
			container.activate(pubUSK, 5);
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "scheduling fetcher for "+pubUSK.getURI());
			if(finished) return;
			fetcher = context.uskManager.getFetcherForInsertDontSchedule(persistent ? pubUSK.copy() : pubUSK, parent.priorityClass, this, parent.getClient(), container, context, persistent);
			if(logMINOR)
				Logger.minor(this, "scheduled: "+fetcher);
		}
		if(persistent) {
			container.store(fetcher);
			container.store(this);
		}
		fetcher.schedule(container, context);
	}

	@Override
	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean lastContentWasMetadata, short codec, byte[] hisData, boolean newKnownGood, boolean newSlotToo) {
		boolean alreadyInserted = false;
		synchronized(this) {
			edition = Math.max(l, edition);
			consecutiveCollisions = 0;
			if((lastContentWasMetadata == isMetadata) && hisData != null
					&& (codec == compressionCodec)) {
				try {
					if(persistent) container.activate(data, 1);
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
			if(persistent) {
				container.activate(fetcher, 1);
				container.activate(fetcher.ctx, 1);
				fetcher.removeFrom(container, context);
				fetcher.ctx.removeFrom(container);
				fetcher = null;
				container.store(this);
			}
		}
		if(alreadyInserted) {
			if(persistent) container.activate(parent, 1);
			// Success!
			parent.completedBlock(true, container, context);
			if(persistent) {
				container.activate(cb, 1);
				container.activate(pubUSK, 5);
			}
			cb.onEncode(pubUSK.copy(edition), this, container, context);
			insertSucceeded(container, context, l);
			if(freeData) {
				data.free();
				if(persistent) data.removeFrom(container);
			}
		} else {
			scheduleInsert(container, context);
		}
	}

	private void insertSucceeded(ObjectContainer container, ClientContext context, long edition) {
		if(logMINOR) Logger.minor(this, "Inserted to edition "+edition+" - inserting USK date hints...");
		USKDateHint hint = USKDateHint.now();
		MultiPutCompletionCallback m = new MultiPutCompletionCallback(cb, parent, tokenObject, persistent, true);
		byte[] hintData;
		try {
			hintData = hint.getData(edition).getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error(e); // Impossible
		}
		boolean cbActive = true;
		boolean parentActive = true;
		if(persistent) {
			container.activate(privUSK, 5);
			container.activate(pubUSK, 5);
			if(!container.ext().isActive(cb)) {
				cbActive = false;
				container.activate(cb, 1);
			}
			if(!container.ext().isActive(parent)) {
				parentActive = false;
				container.activate(parent, 1);
			}
		}
		FreenetURI[] hintURIs = hint.getInsertURIs(privUSK);
		boolean added = false;
		for(FreenetURI uri : hintURIs) {
			try {
				Bucket bucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent), hintData);
				SingleBlockInserter sb = 
					new SingleBlockInserter(parent, bucket, (short) -1, uri,
							ctx, realTimeFlag, m, false, sourceLength, token, getCHKOnly, true, true /* we don't use it */, null, container, context, persistent, false, extraInserts, cryptoAlgorithm, forceCryptoKey);
				Logger.normal(this, "Inserting "+uri+" with "+sb+" for insert of "+pubUSK);
				m.add(sb, container);
				sb.schedule(container, context);
				added = true;
			} catch (IOException e) {
				Logger.error(this, "Unable to insert USK date hints due to disk I/O error: "+e, e);
				if(!added) {
					cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, pubUSK.getSSK(edition).getURI()), this, container, context);
					return;
				} // Else try to insert the other hints.
			} catch (InsertException e) {
				Logger.error(this, "Unable to insert USK date hints due to error: "+e, e);
				if(!added) {
					cb.onFailure(e, this, container, context);
					return;
				} // Else try to insert the other hints.
			}
		}
		cb.onTransition(this, m, container);
		m.arm(container, context);
		if(!parentActive)
			container.deactivate(parent, 1);
		if(!cbActive)
			container.deactivate(cb, 1);
	}

	private void scheduleInsert(ObjectContainer container, ClientContext context) {
		long edNo = Math.max(edition, context.uskManager.lookupLatestSlot(pubUSK)+1);
		if(persistent) {
			container.activate(privUSK, 5);
			container.activate(pubUSK, 5);
			container.activate(parent, 1);
		}
		synchronized(this) {
			if(finished) return;
			edition = edNo;
			if(logMINOR)
				Logger.minor(this, "scheduling insert for "+pubUSK.getURI()+ ' ' +edition);
			sbi = new SingleBlockInserter(parent, data, compressionCodec, privUSK.getInsertableSSK(edition).getInsertURI(),
					ctx, realTimeFlag, this, isMetadata, sourceLength, token, getCHKOnly, false, true /* we don't use it */, tokenObject, container, context, persistent, false, extraInserts, cryptoAlgorithm, forceCryptoKey);
		}
		try {
			sbi.schedule(container, context);
			if(persistent) container.store(this);
		} catch (InsertException e) {
			synchronized(this) {
				finished = true;
			}
			if(freeData) {
				if(persistent) container.activate(data, 1);
				data.free();
				if(persistent) data.removeFrom(container);
				synchronized(this) {
					data = null;
				}
			}
			if(persistent) container.store(this);
			cb.onFailure(e, this, container, context);
		}
	}

	@Override
	public synchronized void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(pubUSK, 5);
		USK newEdition = pubUSK.copy(edition);
		finished = true;
		sbi = null;
		FreenetURI targetURI = pubUSK.getSSK(edition).getURI();
		FreenetURI realURI = ((SingleBlockInserter)state).getURI(container, context);
		if(!targetURI.equals(realURI))
			Logger.error(this, "URI should be "+targetURI+" actually is "+realURI);
		else {
			if(logMINOR)
				Logger.minor(this, "URI should be "+targetURI+" actually is "+realURI);
			context.uskManager.updateKnownGood(pubUSK, edition, context);
		}
		if(persistent) state.removeFrom(container, context);
		if(freeData) {
			if(persistent) container.activate(data, 1);
			data.free();
			if(persistent) data.removeFrom(container);
			data = null;
			if(persistent) container.store(this);
		}
		if(persistent) {
			container.activate(cb, 1);
			container.store(this);
		}
		cb.onEncode(newEdition, this, container, context);
		insertSucceeded(container, context, edition);
		// FINISHED!!!! Yay!!!
	}

	@Override
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		ClientPutState oldSBI;
		synchronized(this) {
			oldSBI = sbi;
			sbi = null;
			if(e.getMode() == InsertException.COLLISION) {
				// Try the next slot
				edition++;
				consecutiveCollisions++;
				if(persistent) container.store(this);
				if(consecutiveCollisions > MAX_TRIED_SLOTS)
					scheduleFetcher(container, context);
				else
					scheduleInsert(container, context);
			} else {
				Bucket d = null;
				synchronized(this) {
					finished = true;
					if(freeData) {
						d = data;
						data = null;
					}
				}
				if(freeData) {
					if(persistent) container.activate(d, 1);
					d.free();
					if(persistent) d.removeFrom(container);
					if(persistent) container.store(this);
				}
				if(persistent)
					container.activate(cb, 1);
				cb.onFailure(e, state, container, context);
			}
		}
		if(state != null && persistent) {
			state.removeFrom(container, context);
		}
		if(oldSBI != null && oldSBI != state && persistent) {
			container.activate(oldSBI, 1);
			oldSBI.removeFrom(container, context);
		}
	}

	@Override
	public int hashCode() {
		return hashCode;
	}
	
	public USKInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, 
			boolean getCHKOnly, boolean addToParent, Object tokenObject, ObjectContainer container, ClientContext context, boolean freeData, boolean persistent, boolean realTimeFlag, int extraInserts, byte cryptoAlgorithm, byte[] forceCryptoKey) throws MalformedURLException {
		this.hashCode = super.hashCode();
		this.tokenObject = tokenObject;
		this.persistent = persistent;
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
			parent.addMustSucceedBlocks(1, container);
			parent.notifyClients(container, context);
		}
		privUSK = InsertableUSK.createInsertable(uri, persistent);
		pubUSK = privUSK.getUSK();
		edition = pubUSK.suggestedEdition;
		this.freeData = freeData;
		this.extraInserts = extraInserts;
		this.cryptoAlgorithm = cryptoAlgorithm;
		this.forceCryptoKey = forceCryptoKey;
		this.realTimeFlag = realTimeFlag;
	}

	@Override
	public BaseClientPutter getParent() {
		return parent;
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		USKFetcherTag tag;
		boolean persist = persistent;
		synchronized(this) {
			if(finished) return;
			finished = true;
			tag = fetcher;
			fetcher = null;
		}
		if(persistent) container.store(this);
		if(tag != null) {
			tag.cancel(container, context);
			if(persist) container.activate(this, 1); // May have been deactivated by callbacks
		}
		if(sbi != null) {
			sbi.cancel(container, context); // will call onFailure, which will removeFrom()
			if(persist) container.activate(this, 1); // May have been deactivated by callbacks
		}
		if(freeData) {
			if(data == null) {
				if(persistent) {
					if(container.ext().isActive(this))
						Logger.error(this, "data = null in cancel() on "+this+" even though active");
					else
						Logger.error(this, "Not active in cancel() on "+this);
				}
				Logger.error(this, "data == null in cancel() on "+this, new Exception("error"));
			} else {
				if(persistent) container.activate(data, 1);
				data.free();
				if(persistent) data.removeFrom(container);
				synchronized(this) {
					data = null;
				}
				if(persistent) container.store(this);
			}
		}
		if(persistent) container.activate(cb, 1);
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	@Override
	public void onFailure(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Fetcher failed to find the given edition or any later edition on "+this);
		scheduleInsert(container, context);
	}

	@Override
	public void onCancelled(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(fetcher != null) {
				if(persistent) {
					container.activate(fetcher, 1);
					container.activate(fetcher.ctx, 1);
					fetcher.ctx.removeFrom(container);
					fetcher.removeFrom(container, context);
				}
				fetcher = null;
			}
			if(finished) return;
		}
		Logger.error(this, "Unexpected onCancelled()", new Exception("error"));
		cancel(container, context);
	}

	@Override
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		// Shouldn't happen
		Logger.error(this, "Got onTransition("+oldState+ ',' +newState+ ')');
	}

	@Override
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Shouldn't happen
		Logger.error(this, "Got onMetadata("+m+ ',' +state+ ')');
	}

	@Override
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public Object getToken() {
		return tokenObject;
	}

	@Override
	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	@Override
	public short getPollingPriorityNormal() {
		return parent.getPriorityClass();
	}

	@Override
	public short getPollingPriorityProgress() {
		return parent.getPriorityClass();
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Removing from database: "+this, new Exception("debug"));
		// parent will remove self
		if(freeData && data != null && container.ext().isStored(data)) {
			try {
				data.free();
			} catch (Throwable t) {
				Logger.error(this, "Already freed? Caught in removeFrom on "+this+" : "+data+" : "+t, t);
			}
			data.removeFrom(container);
		}
		// ctx is passed in, cb will deal with
		// cb will remove self
		// tokenObject will be removed by creator
		container.activate(privUSK, 5);
		privUSK.removeFrom(container);
		container.activate(pubUSK, 5);
		pubUSK.removeFrom(container);
		if(fetcher != null) {
			Logger.error(this, "Fetcher tag still present: "+fetcher+" in removeFrom() for "+this, new Exception("debug"));
			container.activate(fetcher, 1);
			container.activate(fetcher.ctx, 1);
			fetcher.ctx.removeFrom(container);
			fetcher.removeFrom(container, context);
		}
		if(sbi != null) {
			Logger.error(this, "sbi still present: "+sbi+" in removeFrom() for "+this);
			container.activate(sbi, 1);
			sbi.removeFrom(container, context);
		}
		container.delete(this);
	}

	@Override
	public void onMetadata(Bucket meta, ClientPutState state,
			ObjectContainer container, ClientContext context) {
		Logger.error(this, "onMetadata on "+this+" from "+state, new Exception("error"));
		meta.free();
	}

//	public boolean objectCanNew(ObjectContainer container) {
//		Logger.minor(this, "objectCanNew() on "+this, new Exception("debug"));
//		return true;
//	}
//	
//	public boolean objectCanUpdate(ObjectContainer container) {
//		Logger.minor(this, "objectCanUpdate() on "+this, new Exception("debug"));
//		return true;
//	}
	
}
