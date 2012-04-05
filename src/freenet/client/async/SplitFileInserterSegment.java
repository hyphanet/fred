package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableInsert;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.store.KeyCollisionException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

public class SplitFileInserterSegment extends SendableInsert implements FECCallback, Encodeable {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, SplitFileInserterSegment.class);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, SplitFileInserterSegment.class);
			}
		});
	}

	final SplitFileInserter parent;
	final BaseClientPutter putter;

	final short splitfileAlgo;

	final Bucket[] dataBlocks;

	final Bucket[] checkBlocks;

	final ClientCHK[] dataURIs;

	final ClientCHK[] checkURIs;

	final int[] dataRetries;
	final int[] checkRetries;

	final int[] dataConsecutiveRNFs;
	final int[] checkConsecutiveRNFs;

	/** Block numbers not finished */
	final ArrayList<Integer> blocks;

	final boolean[] dataFinished;
	final boolean[] checkFinished;

	final boolean[] dataFailed;
	final boolean[] checkFailed;

	final int maxRetries;

	final InsertContext blockInsertContext;

	final int segNo;

	private volatile boolean encoded;

	private volatile boolean started;

	private volatile boolean finished;

	private volatile boolean hasURIs;

	private final boolean getCHKOnly;

	private InsertException toThrow;

	private final FailureCodeTracker errors;

	private int blocksGotURI;
	private int blocksSucceeded;
	private int blocksCompleted;

	private final boolean persistent;

	private FECJob encodeJob;
	
	private byte cryptoAlgorithm;
	private final byte[] cryptoKey;
	
	// Cross-segment redundancy
	
	private final int crossCheckBlocks;
	
	// Only used in initial allocation
	private transient int crossDataBlocksAllocated;
	private transient int crossCheckBlocksAllocated;
	
	private final SplitFileInserterCrossSegment[] crossSegmentsByBlock;
	
	/** When this reaches crossCheckBlocks, we can encode the check blocks. */
	private int encodedCrossCheckBlocks;

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private SplitFileInserterSegment() {
		splitfileAlgo = 0;
		segNo = 0;
		putter = null;
		persistent = false;
		parent = null;
		maxRetries = 0;
		getCHKOnly = false;
		errors = null;
		dataURIs = null;
		dataRetries = null;
		dataFinished = null;
		dataFailed = null;
		dataConsecutiveRNFs = null;
		dataBlocks = null;
		cryptoKey = null;
		crossSegmentsByBlock = null;
		crossCheckBlocks = 0;
		checkURIs = null;
		checkRetries = null;
		checkFinished = null;
		checkFailed = null;
		checkConsecutiveRNFs = null;
		checkBlocks = null;
		blocks = null;
		blockInsertContext = null;
	}

	public SplitFileInserterSegment(SplitFileInserter parent, boolean persistent, boolean realTimeFlag, BaseClientPutter putter,
			short splitfileAlgo, int crossCheckBlocks, int checkBlockCount, Bucket[] origDataBlocks,
			InsertContext blockInsertContext, boolean getCHKOnly, int segNo, byte cryptoAlgorithm, byte[] cryptoKey, ObjectContainer container) {
		super(persistent, realTimeFlag);
		this.crossCheckBlocks = crossCheckBlocks;
		this.crossSegmentsByBlock = new SplitFileInserterCrossSegment[origDataBlocks.length + crossCheckBlocks];
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.persistent = persistent;
		this.errors = new FailureCodeTracker(true);
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		if(crossCheckBlocks != 0) {
			// Cross check blocks count as data blocks for most purposes.
			this.dataBlocks = new Bucket[origDataBlocks.length + crossCheckBlocks];
			System.arraycopy(origDataBlocks, 0, dataBlocks, 0, origDataBlocks.length);
			origDataBlocks = dataBlocks;
		} else {
			this.dataBlocks = origDataBlocks;
		}
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new ClientCHK[checkBlockCount];
		dataURIs = new ClientCHK[origDataBlocks.length];
		dataRetries = new int[origDataBlocks.length];
		checkRetries = new int[checkBlockCount];
		dataFinished = new boolean[origDataBlocks.length];
		checkFinished = new boolean[checkBlockCount];
		dataFailed = new boolean[origDataBlocks.length];
		checkFailed = new boolean[checkBlockCount];
		dataConsecutiveRNFs = new int[origDataBlocks.length];
		checkConsecutiveRNFs = new int[checkBlockCount];
		blocks = new ArrayList<Integer>();
		putter.addMustSucceedBlocks(dataURIs.length, container);
		putter.addRedundantBlocks(checkURIs.length, container);
		this.segNo = segNo;
		if(persistent) container.activate(blockInsertContext, 1);
		maxRetries = blockInsertContext.maxInsertRetries;
		this.putter = putter;
		this.cryptoAlgorithm = cryptoAlgorithm;
		this.cryptoKey = cryptoKey;
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		synchronized(this) {
			if(crossCheckBlocks != 0) {
				// FIXME
				// This simplifies matters significantly, but it reduces performance.
				// We should really have a separate startEncode, and ensure that if we
				// are scheduled before we have all the cross check blocks we just don't select them.
				// See onEncodedCrossCheckBlock().
				if(encodedCrossCheckBlocks != crossCheckBlocks)
					return;
				if(logMINOR) Logger.minor(this, "Starting segment "+segNo);
			}
			if(started) return;
			started = true;
		}
		// Always called by parent, so don't activate or deactivate parent.
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
			container.activate(blocks, 2);
		}
		if (logMINOR) {
			if(parent == null) throw new NullPointerException();
			Logger.minor(this, "Starting segment " + segNo + " of " + parent
					+ " (" + parent.dataLength + "): " + this + " ( finished="
					+ finished + " encoded=" + encoded + " hasURIs=" + hasURIs
					+ " persistent=" + persistent + ')');
		}
		boolean fin = true;

		for (int i = 0; i < dataBlocks.length; i++) {
			if (dataBlocks[i] != null) { // else already finished on creation
				fin = false;
				synchronized(this) {
					blocks.add(i);
				}
			} else {
				parent.parent.completedBlock(true, container, context);
			}
		}
		// parent.parent.notifyClients();
		FECJob job = null;
		FECCodec splitfileAlgo = null;
		// FIXME double checked locking. It does however work because encoded is volatile.
		// But we could probably improve performance by synchronizing the whole block, 
		// and making encoded non-volatile, since the long lock time wouldn't matter as 
		// we don't do anything before this method returns anyway.
		if (!encoded) {
			if (logMINOR)
				Logger.minor(this, "Segment " + segNo + " of " + parent + " ("
						+ parent.dataLength + ") is not encoded");
			splitfileAlgo = FECCodec.getCodec(this.splitfileAlgo,
					dataBlocks.length, checkBlocks.length);
				if (logMINOR)
					Logger.minor(this, "Encoding segment " + segNo + " of "
							+ parent + " (" + parent.dataLength + ") persistent="+persistent);
				// Encode blocks
				synchronized(this) {
					if(!encoded){
						// FIXME necessary??? the queue is persistence aware, won't it activate them...?
						if(persistent) {
							for(int i=0;i<dataBlocks.length;i++)
								container.activate(dataBlocks[i], 5);
						}
						job = encodeJob = new FECJob(splitfileAlgo, context.fecQueue, dataBlocks, checkBlocks, CHKBlock.DATA_LENGTH, persistent ? context.persistentBucketFactory : context.tempBucketFactory, this, false, parent.parent.getPriorityClass(), persistent);
					}
				}
				fin = false;
		} else {
			for (int i = 0; i < checkBlocks.length; i++) {
				if (checkBlocks[i] != null) {
					synchronized(this) {
						blocks.add(i + dataBlocks.length);
					}
					fin = false;
				} else
					parent.parent.completedBlock(true, container, context);
			}
			onEncodedSegment(container, context, null, dataBlocks, checkBlocks, null, null);
		}
		if (hasURIs) {
			parent.segmentHasURIs(this, container, context);
		}
		boolean fetchable;
		synchronized (this) {
			fetchable = (blocksCompleted > dataBlocks.length);
		}
		if(persistent) {
			container.store(this);
			container.store(blocks);
		}
		if (fetchable)
			parent.segmentFetchable(this, container);
		if (fin)
			finish(container, context, parent);
		else
			schedule(container, context);
		if (finished) {
			finish(container, context, parent);
		}
		if(job != null) {
			splitfileAlgo.addToQueue(job, context.fecQueue, container);
		}
	}

	private void schedule(ObjectContainer container, ClientContext context) {
		if(!getCHKOnly) {
			this.getScheduler(container, context).registerInsert(this, persistent, false, container);
		} else {
			tryEncode(container, context);
		}
	}

	@Override
	public void tryEncode(ObjectContainer container, ClientContext context) {
		boolean deactivateParent = false;
		boolean deactivateParentCtx = false;
		if(persistent) {
			deactivateParent = !container.ext().isActive(parent);
			deactivateParentCtx = !container.ext().isActive(parent.ctx);
			if (deactivateParent)
				container.activate(parent, 1);
			if (deactivateParentCtx)
				container.activate(parent.ctx, 1);
		}
		if(parent == null) {
			Logger.error(this, "tryEncode() but parent is null!", new Exception("error"));
			return;
		} else if(parent.ctx == null) {
			Logger.error(this, "tryEncode() but parent.ctx is null!", new Exception("error"));
			return;
		}
		String compressorDescriptor = parent.ctx.compressorDescriptor;
		if(persistent) {
			if (deactivateParent)
				container.deactivate(parent, 1);
			if (deactivateParentCtx)
				container.deactivate(parent.ctx, 1);
		}
		byte cryptoAlgorithm = getCryptoAlgorithm(container);
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataURIs[i] == null && dataBlocks[i] != null) {
				try {
					boolean deactivate = false;
					if(persistent) {
						deactivate = !container.ext().isActive(dataBlocks[i]);
						if(deactivate) container.activate(dataBlocks[i], 1);
					}
					ClientCHK key = encodeBucket(dataBlocks[i], compressorDescriptor, cryptoAlgorithm, cryptoKey).getClientKey();
					if(deactivate) container.deactivate(dataBlocks[i], 1);
					onEncode(i, key, container, context);
				} catch (CHKEncodeException e) {
					fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);
				} catch (IOException e) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				}
			} else if(dataURIs[i] == null && dataBlocks[i] == null) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, "Data block "+i+" cannot be encoded: no data", null), container, context);
			}
		}
		if(encoded) {
			for(int i=0;i<checkBlocks.length;i++) {
				if(checkURIs[i] == null && checkBlocks[i] != null) {
					try {
						boolean deactivate = false;
						if(persistent) {
							deactivate = !container.ext().isActive(checkBlocks[i]);
							if(deactivate) container.activate(checkBlocks[i], 1);
						}
						ClientCHK key = encodeBucket(checkBlocks[i], compressorDescriptor, cryptoAlgorithm, cryptoKey).getClientKey();
						if(deactivate) container.deactivate(checkBlocks[i], 1);
						onEncode(i+dataBlocks.length, key, container, context);
					} catch (CHKEncodeException e) {
						fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);
					} catch (IOException e) {
						fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
					}
				} else if(checkURIs[i] == null && checkBlocks[i] == null) {
					fail(new InsertException(InsertException.INTERNAL_ERROR, "Data block "+i+" cannot be encoded: no data", null), container, context);
				}
			}
		}
	}

	private byte getCryptoAlgorithm(ObjectContainer container) {
		if(cryptoAlgorithm == 0) {
			cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
			if(persistent) container.store(this);
		}
		return cryptoAlgorithm;
	}

	@Override
	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {} // irrevelant

	@Override
	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
			container.activate(blocks, 2);
		}
		boolean fin;
		synchronized(this) {
			fin = finished;
			encodeJob = null;
		}
		if(removeOnEncode) {
			if(logMINOR) Logger.minor(this, "Removing on encode: "+this);
			freeBucketsArray(container, dataBuckets);
			freeBucketsArray(container, checkBuckets);
			removeFrom(container, context);
			return;
		}
		if(fin) {
			Logger.error(this, "Encoded segment even though segment finished! Freeing buckets...");
			freeBucketsArray(container, dataBuckets);
			freeBucketsArray(container, checkBuckets);
			return;
		}
		// Start the inserts
		try {
			if(logMINOR)
				Logger.minor(this, "Scheduling "+checkBlocks.length+" check blocks...");
			for (int i = 0; i < checkBlocks.length; i++) {
				// See comments on FECCallback: WE MUST COPY THE DATA BACK!!!
				checkBlocks[i] = checkBuckets[i];
				if(checkBlocks[i] == null) {
					if(logMINOR)
						Logger.minor(this, "Skipping check block "+i+" - is null");
					continue;
				}
				if(persistent)
					checkBlocks[i].storeTo(container);
				if(persistent) {
					container.deactivate(checkBlocks[i], 1);
				}
			}
			synchronized(this) {
				for(int i=0;i<checkBlocks.length;i++)
					blocks.add(dataBlocks.length + i);
			}
			if(persistent) container.store(blocks);
		} catch (Throwable t) {
			Logger.error(this, "Caught " + t + " while encoding " + this, t);
			InsertException ex = new InsertException(
					InsertException.INTERNAL_ERROR, t, null);
			finish(ex, container, context, parent);
			if(persistent)
				container.deactivate(parent, 1);
			return;
		}

		synchronized (this) {
			encoded = true;
		}

		if(persistent) {
			container.store(this);
			container.activate(parent, 1);
		}

		// Tell parent only after have started the inserts.
		// Because of the counting.
		parent.encodedSegment(this, container, context);

		synchronized (this) {
			freeFinishedDataBlocks(container);
		}

		if(persistent) {
			container.store(this);
			container.deactivate(parent, 1);
		}

		schedule(container, context);
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param ex
	 * @param container
	 * @param context
	 * @param parent
	 */
	void finish(InsertException ex, ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if (logMINOR)
			Logger.minor(this, "Finishing " + this + " with " + ex, ex);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = ex;
		}
		if(persistent) {
			container.store(this);
		}
		parent.segmentFinished(this, container, context);

		freeBucketsArray(container, dataBlocks);
		freeBucketsArray(container, checkBlocks);
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param container
	 * @param context
	 * @param parent
	 */
	private void finish(ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if(logMINOR) Logger.minor(this, "Finishing "+this);
		if(persistent)
			container.activate(errors, 5);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if(blocksSucceeded < blocksCompleted) {
				toThrow = InsertException.construct(errors);
				if(logMINOR) Logger.minor(this, "Blocks succeeded "+blocksSucceeded+" blocks completed "+blocksCompleted+" gives error "+toThrow+" on "+this);
			} else if(!hasURIs) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, "Completed but not encoded?!", null), container, context);
				return;
			}
		}
		if(persistent) {
			container.store(this);
			container.deactivate(errors, 5);
		}
		unregister(container, context, getPriorityClass(container));
		parent.segmentFinished(this, container, context);

		freeBucketsArray(container, dataBlocks);
		freeBucketsArray(container, checkBlocks);
	}

	private boolean onEncode(int x, ClientCHK key, ObjectContainer container, final ClientContext context) {
		if(logMINOR) Logger.minor(this, "Encoded block "+x+" on "+this);
		boolean gotAllURIs = false;
		synchronized (this) {
			if (finished) {
				if(logMINOR) Logger.minor(this, "Already finished");
				return false;
			}
			if (x >= dataBlocks.length) {
				if (checkURIs[x - dataBlocks.length] != null) {
					if(logMINOR) Logger.minor(this, "Already encoded check block");
					return false;
				}
				checkURIs[x - dataBlocks.length] = key;
			} else {
				if (dataURIs[x] != null) {
					if(logMINOR) Logger.minor(this, "Already encoded data block");
					return false;
				}
				dataURIs[x] = key;
			}
			blocksGotURI++;
			if(persistent)
				container.store(this);
			if(logMINOR)
				Logger.minor(this, "Blocks got URI: "+blocksGotURI+" of "+(dataBlocks.length + checkBlocks.length));
			gotAllURIs = blocksGotURI == dataBlocks.length + checkBlocks.length;
			if(gotAllURIs) {
				// Double check
				for (int i = 0; i < checkURIs.length; i++) {
					if (checkURIs[i] == null) {
						Logger.error(this, "Check URI " + i + " is null");
						gotAllURIs = false;
					}
				}
				for (int i = 0; i < dataURIs.length; i++) {
					if (dataURIs[i] == null) {
						Logger.error(this, "Data URI " + i + " is null");
						gotAllURIs = false;
					}
				}
				if(gotAllURIs)
					hasURIs = true;
			}
			if(!(getCHKOnly || hasURIs)) return false;
		}
		if(persistent) {
			container.activate(parent, 1);
			container.store(this);
		}
		if(gotAllURIs) {
			if(!persistent) {
				context.mainExecutor.execute(new Runnable() {
					
					@Override
					public void run() {
						parent.segmentHasURIs(SplitFileInserterSegment.this, null, context);
					}
					
				});
			} else {
				parent.segmentHasURIs(this, container, context);
			}
		}
		if(getCHKOnly) {
			byte cryptoAlgorithm = getCryptoAlgorithm(container);
			// FIXME refactor onSuccess to avoid creating the bucket.
			// Sometimes shadowing will fail and creating the bucket will involve copying. This is bad!
			try {
				BlockItem block = getBlockItem(container, context, x, cryptoAlgorithm);
				onSuccess(block, container, context);
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
			}
		}
		if(persistent)
			container.deactivate(parent, 1);
		return gotAllURIs;
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public boolean isEncoded() {
		return encoded;
	}

	public int countCheckBlocks() {
		return checkBlocks.length;
	}

	public int countDataBlocks() {
		return dataBlocks.length;
	}

	public ClientCHK[] getCheckCHKs() {
		return checkURIs;
	}

	/** Note that this includes cross-check blocks. */
	public ClientCHK[] getDataCHKs() {
		return dataURIs;
	}

	/** Get the InsertException for this segment.
	 * NOTE: This will be deleted when the segment is deleted! Do not store it or pass
	 * it on!
	 */
	InsertException getException(ObjectContainer container) {
		synchronized (this) {
			if(persistent) container.activate(toThrow, 5);
			return toThrow;
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if (toThrow != null)
				toThrow = new InsertException(InsertException.CANCELLED);
		}
		cancelInner(container, context);
	}

	private void cancelInner(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelling "+this);
		super.unregister(container, context, getPriorityClass(container));
		if(persistent) {
			container.store(this);
			container.activate(parent, 1);
		}
		parent.segmentFinished(this, container, context);
		freeBucketsArray(container, dataBlocks);
		freeBucketsArray(container, checkBlocks);
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		Logger.error(this, "Illegal transition in SplitFileInserterSegment: "
				+ oldState + " -> " + newState);
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got onMetadata from " + state);
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
		Logger.error(this, "Should not happen: onBlockSetFinished(" + state
				+ ") on " + this);
	}

	public synchronized boolean hasURIs() {
		return hasURIs;
	}

	public synchronized boolean isFetchable() {
		return blocksCompleted >= dataBlocks.length;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	/**
	 * Force the remaining blocks which haven't been encoded so far to be
	 * encoded ASAP.
	 */
	public void forceEncode(ObjectContainer container, ClientContext context) {
		context.backgroundBlockEncoder.queue(this, container, context);
	}

	public void fail(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Failing but already finished on "+this, new Exception("error"));
				return;
			}
			finished = true;
			Logger.error(this, "Insert segment failed: "+e+" for "+this, e);
			this.toThrow = e;
			if(persistent) container.store(this);
		}
		cancelInner(container, context);
	}

	@Override
	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
			Logger.error(this, "Insert segment failed: "+t+" for "+this, t);
			this.toThrow = new InsertException(InsertException.INTERNAL_ERROR, "FEC failure: "+t, null);
		}
		cancelInner(container, context);
	}

	Bucket getBucket(int blockNum) {
		if(blockNum >= dataBlocks.length)
			return checkBlocks[blockNum - dataBlocks.length];
		else
			return dataBlocks[blockNum];
	}

	private BlockItem getBlockItem(ObjectContainer container, ClientContext context, int blockNum, byte cryptoAlgorithm) throws IOException {
		Bucket sourceData = getBucket(blockNum);
		if(sourceData == null) {
			Logger.error(this, "Selected block "+blockNum+" but is null - already finished?? on "+this);
			return null;
		}
		boolean deactivateBucket = false;
		if(persistent) {
			deactivateBucket = !container.ext().isActive(sourceData);
			if(deactivateBucket)
				container.activate(sourceData, 1);
		}
		Bucket data = sourceData.createShadow();
		if(data == null) {
			data = context.tempBucketFactory.makeBucket(sourceData.size());
			BucketTools.copy(sourceData, data);
		}
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" : bucket "+sourceData+" shadow "+data);
		if(persistent) {
			if(deactivateBucket)
				container.deactivate(sourceData, 1);
		}
		return new BlockItem(this, blockNum, data, persistent, cryptoAlgorithm, cryptoKey);
	}

	private int hashCodeForBlock(int blockNum) {
		// FIXME: Standard hashCode() pattern assumes both inputs are evenly
		// distributed ... this is not true here.
		return hashCode() * (blockNum + 1);
	}

	private static class BlockItem implements SendableRequestItem {

		private final boolean persistent;
		private final Bucket copyBucket;
		private final int hashCode;
		/** STRICTLY for purposes of equals() !!! */
		private final SplitFileInserterSegment parent;
		private final int blockNum;
		final byte cryptoAlgorithm;
		public byte[] cryptoKey;

		BlockItem(SplitFileInserterSegment parent, int blockNum, Bucket bucket, boolean persistent, byte cryptoAlgorithm, byte[] cryptoKey) throws IOException {
			this.parent = parent;
			this.blockNum = blockNum;
			this.copyBucket = bucket;
			this.hashCode = parent.hashCodeForBlock(blockNum);
			this.persistent = persistent;
			this.cryptoAlgorithm = cryptoAlgorithm;
			this.cryptoKey = cryptoKey;
		}

		@Override
		public void dump() {
			copyBucket.free();
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == parent && ((BlockItem)o).blockNum == blockNum) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == parent && ((FakeBlockItem)o).blockNum == blockNum) return true;
			}
			return false;
		}

	}

	// Used for testing whether a block is already queued.
	private class FakeBlockItem implements SendableRequestItem {

		private final int blockNum;
		private final int hashCode;

		FakeBlockItem(int blockNum) {
			this.blockNum = blockNum;
			this.hashCode = hashCodeForBlock(blockNum);

		}

		@Override
		public void dump() {
			// Do nothing
		}

		public SplitFileInserterSegment getParent() {
			return SplitFileInserterSegment.this;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == SplitFileInserterSegment.this && ((BlockItem)o).blockNum == blockNum) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == SplitFileInserterSegment.this && ((FakeBlockItem)o).blockNum == blockNum) return true;
			}
			return false;
		}
	}

	@Override
	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		BlockItem block = (BlockItem) keyNum;
		synchronized(this) {
			if(finished) return;
		}
		// First report the error.
		if(persistent)
			container.activate(errors, 5);
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			Logger.error(this, "Collision on a CHK?!?!?");
			fail(new InsertException(InsertException.INTERNAL_ERROR, "Collision on a CHK", null), container, context);
			return;
		case LowLevelPutException.INTERNAL_ERROR:
			Logger.error(this, "Internal error: "+e, e);
			fail(new InsertException(InsertException.INTERNAL_ERROR, e.toString(), null), container, context);
			return;
		case LowLevelPutException.REJECTED_OVERLOAD:
			errors.inc(InsertException.REJECTED_OVERLOAD);
			break;
		case LowLevelPutException.ROUTE_NOT_FOUND:
			errors.inc(InsertException.ROUTE_NOT_FOUND);
			break;
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
			break;
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
			fail(new InsertException(InsertException.INTERNAL_ERROR, e.toString(), null), container, context);
			return;
		}
		if(persistent)
			container.store(errors);
		boolean isRNF = e.code == LowLevelPutException.ROUTE_NOT_FOUND ||
			e.code == LowLevelPutException.ROUTE_REALLY_NOT_FOUND;
		int blockNum = block.blockNum;
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" failed on "+this+" : "+e);
		boolean treatAsSuccess = false;
		boolean failedBlock = false;
		int completed;
		int succeeded;
		synchronized(this) {
			if(blockNum >= dataBlocks.length) {
				// Check block.
				int checkNum = blockNum - dataBlocks.length;
				if(checkFinished[checkNum]) {
					if(checkFailed[checkNum])
						Logger.error(this, "Got onFailure() but block has already failed! Check block "+checkNum+" on "+this);
					else
						Logger.error(this, "Got onFailure() but block has already succeeded: Check block "+checkNum+" on "+this);
					return;
				}
				if(isRNF) {
					checkConsecutiveRNFs[checkNum]++;
					if(persistent) container.activate(blockInsertContext, 1);
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+checkConsecutiveRNFs[checkNum]+" / "+blockInsertContext.consecutiveRNFsCountAsSuccess);
					if(checkConsecutiveRNFs[checkNum] == blockInsertContext.consecutiveRNFsCountAsSuccess) {
						// Treat as success
						treatAsSuccess = true;
					}
				} else {
					checkConsecutiveRNFs[checkNum] = 0;
				}
				if(!treatAsSuccess) {
					checkRetries[checkNum]++;
					if(checkRetries[checkNum] > maxRetries && maxRetries != -1) {
						failedBlock = true;
						// Treat as failed.
						checkFinished[checkNum] = true;
						checkFailed[checkNum] = true;
						blocksCompleted++;
						if(persistent) container.activate(blocks, 2);
						blocks.remove(Integer.valueOf(blockNum));
						if(persistent) container.store(blocks);
						if(checkBlocks[checkNum] != null) {
							if(persistent) container.activate(checkBlocks[checkNum], 1);
							checkBlocks[checkNum].free();
							if(persistent) checkBlocks[checkNum].removeFrom(container);
							checkBlocks[checkNum] = null;
							if(logMINOR) Logger.minor(this, "Failed to insert check block "+checkNum+" on "+this);
						} else {
							Logger.error(this, "Check block "+checkNum+" failed on "+this+" but bucket is already nulled out!");
						}
					}
					// Else we are still registered, but will have to be
					// re-selected: for persistent requests, the current
					// PersistentChosenRequest will not re-run the same block.
					// This is okay!
				} else {
					// Better handle it here to minimize race conditions. :|
					checkFinished[checkNum] = true;
					checkFailed[checkNum] = false; // Treating as succeeded
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(Integer.valueOf(blockNum));
					if(persistent) container.store(blocks);
					if(checkBlocks[checkNum] != null) {
						if(persistent) container.activate(checkBlocks[checkNum], 1);
						checkBlocks[checkNum].free();
						if(persistent) checkBlocks[checkNum].removeFrom(container);
						checkBlocks[checkNum] = null;
						if(logMINOR) Logger.minor(this, "Repeated RNF, treating as success for check block "+checkNum+" on "+this);
					} else {
						Logger.error(this, "Check block "+checkNum+" succeeded (sort of) on "+this+" but bucket is already nulled out!");
					}
				}
			} else {
				// Data block.
				if(dataFinished[blockNum]) {
					if(dataFailed[blockNum])
						Logger.error(this, "Got onFailure() but block has already failed! Data block "+blockNum+" on "+this);
					else
						Logger.error(this, "Got onFailure() but block has already succeeded: Data block "+blockNum+" on "+this);
					return;
				}
				if(isRNF) {
					dataConsecutiveRNFs[blockNum]++;
					if(persistent) container.activate(blockInsertContext, 1);
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+dataConsecutiveRNFs[blockNum]+" / "+blockInsertContext.consecutiveRNFsCountAsSuccess);
					if(dataConsecutiveRNFs[blockNum] == blockInsertContext.consecutiveRNFsCountAsSuccess) {
						// Treat as success
						treatAsSuccess = true;
					}
				} else {
					dataConsecutiveRNFs[blockNum] = 0;
				}
				if(!treatAsSuccess) {
					dataRetries[blockNum]++;
					if(dataRetries[blockNum] > maxRetries && maxRetries != -1) {
						failedBlock = true;
						// Treat as failed.
						dataFinished[blockNum] = true;
						dataFailed[blockNum] = true;
						blocksCompleted++;
						if(persistent) container.activate(blocks, 2);
						blocks.remove(Integer.valueOf(blockNum));
						if(persistent) container.store(blocks);
						if(encoded && dataBlocks[blockNum] != null) {
							if(persistent) container.activate(dataBlocks[blockNum], 1);
							dataBlocks[blockNum].free();
							if(persistent) dataBlocks[blockNum].removeFrom(container);
							dataBlocks[blockNum] = null;
							if(logMINOR) Logger.minor(this, "Failed to insert data block "+blockNum+" on "+this);
						} else if(dataBlocks[blockNum] == null) {
							Logger.error(this, "Data block "+blockNum+" failed on "+this+" but bucket is already nulled out!");
						}
					}
					// Else we are still registered, but will have to be
					// re-selected: for persistent requests, the current
					// PersistentChosenRequest will not re-run the same block.
					// This is okay!
				} else {
					// Better handle it here to minimize race conditions. :|
					dataFinished[blockNum] = true;
					dataFailed[blockNum] = false; // Treating as succeeded
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(Integer.valueOf(blockNum));
					if(persistent) container.store(blocks);
					if(dataBlocks[blockNum] != null && encoded) {
						if(persistent) container.activate(dataBlocks[blockNum], 1);
						dataBlocks[blockNum].free();
						if(persistent) dataBlocks[blockNum].removeFrom(container);
						dataBlocks[blockNum] = null;
						if(logMINOR) Logger.minor(this, "Repeated RNF, treating as success for data block "+blockNum+" on "+this);
					} else {
						Logger.error(this, "Data block "+blockNum+" succeeded (sort of) on "+this+" but bucket is already nulled out!");
					}
				}
			}
			if(persistent)
				container.store(this);
			completed = blocksCompleted;
			succeeded = blocksSucceeded;
		}
		if(persistent) container.activate(putter, 1);
		if(failedBlock)
			putter.failedBlock(container, context);
		else if(treatAsSuccess)
			putter.completedBlock(false, container, context);
		if(persistent) container.deactivate(putter, 1);
		if(completed == dataBlocks.length + checkBlocks.length) {
			if(persistent) container.activate(parent, 1);
			finish(container, context, parent);
			if(persistent) container.deactivate(parent, 1);
		} else if(treatAsSuccess && succeeded == dataBlocks.length) {
			if(persistent) container.activate(parent, 1);
			parent.segmentFetchable(this, container);
			if(persistent) container.deactivate(parent, 1);
		}
	}

	@Override
	public void onSuccess(Object keyNum, ObjectContainer container, final ClientContext context) {
		BlockItem block = (BlockItem) keyNum;
		int blockNum = block.blockNum;
		int completed;
		int succeeded;
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" succeeded on "+this);
		synchronized(this) {
			if(finished) {
				return;
			}
			if(blockNum >= dataBlocks.length) {
				// Check block.
				int checkNum = blockNum - dataBlocks.length;
				if(!checkFinished[checkNum]) {
					checkFinished[checkNum] = true;
					checkFailed[checkNum] = false;
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(Integer.valueOf(blockNum));
					if(persistent) container.store(blocks);
				} else {
					if(checkFailed[checkNum])
						Logger.error(this, "Got onSuccess() but block has already failed! Check block "+checkNum+" on "+this);
					else
						Logger.error(this, "Got onSuccess() but block has already succeeded: Check block "+checkNum+" on "+this);
					return;
				}
				if(checkBlocks[checkNum] != null) {
					if(persistent) container.activate(checkBlocks[checkNum], 1);
					checkBlocks[checkNum].free();
					if(persistent) checkBlocks[checkNum].removeFrom(container);
					checkBlocks[checkNum] = null;
				} else {
					Logger.error(this, "Check block "+checkNum+" succeeded on "+this+" but bucket is already nulled out!");
				}
			} else {
				// Data block
				if(!dataFinished[blockNum]) {
					dataFinished[blockNum] = true;
					dataFailed[blockNum] = false;
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(Integer.valueOf(blockNum));
					if(persistent) container.store(blocks);
				} else {
					if(dataFailed[blockNum])
						Logger.error(this, "Got onSuccess() but block has already failed! Data block "+blockNum+" on "+this);
					else
						Logger.error(this, "Got onSuccess() but block has already succeeded: Data block "+blockNum+" on "+this);
					return;
				}
				// Data blocks may not be freed until after we have encoded the check blocks.
				if(encoded && dataBlocks[blockNum] != null) {
					if(persistent) container.activate(dataBlocks[blockNum], 1);
					dataBlocks[blockNum].free();
					if(persistent) dataBlocks[blockNum].removeFrom(container);
					dataBlocks[blockNum] = null;
				} else if(dataBlocks[blockNum] == null) {
					Logger.error(this, "Data block "+blockNum+" succeeded on "+this+" but bucket is already nulled out!");
					if(persistent) Logger.minor(this, "Activation state: "+container.ext().isActive(this));
				}
			}
			if(persistent)
				container.store(this);
			completed = blocksCompleted;
			succeeded = blocksSucceeded;
		}
		if(persistent) container.activate(putter, 1);
		putter.completedBlock(false, container, context);
		if(persistent) container.deactivate(putter, 1);
		if(completed == dataBlocks.length + checkBlocks.length) {
			if(persistent) container.activate(parent, 1);
			// This could be quite heavy. Run it off-thread.
			// Note that it's not safe to do this for persistent requests for consistency reasons.
			// But for persistent requests this is not called on the request sender anyway.
			if(!persistent) {
				context.mainExecutor.execute(new Runnable() {

					@Override
					public void run() {
						finish(null, context, parent);
					}
					
				});
			} else {
				finish(container, context, parent);
			}
			if(persistent) container.deactivate(parent, 1);
		} else if(succeeded == dataBlocks.length) {
			if(persistent) container.activate(parent, 1);
			if(!persistent) {
				context.mainExecutor.execute(new Runnable() {

					@Override
					public void run() {
						parent.segmentFetchable(SplitFileInserterSegment.this, null);
					}
					
				});
			} else {
				parent.segmentFetchable(this, container);
			}
			if(persistent) container.deactivate(parent, 1);
		}
	}

	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		return countSendableKeys(container, context);
	}

	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blocks, 1);
		}
		synchronized(this) {
			if(finished) return null;
			if(blocks.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "No blocks to remove");
				return null;
			}
			for(int i=0;i<10;i++) {
				Integer ret;
				int x;
				if(blocks.size() == 0) return null;
				x = context.random.nextInt(blocks.size());
				ret = blocks.get(x);
				int num = ret;

				// Check whether it is already running
				if(!persistent) {
					if(keys.hasTransientInsert(this, new FakeBlockItem(num)))
						continue;
				}

				try {
					return getBlockItem(container, context, num, cryptoAlgorithm);
				} catch (IOException e) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
					return null;
				}
			}
			return null;
		}
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		if(persistent) container.activate(putter, 1);
		return putter.getClient();
	}

	@Override
	public ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(putter, 1);
		return putter.getPriorityClass();
	}

	static class MySendableRequestSender implements SendableRequestSender {
		private final String compressorDescriptor;
		/** May be deactivated, not safe to just use. */
		private final SplitFileInserterSegment seg;
		MySendableRequestSender(String compressorDescriptor2, SplitFileInserterSegment seg) {
			compressorDescriptor = compressorDescriptor2;
			this.seg = seg;
		}
		@Override
		public boolean send(NodeClientCore core, RequestScheduler sched, final ClientContext context, final ChosenBlock req) {
				// Ignore keyNum, key, since we're only sending one block.
			final int num;
			final ClientCHK key;
			BlockItem block = (BlockItem) req.token;
				try {
					if(SplitFileInserterSegment.logMINOR) Logger.minor(this, "Starting request: block number "+block.blockNum);
					ClientCHKBlock b;
					try {
						b = encodeBucket(block.copyBucket, compressorDescriptor, block.cryptoAlgorithm, block.cryptoKey);
					} catch (CHKEncodeException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage()+" for "+block.copyBucket, e);
					} catch (MalformedURLException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage()+" for "+block.copyBucket, e);
					} catch (IOException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage()+" for "+block.copyBucket, e);
					} finally {
						block.copyBucket.free();
					}
					if (b==null) {
						Logger.error(this, "Asked to send empty block", new Exception("error"));
						return false;
					}
					key = b.getClientKey();
					num = block.blockNum;
					if(block.persistent) {
						req.setGeneratedKey(key);
					} else {
						seg.onEncode(num, key, null, context);
					}
					if(req.localRequestOnly)
						try {
							core.node.store(b, false, req.canWriteClientCache, true, false);
						} catch (KeyCollisionException e) {
							throw new LowLevelPutException(LowLevelPutException.COLLISION);
						}
					else
						core.realPut(b, req.canWriteClientCache, req.forkOnCacheable, Node.PREFER_INSERT_DEFAULT, Node.IGNORE_LOW_BACKOFF_DEFAULT, req.realTimeFlag);
				} catch (LowLevelPutException e) {
					req.onFailure(e, context);
					if(SplitFileInserterSegment.logMINOR) Logger.minor(this, "Request failed for "+e);
					return true;
				}
				if(SplitFileInserterSegment.logMINOR) Logger.minor(this, "Request succeeded");
				req.onInsertSuccess(context);
				return true;
			}
		
		@Override
		public boolean sendIsBlocking() {
			return true;
		}


	}

	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		SendableRequestSender result;
		boolean deactivateParent = false;
		boolean deactivateParentCtx = false;
		if(persistent) {
			deactivateParent = !container.ext().isActive(parent);
			deactivateParentCtx = !container.ext().isActive(parent.ctx);
			if (deactivateParent)
				container.activate(parent, 1);
			if (deactivateParentCtx)
				container.activate(parent.ctx, 1);
		}
		result = new MySendableRequestSender(parent.ctx.compressorDescriptor, this);
		if(persistent) {
			if (deactivateParent)
				container.deactivate(parent, 1);
			if (deactivateParentCtx)
				container.deactivate(parent.ctx, 1);
		}
		return result;
	}

	protected static ClientCHKBlock encodeBucket(Bucket copyBucket, String compressorDescriptor, byte cryptoAlgorithm, byte[] cryptoKey) throws CHKEncodeException, IOException {
		byte[] buf = BucketTools.toByteArray(copyBucket);
		assert(buf.length == CHKBlock.DATA_LENGTH); // All new splitfile inserts insert only complete blocks even at the end.
		return ClientCHKBlock.encodeSplitfileBlock(buf, cryptoKey, cryptoAlgorithm);
	}

	@Override
	public boolean isCancelled(ObjectContainer container) {
		return finished;
	}

	@Override
	public boolean isSSK() {
		return false;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		// FIXME use keys
		if(persistent) {
			container.activate(blocks, 1);
		}
		Integer[] blockNumbers;
		synchronized(this) {
			blockNumbers = blocks.toArray(new Integer[blocks.size()]);
		}
		ArrayList<PersistentChosenBlock> ret = new ArrayList<PersistentChosenBlock>();
		Arrays.sort(blockNumbers);
		int prevBlockNumber = -1;
		byte cryptoAlgorithm = getCryptoAlgorithm(container);
		for(int i=0;i<blockNumbers.length;i++) {
			int blockNumber = blockNumbers[i];
			if(blockNumber == prevBlockNumber) {
				Logger.error(this, "Duplicate block number in makeBlocks() in "+this+": two copies of "+blockNumber);
				continue;
			}
			prevBlockNumber = blockNumber;
			SendableRequestItem item;
			try {
				item = getBlockItem(container, context, blockNumber, cryptoAlgorithm);
				if(item == null) continue;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				return null;
			}
			PersistentChosenBlock block = new PersistentChosenBlock(true, request, item, null, null, sched);
			if(logMINOR) Logger.minor(this, "Created block "+block+" for block number "+blockNumber+" on "+this);
			ret.add(block);
		}
		if(persistent) {
			container.deactivate(blocks, 1);
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret.size()+" blocks");
		return ret;
	}

	@Override
	public synchronized long countSendableKeys(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blocks, 1);
		}
		int sz = blocks.size();
		if(persistent) {
			container.deactivate(blocks, 1);
		}
		return sz;
	}

	@Override
	public synchronized boolean isEmpty(ObjectContainer container) {
		if(persistent) container.activate(blocks, 2);
		boolean ret = (finished || blocks.isEmpty());
		if(persistent) container.deactivate(blocks, 1);
		return ret;
	}

	private boolean removeOnEncode;

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(encodeJob, 1);
		if(encodeJob != null) {
			if(!encodeJob.cancel(container, context)) {
				synchronized(this) {
					removeOnEncode = true;
					if(logMINOR) Logger.minor(this, "Will remove after encode finished: "+this);
					container.store(this);
					return;
				}
			}
			encodeJob = null;
		}
		// parent, putter can deal with themselves
		freeBucketsArray(container, dataBlocks);
		freeBucketsArray(container, checkBlocks);
		for(int i=0;i<dataURIs.length;i++) {
			ClientCHK chk = dataURIs[i];
			if(chk != null) {
				container.activate(chk, 5);
				chk.removeFrom(container);
			} else {
				if(logMINOR) Logger.minor(this, "dataURI "+i+" is null on "+this);
			}
		}
		for(int i=0;i<checkURIs.length;i++) {
			ClientCHK chk = checkURIs[i];
			if(chk != null) {
				container.activate(chk, 5);
				chk.removeFrom(container);
			} else {
				if(logMINOR) Logger.minor(this, "checkURI "+i+" is null on "+this);
			}
		}
		container.activate(blocks, 5);
		for(Integer i : blocks) {
			container.activate(i, 1);
			container.delete(i);
		}
		container.delete(blocks);
		if(toThrow != null) {
			container.activate(toThrow, 5);
			toThrow.removeFrom(container);
		}
		if(errors != null) {
			container.activate(errors, 1);
			errors.removeFrom(container);
		}
		container.delete(this);
	}

	@Override
	public boolean canWriteClientCache(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(blockInsertContext);
			if(deactivate)
				container.activate(blockInsertContext, 1);
		}
		boolean retval = blockInsertContext.canWriteClientCache;
		if(deactivate)
			container.deactivate(blockInsertContext, 1);
		return retval;
	}

	@Override
	public boolean localRequestOnly(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(blockInsertContext);
			if(deactivate)
				container.activate(blockInsertContext, 1);
		}
		boolean retval = blockInsertContext.localRequestOnly;
		if(deactivate)
			container.deactivate(blockInsertContext, 1);
		return retval;
	}

	@Override
	public boolean forkOnCacheable(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(blockInsertContext);
			if(deactivate)
				container.activate(blockInsertContext, 1);
		}
		boolean retval = blockInsertContext.forkOnCacheable;
		if(deactivate)
			container.deactivate(blockInsertContext, 1);
		return retval;
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(finished) {
			Logger.error(this, "Storing "+this+" when already finished!", new Exception("error"));
			return false;
		}
		if(logDEBUG) Logger.debug(this, "Storing "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
		return true;
	}

	private void freeBucketsArray(ObjectContainer container, Bucket[] buckets) {
		for(int i=0;i<buckets.length;i++) {
			if(buckets[i] == null) continue;
			if(persistent)
				container.activate(buckets[i], 1);
			buckets[i].free();
			if(persistent)
				buckets[i].removeFrom(container);
			buckets[i] = null;
		}
	}

	/**
	 * Free only those data blocks which have finished already, because they were
	 * not freed when they finished because we hadn't completed FEC encoding and
	 * thus needed to keep them until we encoded.
	 * @param container
	 */
	private void freeFinishedDataBlocks(ObjectContainer container) {
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataFinished[i] && dataBlocks[i] != null) {
				if(logMINOR) Logger.minor(this, "Freeing data block "+i+" delayed for encode");
				if(persistent) container.activate(dataBlocks[i], 1);
				dataBlocks[i].free();
				if(persistent)
					dataBlocks[i].removeFrom(container);
				dataBlocks[i] = null;
			}
		}
	}

	@Override
	public boolean isStorageBroken(ObjectContainer container) {
		if(putter == null) return true;
		if(parent == null) return true;
		if(dataRetries == null) return true;
		if(checkRetries == null) return true;
		return false;
	}

	public void checkHasDataBlocks(boolean log, ObjectContainer container) {
		int dataCount = 0;
		int dataKeys = 0;
		int dataDone = 0;
		for(int i=0;i<dataBlocks.length;i++) {
			Bucket data = dataBlocks[i];
			if(data == null) {
				if(log)
					System.err.println("Data block "+i+" is null!");
			} else {
				container.activate(data, 5);
				if(data.size() != CHKBlock.DATA_LENGTH) {
					System.err.println("Size of data block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
				} else {
					dataCount++;
				}
				if(log) System.err.println(data.toString()+" : "+data.size());
				container.deactivate(data, 5);
			}
			if(dataURIs[i] != null)
				dataKeys++;
			if(dataFinished[i])
				dataDone++;
		}
		if(dataCount == dataBlocks.length)
			System.out.println("Has all data blocks");
		else
			System.out.println("Does not have all data blocks: "+dataCount+" of "+dataBlocks.length);
		System.out.println("Data blocks have URIs: "+dataKeys+" finished: "+dataDone);
		int checkCount = 0;
		int checkKeys = 0;
		int checkDone = 0;
		for(int i=0;i<checkBlocks.length;i++) {
			Bucket data = checkBlocks[i];
			if(data == null) {
				// Not here
			} else {
				if(data.size() != CHKBlock.DATA_LENGTH) {
					System.err.println("Size of check block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
				} else {
					checkCount++;
				}
			}
			if(checkURIs[i] != null)
				checkKeys++;
			if(checkFinished[i])
				checkDone++;
		}
		System.out.println("Check count: "+checkCount+" keys: "+checkKeys+" done: "+checkDone);
		if(encodeJob == null) {
			System.err.println("NO QUEUED FEC JOB!");
			container.activate(parent, 1);
			System.err.println("Parent: "+parent);
			if(parent != null) {
				parent.dump(container);
			}
			container.deactivate(parent, 1);
		} else {
			container.activate(encodeJob, 1);
			System.err.println("Queued FEC job: "+encodeJob);
			encodeJob.dump(container);
			container.deactivate(encodeJob, 1);
		}
	}

	public boolean isStarted() {
		return started;
	}

	@Override
	public void onEncode(SendableRequestItem token, ClientKey key, ObjectContainer container, ClientContext context) {
		onEncode(((BlockItem)token).blockNum, (ClientCHK)key, container, context);
	}

	public int allocateCrossDataBlock(SplitFileInserterCrossSegment seg, Random random) {
		int size = realDataBlocks();
		if(crossDataBlocksAllocated == size) return -1;
		int x = 0;
		for(int i=0;i<10;i++) {
			x = random.nextInt(size);
			if(crossSegmentsByBlock[x] == null) {
				crossSegmentsByBlock[x] = seg;
				crossDataBlocksAllocated++;
				return x;
			}
		}
		for(int i=0;i<size;i++) {
			x++;
			if(x == size) x = 0;
			if(crossSegmentsByBlock[x] == null) {
				crossSegmentsByBlock[x] = seg;
				crossDataBlocksAllocated++;
				return x;
			}
		}
		throw new IllegalStateException("Unable to allocate cross data block even though have not used all slots up???");
	}

	public int allocateCrossCheckBlock(SplitFileInserterCrossSegment seg, Random random) {
		if(crossCheckBlocksAllocated == crossCheckBlocks) return -1;
		int x = dataBlocks.length - (1 + random.nextInt(crossCheckBlocks));
		for(int i=0;i<crossCheckBlocks;i++) {
			x++;
			if(x == dataBlocks.length) x = dataBlocks.length - crossCheckBlocks;
			if(crossSegmentsByBlock[x] == null) {
				crossSegmentsByBlock[x] = seg;
				crossCheckBlocksAllocated++;
				return x;
			}
		}
		throw new IllegalStateException("Unable to allocate cross check block even though have not used all slots up???");
	}
	
	public final int realDataBlocks() {
		return dataBlocks.length - crossCheckBlocks;
	}

	public void onEncodedCrossCheckBlock(int blockNum, Bucket data, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(dataBlocks[blockNum] != null) {
				Logger.error(this, "Cross-check block already encoded??? "+blockNum+" on "+this);
				data.free();
				return;
			}
			dataBlocks[blockNum] = data;
			++encodedCrossCheckBlocks;
			if(logMINOR && encodedCrossCheckBlocks != crossCheckBlocks)
				Logger.minor(this, "Segment "+segNo+" has "+encodedCrossCheckBlocks+" encoded of "+crossCheckBlocks+", still waiting...");
		}
		if(persistent) {
			data.storeTo(container);
			container.store(this);
		}
	}

}
