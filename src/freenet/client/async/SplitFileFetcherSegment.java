/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FECQueue;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.NodeCHK;
import freenet.keys.TooBigException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.RandomGrabArray;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SplitFileFetcherSubSegment's, which are registered on the ClientRequestScheduler.
 */
public class SplitFileFetcherSegment implements FECCallback {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	final short splitfileType;
	final ClientCHK[] dataKeys;
	final ClientCHK[] checkKeys;
	final MinimalSplitfileBlock[] dataBuckets;
	final MinimalSplitfileBlock[] checkBuckets;
	final long[] dataCooldownTimes;
	final long[] checkCooldownTimes;
	final int[] dataRetries;
	final int[] checkRetries;
	final Vector<SplitFileFetcherSubSegment> subSegments;
	final int minFetched;
	final SplitFileFetcher parentFetcher;
	final ClientRequester parent;
	final ArchiveContext archiveContext;
	final long maxBlockLength;
	/** Has the segment finished processing? Irreversible. */
	private volatile boolean finished;
	private boolean startedDecode;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Fetch context for block fetches */
	final FetchContext blockFetchContext;
	/** Recursion level */
	final int recursionLevel;
	private FetchException failureException;
	/**
	 * If true, the last data block has bad padding and cannot be involved in FEC decoding.
	 */
	private final boolean ignoreLastDataBlock;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	/**
	 * The number of blocks fetched. If ignoreLastDataBlock is set, fetchedBlocks does not
	 * include the last data block.
	 */
	private int fetchedBlocks;
	/**
	 * The number of data blocks (NOT check blocks) fetched. On a small splitfile, sometimes
	 * we will manage to fetch all the data blocks. If so, we can complete the segment, even
	 * if we can't do a FEC decode because of ignoreLastDataBlock.
	 */
	private int fetchedDataBlocks;
	final FailureCodeTracker errors;
	private boolean finishing;
	private boolean scheduled;
	private final boolean persistent;
	final int segNum;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;

	// After the fetcher has finished with the segment, *and* we have encoded and started healing inserts,
	// we can removeFrom(). Note that encodes are queued to the database.
	private boolean fetcherFinished = false;
	private boolean encoderFinished = false;
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	private transient FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, ClientCHK[] splitfileDataKeys, ClientCHK[] splitfileCheckKeys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext blockFetchContext, long maxTempLength, int recursionLevel, ClientRequester requester, int segNum, boolean ignoreLastDataBlock) throws MetadataParseException, FetchException {
		this.segNum = segNum;
		this.hashCode = super.hashCode();
		this.persistent = fetcher.persistent;
		this.parentFetcher = fetcher;
		this.ignoreLastDataBlock = ignoreLastDataBlock;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		this.parent = requester;
		dataKeys = splitfileDataKeys;
		checkKeys = splitfileCheckKeys;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataKeys.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataKeys.length;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		finished = false;
		decodedData = null;
		dataBuckets = new MinimalSplitfileBlock[dataKeys.length];
		checkBuckets = new MinimalSplitfileBlock[checkKeys.length];
		for(int i=0;i<dataBuckets.length;i++) {
			dataBuckets[i] = new MinimalSplitfileBlock(i);
		}
		for(int i=0;i<checkBuckets.length;i++)
			checkBuckets[i] = new MinimalSplitfileBlock(i+dataBuckets.length);
		dataRetries = new int[dataKeys.length];
		checkRetries = new int[checkKeys.length];
		dataCooldownTimes = new long[dataKeys.length];
		checkCooldownTimes = new long[checkKeys.length];
		subSegments = new Vector<SplitFileFetcherSubSegment>();
		maxBlockLength = maxTempLength;
		this.blockFetchContext = blockFetchContext;
		this.recursionLevel = 0;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parentFetcher+" : "+dataRetries.length+" data blocks "+checkRetries.length+" check blocks");
		for(int i=0;i<dataKeys.length;i++)
			if(dataKeys[i] == null) throw new NullPointerException("Null: data block "+i);
		for(int i=0;i<checkKeys.length;i++)
			if(checkKeys[i] == null) throw new NullPointerException("Null: check block "+i);
	}

	public synchronized boolean isFinished(ObjectContainer container) {
		if(finished) return true;
		// Deactivating parent is a *bad* side-effect, so avoid it.
		boolean deactivateParent = false;
		if(persistent) {
			deactivateParent = !container.ext().isActive(parent);
			if(deactivateParent) container.activate(parent, 1);
		}
		boolean ret = parent.isCancelled();
		if(deactivateParent)
			container.deactivate(parent, 1);
		return ret;
	}
	
	public synchronized boolean succeeded() {
		return finished;
	}

	public synchronized boolean isFinishing(ObjectContainer container) {
		return isFinished(container) || finishing;
	}
	
	/** Throw a FetchException, if we have one. Else do nothing. 
	 * @param container */
	public synchronized void throwError(ObjectContainer container) throws FetchException {
		if(failureException != null) {
			if(persistent) container.activate(failureException, 5);
			if(persistent)
				throw failureException.clone(); // We will remove, caller is responsible for clone
			else
				throw failureException;
		}
	}
	
	/** Decoded length? 
	 * @param container */
	public long decodedLength(ObjectContainer container) {
		if(persistent)
			container.activate(decodedData, 1);
		return decodedData.size();
	}

	/** Write the decoded segment's data to an OutputStream */
	public long writeDecodedDataTo(OutputStream os, long truncateLength) throws IOException {
		long len = decodedData.size();
		if((truncateLength >= 0) && (truncateLength < len))
			len = truncateLength;
		BucketTools.copyTo(decodedData, os, Math.min(truncateLength, decodedData.size()));
		return len;
	}

	/** How many blocks have failed due to running out of retries? */
	public synchronized int failedBlocks() {
		return failedBlocks;
	}
	
	/** How many blocks have been successfully fetched? */
	public synchronized int fetchedBlocks() {
		return fetchedBlocks;
	}

	/** How many blocks failed permanently due to fatal errors? */
	public synchronized int fatallyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	private synchronized short onSuccessInner(Bucket data, int blockNo, ClientKeyBlock block, ObjectContainer container, ClientContext context, SplitFileFetcherSubSegment sub) {
		boolean dontNotify;
		boolean allFailed = false;
		boolean decodeNow = false;
		boolean wasDataBlock = false;
		if(finished) {
			// Happens sometimes, don't complain about it...
			// What this means is simply that there were a bunch of requests
			// running, one of them completed, the whole segment went into
			// decode, and now the extra requests are surplus to requirements.
			// It's a slight overhead, but the alternative is worse.
			if(logMINOR)
				Logger.minor(this, "onSuccess() when already finished for "+this);
			data.free();
			return -1;
		}
		if(startedDecode) {
			// Much the same.
			if(logMINOR)
				Logger.minor(this, "onSuccess() when started decode for "+this);
			data.free();
			return -1;
		}
		if(blockNo < dataKeys.length) {
			wasDataBlock = true;
			if(dataKeys[blockNo] == null) {
				if(!startedDecode) {
					// This can happen.
					// We queue a persistent download, we queue a transient.
					// The transient goes through DatastoreChecker first,
					// and feeds the block to us. We don't finish, because
					// we need more blocks. Then the persistent goes through 
					// the DatastoreChecker, and calls us again with the same
					// block.
					if(logMINOR)
						Logger.minor(this, "Block already finished: "+blockNo);
				}
				data.free();
				return -1;
			}
			dataRetries[blockNo] = 0; // Prevent healing of successfully fetched block.
			if(persistent) {
				container.activate(dataKeys[blockNo], 5);
				dataKeys[blockNo].removeFrom(container);
			}
			dataKeys[blockNo] = null;
			if(persistent)
				container.activate(dataBuckets[blockNo], 1);
			dataBuckets[blockNo].setData(data);
			if(persistent) {
				data.storeTo(container);
				container.store(dataBuckets[blockNo]);
				container.store(this); // We could return -1, so we need to store(this) here
			}
		} else if(blockNo < checkKeys.length + dataKeys.length) {
			int checkNo = blockNo - dataKeys.length;
			if(checkKeys[checkNo] == null) {
				if(!startedDecode) {
					if(logMINOR)
						Logger.minor(this, "Check block already finished: "+checkNo);
				}
				data.free();
				return -1;
			}
			checkRetries[checkNo] = 0; // Prevent healing of successfully fetched block.
			if(persistent) {
				container.activate(checkKeys[checkNo], 5);
				checkKeys[checkNo].removeFrom(container);
			}
			checkKeys[checkNo] = null;
			if(persistent)
				container.activate(checkBuckets[checkNo], 1);
			checkBuckets[checkNo].setData(data);
			if(persistent) {
				data.storeTo(container);
				container.store(checkBuckets[checkNo]);
				container.store(this); // We could return -1, so we need to store(this) here
			}
		} else
			Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
		if(startedDecode) {
			return -1;
		} else {
			boolean tooSmall = data.size() < CHKBlock.DATA_LENGTH;
			// Don't count the last data block, since we can't use it in FEC decoding.
			if(tooSmall && ((!ignoreLastDataBlock) || (blockNo != dataKeys.length - 1))) {
				fail(new FetchException(FetchException.INVALID_METADATA, "Block too small in splitfile: block "+blockNo+" of "+dataKeys.length+" data keys, "+checkKeys.length+" check keys"), container, context, true);
				return -1;
			}
			if(!(ignoreLastDataBlock && blockNo == dataKeys.length - 1 && tooSmall))
				fetchedBlocks++;
			else
				// This block is not going to be fetched, and because of the insertion format. 
				// Thus it is a fatal failure. We need to track it, because it is quite possible
				// to fetch the last block, not complete because it's the last block, and hang.
				fatallyFailedBlocks++;
			// However, if we manage to get EVERY data block (common on a small splitfile),
			// we don't need to FEC decode.
			if(wasDataBlock)
				fetchedDataBlocks++;
			if(logMINOR) Logger.minor(this, "Fetched "+fetchedBlocks+" blocks in onSuccess("+blockNo+")");
			boolean haveDataBlocks = fetchedDataBlocks == dataKeys.length;
			decodeNow = (!startedDecode) && (fetchedBlocks >= minFetched || haveDataBlocks);
			if(decodeNow) {
				startedDecode = true;
				finishing = true;
			} else {
				// Avoid hanging when we have n-1 check blocks, we succeed on the last data block,
				// we don't have the other data blocks, and we have nothing else fetching.
				allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
			}
		}
		dontNotify = !scheduled;
		short res = 0;
		if(dontNotify) res |= ON_SUCCESS_DONT_NOTIFY;
		if(allFailed) res |= ON_SUCCESS_ALL_FAILED;
		if(decodeNow) res |= ON_SUCCESS_DECODE_NOW;
		return res;
	}
	
	private static final short ON_SUCCESS_DONT_NOTIFY = 1;
	private static final short ON_SUCCESS_ALL_FAILED = 2;
	private static final short ON_SUCCESS_DECODE_NOW = 4;
	
	public void onSuccess(Bucket data, int blockNo, ClientKeyBlock block, ObjectContainer container, ClientContext context, SplitFileFetcherSubSegment sub) {
		if(persistent)
			container.activate(this, 1);
		if(data == null) throw new NullPointerException();
		if(logMINOR) Logger.minor(this, "Fetched block "+blockNo+" in "+this+" data="+dataBuckets.length+" check="+checkBuckets.length);
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
		// No need to unregister key, because it will be cleared in tripPendingKey().
		short result = onSuccessInner(data, blockNo, block, container, context, sub);
		if(result == (short)-1) return;
		finishOnSuccess(result, container, context);
	}

	private void finishOnSuccess(short result, ObjectContainer container, ClientContext context) {
		boolean dontNotify = (result & ON_SUCCESS_DONT_NOTIFY) == ON_SUCCESS_DONT_NOTIFY;
		boolean allFailed = (result & ON_SUCCESS_ALL_FAILED) == ON_SUCCESS_ALL_FAILED;
		boolean decodeNow = (result & ON_SUCCESS_DECODE_NOW) == ON_SUCCESS_DECODE_NOW;
		if(logMINOR) Logger.minor(this, "finishOnSuccess: result = "+result+" dontNotify="+dontNotify+" allFailed="+allFailed+" decodeNow="+decodeNow);
		if(persistent) {
			container.store(this);
			container.activate(parent, 1);
		}
		parent.completedBlock(dontNotify, container, context);
		if(decodeNow) {
			if(persistent)
				container.activate(parentFetcher, 1);
			parentFetcher.removeMyPendingKeys(this, container, context);
			if(persistent)
				container.deactivate(parentFetcher, 1);
			removeSubSegments(container, context, false);
			decode(container, context);
		} else if(allFailed) {
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors), container, context, true);
		}
		if(persistent) {
			container.deactivate(parent, 1);
		}
	}

	public void decode(ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		if(persistent)
			container.store(this);
		
		// Activate buckets
		if(persistent) {
			for(int i=0;i<dataBuckets.length;i++)
				container.activate(dataBuckets[i], 1);
		}
		if(persistent) {
			for(int i=0;i<checkBuckets.length;i++)
				container.activate(checkBuckets[i], 1);
		}
		int data = 0;
		for(int i=0;i<dataBuckets.length;i++) {
			if(dataBuckets[i].getData() != null) {
				data++;
			}
		}
		if(data == dataBuckets.length) {
			if(logMINOR)
				Logger.minor(this, "Already decoded");
			if(persistent) {
				for(int i=0;i<dataBuckets.length;i++) {
					container.activate(dataBuckets[i].getData(), 1);
				}
			}
			onDecodedSegment(container, context, null, null, null, dataBuckets, checkBuckets);
			return;
		}
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			FECQueue queue = context.fecQueue;
			// Double-check...
			int count = 0;
			for(int i=0;i<dataBuckets.length;i++) {
				if(dataBuckets[i].getData() != null)
					count++;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				if(checkBuckets[i].getData() != null)
					count++;
			}
			if(count < dataBuckets.length) {
				Logger.error(this, "Attempting to decode but only "+count+" of "+dataBuckets.length+" blocks available!", new Exception("error"));
			}
			if(persistent)
				container.activate(parent, 1);
			Bucket lastBlock = dataBuckets[dataBuckets.length-1].data;
			if(lastBlock != null) {
				if(persistent)
					container.activate(lastBlock, 1);
				if(ignoreLastDataBlock && lastBlock.size() < CHKBlock.DATA_LENGTH) {
					lastBlock.free();
					if(persistent)
						lastBlock.removeFrom(container);
					dataBuckets[dataBuckets.length-1].data = null;
					if(persistent)
						container.store(dataBuckets[dataBuckets.length-1]);
					// It will be decoded by the FEC job.
				} else if(lastBlock.size() != CHKBlock.DATA_LENGTH) {
					// All new inserts will have the last block padded. If it was an old insert, ignoreLastDataBlock
					// would be set. Another way we can get here is if the last data block of a segment other than
					// the last data block is too short.
					fail(new FetchException(FetchException.INVALID_METADATA, "Last data block is not the standard size"), container, context, true);
				}
			}
			if(codec == null)
				codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length);
			FECJob job = new FECJob(codec, queue, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), this, true, parent.getPriorityClass(), persistent);
			codec.addToQueue(job, 
					queue, container);
			if(logMINOR)
				Logger.minor(this, "Queued FEC job: "+job);
			if(persistent)
				container.deactivate(parent, 1);
			// Now have all the data blocks (not necessarily all the check blocks)
		} else {
			Logger.error(this, "SPLITFILE_NONREDUNDANT !!");
			onDecodedSegment(container, context, null, null, null, null, null);
		}
	}
	
	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(parentFetcher, 1);
			container.activate(parent, 1);
			container.activate(context, 1);
		}
		if(codec == null)
			codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length);
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
		try {
			if(persistent) {
				for(int i=0;i<dataBuckets.length;i++) {
					// The FECCodec won't set them.
					// But they should be active.
					if(dataBlockStatus[i] != dataBuckets[i]) {
						long theirID = container.ext().getID(dataBlockStatus[i]);
						long ourID = container.ext().getID(dataBuckets[i]);
						if(theirID == ourID) {
							Logger.error(this, "DB4O BUG DETECTED IN DECODED SEGMENT!: our block: "+dataBuckets[i]+" block from decode "+dataBlockStatus[i]+" both have ID "+ourID+" = "+theirID);
							dataBuckets[i] = (MinimalSplitfileBlock) dataBlockStatus[i];
						}
					}
					if(logMINOR)
						Logger.minor(this, "Data block "+i+" is "+dataBuckets[i]);
					if(!container.ext().isStored(dataBuckets[i]))
						Logger.error(this, "Data block "+i+" is not stored!");
					else if(!container.ext().isActive(dataBuckets[i]))
						Logger.error(this, "Data block "+i+" is inactive! : "+dataBuckets[i]);
					if(dataBuckets[i] == null)
						Logger.error(this, "Data block "+i+" is null!");
					else if(dataBuckets[i].data == null)
						Logger.error(this, "Data block "+i+" has null data!");
					else
						dataBuckets[i].data.storeTo(container);
					container.store(dataBuckets[i]);
				}
			}
			if(isCollectingBinaryBlob()) {
				for(int i=0;i<dataBuckets.length;i++) {
					Bucket data = dataBlockStatus[i].getData();
					if(data == null) 
						throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null");
					try {
						maybeAddToBinaryBlob(data, i, false, container, context);
					} catch (FetchException e) {
						fail(e, container, context, false);
						return;
					}
				}
			}
			decodedData = context.getBucketFactory(persistent).makeBucket(maxBlockLength * dataBuckets.length);
			if(logMINOR) Logger.minor(this, "Copying data from "+dataBuckets.length+" data blocks");
			OutputStream os = decodedData.getOutputStream();
			long osSize = 0;
			for(int i=0;i<dataBuckets.length;i++) {
				if(logMINOR) Logger.minor(this, "Copying data from block "+i);
				SplitfileBlock status = dataBuckets[i];
				if(status == null) throw new NullPointerException();
				Bucket data = status.getData();
				if(data == null) 
					throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null");
				if(persistent) container.activate(data, 1);
				long copied = BucketTools.copyTo(data, os, Long.MAX_VALUE);
				osSize += copied;
				if(i != dataBuckets.length-1 && copied != 32768)
					Logger.error(this, "Copied only "+copied+" bytes from "+data+" (bucket "+i+")");
				if(logMINOR) Logger.minor(this, "Copied "+copied+" bytes from bucket "+i);
			}
			if(logMINOR) Logger.minor(this, "Copied data ("+osSize+")");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			if(persistent) container.store(this);
			if(persistent) {
				boolean fin;
				synchronized(this) {
					fin = fetcherFinished;
				}
				if(fin) {
					encoderFinished(container, context);
					return;
				}
			}
			if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT || !isCollectingBinaryBlob())
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			// Leave active before queueing
		} catch (IOException e) {
			Logger.normal(this, "Caught bucket error?: "+e, e);
			boolean fin;
			synchronized(this) {
				finished = true;
				failureException = new FetchException(FetchException.BUCKET_ERROR);
				fin = fetcherFinished;
			}
			if(persistent && fin) {
				encoderFinished(container, context);
				return;
			}
			if(persistent) container.store(this);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			if(persistent)
				encoderFinished(container, context);
			return;
		}

		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			if(persistent) {
				container.deactivate(parentFetcher, 1);
				container.deactivate(parent, 1);
				container.deactivate(context, 1);
			}
			if(persistent)
				encoderFinished(container, context);
			return;
		}
		
		// Now heal

		/** Splitfile healing:
		 * Any block which we have tried and failed to download should be 
		 * reconstructed and reinserted.
		 */

		// FIXME don't heal if ignoreLastBlock.
		Bucket lastBlock = dataBuckets[dataBuckets.length-1].data;
		if(lastBlock != null) {
			if(persistent)
				container.activate(lastBlock, 1);
			if(lastBlock.size() != CHKBlock.DATA_LENGTH) {
				try {
					dataBuckets[dataBuckets.length-1].data =
						BucketTools.pad(lastBlock, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), (int) lastBlock.size());
					lastBlock.free();
					if(persistent) {
						lastBlock.removeFrom(container);
						dataBuckets[dataBuckets.length-1].storeTo(container);
					}
				} catch (IOException e) {
					fail(new FetchException(FetchException.BUCKET_ERROR, e), container, context, true);
				}
			}
		}
		
		// Encode any check blocks we don't have
		try {
		codec.addToQueue(new FECJob(codec, context.fecQueue, dataBuckets, checkBuckets, 32768, context.getBucketFactory(persistent), this, false, parent.getPriorityClass(), persistent),
				context.fecQueue, container);
		if(persistent) {
			container.deactivate(parentFetcher, 1);
			container.deactivate(parent, 1);
			container.deactivate(context, 1);
		}
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			if(persistent)
				encoderFinished(container, context);
		}
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		try {
		if(persistent) {
			container.activate(parent, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Encoded "+this);
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
		synchronized(this) {
			// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
			for(int i=0;i<dataBuckets.length;i++) {
				boolean heal = false;
				if(dataBuckets[i] == null) {
					Logger.error(this, "Data bucket "+i+" is null in onEncodedSegment on "+this);
					continue;
				}
				if(dataBuckets[i] != dataBlockStatus[i]) {
					Logger.error(this, "Data block "+i+" : ours is "+dataBuckets[i]+" codec's is "+dataBlockStatus[i]);
					if(persistent) {
						if(container.ext().getID(dataBuckets[i]) == container.ext().getID(dataBlockStatus[i]))
							Logger.error(this, "DB4O BUG DETECTED: SAME UID FOR TWO OBJECTS: "+dataBuckets[i]+"="+container.ext().getID(dataBuckets[i])+" and "+dataBlockStatus[i]+"="+container.ext().getID(dataBlockStatus[i])+" ... attempting workaround ...");
						Logger.error(this, "Ours is "+(container.ext().isStored(dataBuckets[i])?"stored ":"")+(container.ext().isActive(dataBuckets[i])?"active ":"")+" UUID "+container.ext().getID(dataBuckets[i]));
						Logger.error(this, "Theirs is "+(container.ext().isStored(dataBlockStatus[i])?"stored ":"")+(container.ext().isActive(dataBlockStatus[i])?"active ":"")+" UUID "+container.ext().getID(dataBlockStatus[i]));
					}
					dataBuckets[i] = (MinimalSplitfileBlock) dataBlockStatus[i];
				}
				Bucket data = dataBuckets[i].getData();
				if(data == null) {
					Logger.error(this, "Data bucket "+i+" has null contents in onEncodedSegment on "+this+" for block "+dataBuckets[i]);
					if(!container.ext().isStored(dataBuckets[i]))
						Logger.error(this, "Splitfile block appears not to be stored");
					else if(!container.ext().isActive(dataBuckets[i]))
						Logger.error(this, "Splitfile block appears not to be active");
					continue;
				}
				
				if(dataRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data, container, context);
					dataBuckets[i].data = null; // So that it doesn't remove the data
				} else {
					dataBuckets[i].data.free();
				}
				if(persistent)
					dataBuckets[i].removeFrom(container);
				dataBuckets[i] = null;
				if(persistent && dataKeys[i] != null)
					dataKeys[i].removeFrom(container);
				dataKeys[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				boolean heal = false;
				// Check buckets will already be active because the FEC codec
				// has been using them.
				if(checkBuckets[i] == null) {
					Logger.error(this, "Check bucket "+i+" is null in onEncodedSegment on "+this);
					continue;
				}
				if(checkBuckets[i] != checkBlockStatus[i]) {
					Logger.error(this, "Check block "+i+" : ours is "+checkBuckets[i]+" codec's is "+checkBlockStatus[i]);
					if(persistent) {
						if(container.ext().getID(checkBuckets[i]) == container.ext().getID(checkBlockStatus[i]))
							Logger.error(this, "DB4O BUG DETECTED: SAME UID FOR TWO OBJECTS: "+checkBuckets[i]+"="+container.ext().getID(checkBuckets[i])+" and "+checkBlockStatus[i]+"="+container.ext().getID(checkBlockStatus[i])+" ... attempting workaround ...");
						Logger.error(this, "Ours is "+(container.ext().isStored(checkBuckets[i])?"stored ":"")+(container.ext().isActive(checkBuckets[i])?"active ":"")+" UUID "+container.ext().getID(checkBuckets[i]));
						Logger.error(this, "Theirs is "+(container.ext().isStored(checkBlockStatus[i])?"stored ":"")+(container.ext().isActive(checkBlockStatus[i])?"active ":"")+" UUID "+container.ext().getID(checkBlockStatus[i]));
					}
					checkBuckets[i] = (MinimalSplitfileBlock) checkBlockStatus[i];
				}
				Bucket data = checkBuckets[i].getData();
				if(data == null) {
					Logger.error(this, "Check bucket "+i+" has null contents in onEncodedSegment on "+this+" for block "+checkBuckets[i]);
					if(!container.ext().isStored(dataBuckets[i]))
						Logger.error(this, "Splitfile block appears not to be stored");
					else if(!container.ext().isActive(dataBuckets[i]))
						Logger.error(this, "Splitfile block appears not to be active");
					continue;
				}
				try {
					maybeAddToBinaryBlob(data, i, true, container, context);
				} catch (FetchException e) {
					fail(e, container, context, false);
					return;
				}
				if(checkRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data, container, context);
					checkBuckets[i].data = null;
				} else {
					data.free();
				}
				if(persistent)
					checkBuckets[i].removeFrom(container);
				checkBuckets[i] = null;
				if(persistent && checkKeys[i] != null)
					checkKeys[i].removeFrom(container);
				checkKeys[i] = null;
			}
			if(persistent && !fetcherFinished) {
				container.store(this);
			}
		}
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(isCollectingBinaryBlob()) {
			if(persistent)
				container.activate(parentFetcher, 1);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			if(persistent)
				container.deactivate(parentFetcher, 1);
		}
		} finally {
			if(persistent)
				encoderFinished(container, context);
		}
	}

	boolean isCollectingBinaryBlob() {
		if(parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private void maybeAddToBinaryBlob(Bucket data, int i, boolean check, ObjectContainer container, ClientContext context) throws FetchException {
		if(parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parent);
			if(getter.collectingBinaryBlob()) {
				try {
					// Note: dontCompress is true. if false we need to know the codec it was compresssed to get a proper blob
					ClientCHKBlock block =
						ClientCHKBlock.encode(data, false, true, (short)-1, data.size(), COMPRESSOR_TYPE.DEFAULT_COMPRESSORDESCRIPTOR);
					getter.addKeyToBinaryBlob(block, container, context);
				} catch (CHKEncodeException e) {
					Logger.error(this, "Failed to encode (collecting binary blob) "+(check?"check":"data")+" block "+i+": "+e, e);
					throw new FetchException(FetchException.INTERNAL_ERROR, "Failed to encode for binary blob: "+e);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, "Failed to encode for binary blob: "+e);
				}
			}
		}
	}

	/**
	 * Queue the data for a healing insert. The data will be freed when it the healing insert completes,
	 * or immediately if a healing insert isn't queued. If we are persistent, copies the data.
	 * @param data
	 * @param container
	 * @param context
	 */
	private void queueHeal(Bucket data, ObjectContainer container, ClientContext context) {
		if(persistent) {
			try {
				Bucket copy = context.tempBucketFactory.makeBucket(data.size());
				BucketTools.copy(data, copy);
				data.free();
				if(persistent)
					data.removeFrom(container);
				data = copy;
			} catch (IOException e) {
				Logger.normal(this, "Failed to copy data for healing: "+e, e);
				data.free();
				if(persistent)
					data.removeFrom(container);
				return;
			}
		}
		if(logMINOR) Logger.minor(this, "Queueing healing insert for "+data+" on "+this);
		context.healingQueue.queue(data, context);
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal 
	 * @param container */
	public void onFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+blockNo+" on "+this+" : "+e, e);
		boolean allFailed;
		// Since we can't keep the key, we need to unregister for it at this point to avoid a memory leak
		synchronized(this) {
			if(isFinishing(container)) return; // this failure is now irrelevant, and cleanup will occur on the decoder thread
			if(blockNo < dataKeys.length) {
				if(dataKeys[blockNo] == null) {
					Logger.error(this, "Block already finished: "+blockNo);
					return;
				}
				if(persistent) {
					container.activate(dataKeys[blockNo], 1);
					dataKeys[blockNo].removeFrom(container);
				}
				dataKeys[blockNo] = null;
			} else if(blockNo < checkKeys.length + dataKeys.length) {
				if(checkKeys[blockNo-dataKeys.length] == null) {
					Logger.error(this, "Check block already finished: "+blockNo);
					return;
				}
				if(persistent) {
					container.activate(checkKeys[blockNo-dataKeys.length], 1);
					checkKeys[blockNo-dataKeys.length].removeFrom(container);
				}
				checkKeys[blockNo-dataKeys.length] = null;
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			// :(
			boolean deactivateParent = false; // can get called from wierd places, don't deactivate parent if not necessary
			if(persistent) {
				deactivateParent = !container.ext().isActive(parent);
				if(deactivateParent) container.activate(parent, 1);
			}
			if(e.isFatal()) {
				fatallyFailedBlocks++;
				parent.fatallyFailedBlock(container, context);
			} else {
				failedBlocks++;
				parent.failedBlock(container, context);
			}
			if(deactivateParent)
				container.deactivate(parent, 1);
			// Once it is no longer possible to have a successful fetch, fail...
			allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
		}
		if(persistent)
			container.store(this);
		if(allFailed)
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors), container, context, false);
		else if(seg != null) {
			if(seg.possiblyRemoveFromParent(container, context))
				seg.kill(container, context, true, true);
		}
	}
	/** A request has failed non-fatally, so the block may be retried 
	 * @param container */
	public void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		onNonFatalFailure(e, blockNo, seg, container, context, true);
	}
	
	private void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context, boolean callStore) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false);
		seg.removeBlockNum(blockNo, container, false);
		SplitFileFetcherSubSegment sub = onNonFatalFailure(e, blockNo, seg, container, context, sched, maxTries, callStore);
		if(sub != null) {
			sub.reschedule(container, context);
			if(persistent && sub != seg) container.deactivate(sub, 1);
		}
	}
	
	public void onNonFatalFailure(FetchException[] failures, int[] blockNos, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false);
		HashSet<SplitFileFetcherSubSegment> toSchedule = null;
		seg.removeBlockNums(blockNos, container);
		for(int i=0;i<failures.length;i++) {
			SplitFileFetcherSubSegment sub = 
				onNonFatalFailure(failures[i], blockNos[i], seg, container, context, sched, maxTries, false);
			if(sub != null) {
				if(toSchedule == null)
					toSchedule = new HashSet<SplitFileFetcherSubSegment>();
				toSchedule.add(sub);
			}
		}
		if(persistent) container.store(this); // We don't call container.store(this) in each onNonFatalFailure because it takes much CPU time.
		if(toSchedule != null && !toSchedule.isEmpty()) {
			for(SplitFileFetcherSubSegment sub : toSchedule) {
				if(persistent)
					if(!container.ext().isActive(sub)) {
						// Inexplicable NPEs in reschedule() called from here, lets check this...
						container.activate(sub, 1);
						Logger.error(this, "Sub-segment somehow got deactivated?!", new Exception("error"));
					}
				sub.reschedule(container, context);
				if(persistent && sub != seg) container.deactivate(sub, 1);
			}
		}
	}
	
	/**
	 * Caller must set(this) iff returns true.
	 */
	private SplitFileFetcherSubSegment onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context, RequestScheduler sched, int maxTries, boolean callStore) {
		if(logMINOR) Logger.minor(this, "Calling onNonFatalFailure for block "+blockNo+" on "+this+" from "+seg);
		int tries;
		boolean failed = false;
		boolean cooldown = false;
		ClientCHK key;
		SplitFileFetcherSubSegment sub = null;
		synchronized(this) {
			if(isFinished(container)) return null;
			if(blockNo < dataKeys.length) {
				key = dataKeys[blockNo];
				if(persistent)
					container.activate(key, 5);
				tries = ++dataRetries[blockNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					sub = getSubSegment(tries, container, false, seg);
					if(tries % RequestScheduler.COOLDOWN_RETRIES == 0) {
						long now = System.currentTimeMillis();
						if(dataCooldownTimes[blockNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" data block no "+blockNo, new Exception("error"));
						else
							dataCooldownTimes[blockNo] = sched.queueCooldown(key, sub, container);
						cooldown = true;
					}
				}
			} else {
				int checkNo = blockNo - dataKeys.length;
				key = checkKeys[checkNo];
				if(persistent)
					container.activate(key, 5);
				tries = ++checkRetries[checkNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					sub = getSubSegment(tries, container, false, seg);
					if(tries % RequestScheduler.COOLDOWN_RETRIES == 0) {
						long now = System.currentTimeMillis();
						if(checkCooldownTimes[checkNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" check block no "+blockNo, new Exception("error"));
						else
							checkCooldownTimes[checkNo] = sched.queueCooldown(key, sub, container);
						cooldown = true;
					}
				}
			}
		}
		if(tries != seg.retryCount+1) {
			Logger.error(this, "Failed on segment "+seg+" but tries for block "+blockNo+" (after increment) is "+tries);
		}
		if(failed) {
			onFatalFailure(e, blockNo, seg, container, context);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return null;
		}
		boolean mustSchedule = false;
		if(cooldown) {
			// Registered to cooldown queue
			if(logMINOR)
				Logger.minor(this, "Added to cooldown queue: "+key+" for "+this+" was on segment "+seg+" now registered to "+sub);
		} else {
			// If we are here we are going to retry
			mustSchedule = sub.add(blockNo, container, context, false);
			if(logMINOR)
				Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
		}
		if(persistent) {
			if(callStore) container.store(this);
			container.deactivate(key, 5);
		}
		if(mustSchedule) 
			return sub;
		else
			return null;
	}
	
	private SplitFileFetcherSubSegment getSubSegment(int retryCount, ObjectContainer container, boolean noCreate, SplitFileFetcherSubSegment dontDeactivate) {
		SplitFileFetcherSubSegment sub;
		if(persistent)
			container.activate(subSegments, 1);
		SplitFileFetcherSubSegment ret = null;
		int dupes = 0;
		synchronized(this) {
			for(int i=0;i<subSegments.size();i++) {
				sub = subSegments.get(i);
				if(persistent) container.activate(sub, 1);
				if(sub.retryCount == retryCount) {
					if(ret != null) {
						Logger.error(this, "Duplicate subsegment (count="+dupes+"): "+ret+" and "+sub+" for retry count "+retryCount+" on "+this);
						dupes++;
					} else
						ret = sub;
				}
				if(persistent && sub != ret && sub != dontDeactivate) container.deactivate(sub, 1);
			}
			if(ret != null) return ret;
			if(noCreate) return null;
			boolean deactivateParent = false;
			if(persistent) {
				deactivateParent = !container.ext().isActive(parent);
				if(deactivateParent) container.activate(parent, 1);
			}
			sub = new SplitFileFetcherSubSegment(this, parent, retryCount);
			if(deactivateParent)
				container.deactivate(parent, 1);
			subSegments.add(sub);
		}
		if(persistent)
			container.ext().store(subSegments, 1);
		return sub;
	}

	void fail(FetchException e, ObjectContainer container, ClientContext context, boolean dontDeactivateParent) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			this.failureException = e;
			if(startedDecode) {
				Logger.error(this, "Failing with "+e+" but already started decode", e);
				return;
			}
			for(int i=0;i<dataBuckets.length;i++) {
				MinimalSplitfileBlock b = dataBuckets[i];
				if(persistent)
					container.activate(b, 2);
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				if(persistent)
					b.removeFrom(container);
				dataBuckets[i] = null;
				if(persistent && dataKeys[i] != null)
					dataKeys[i].removeFrom(container);
				dataKeys[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				MinimalSplitfileBlock b = checkBuckets[i];
				if(persistent)
					container.activate(b, 2);
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				if(persistent)
					b.removeFrom(container);
				checkBuckets[i] = null;
				if(persistent && checkKeys[i] != null)
					checkKeys[i].removeFrom(container);
				checkKeys[i] = null;
			}
		}
		removeSubSegments(container, context, false);
		if(persistent) {
			container.store(this);
			container.activate(parentFetcher, 1);
		}
		parentFetcher.removeMyPendingKeys(this, container, context);
		parentFetcher.segmentFinished(this, container, context);
		if(persistent && !dontDeactivateParent)
			container.deactivate(parentFetcher, 1);
	}

	public SplitFileFetcherSubSegment schedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
		}
		try {
			SplitFileFetcherSubSegment seg = getSubSegment(0, container, false, null);
			if(persistent)
				container.activate(seg, 1);
			seg.addAll(dataRetries.length+checkRetries.length, container, context, false);

			if(logMINOR)
				Logger.minor(this, "scheduling "+seg+" : "+seg.blockNums);
			
			synchronized(this) {
				scheduled = true;
			}
			if(persistent)
				container.store(this);
			if(persistent)
				container.deactivate(seg, 1);
			return seg;
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" scheduling "+this, t);
			fail(new FetchException(FetchException.INTERNAL_ERROR, t), container, context, true);
			return null;
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		fail(new FetchException(FetchException.CANCELLED), container, context, true);
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore; irrelevant
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

	public synchronized ClientCHK getBlockKey(int blockNum, ObjectContainer container) {
		ClientCHK ret;
		if(blockNum < 0) return null;
		else if(blockNum < dataKeys.length)
			ret = dataKeys[blockNum];
		else if(blockNum < dataKeys.length + checkKeys.length)
			ret = checkKeys[blockNum - dataKeys.length];
		else return null;
		if(persistent)
			container.activate(ret, 5);
		return ret;
	}
	
	public NodeCHK getBlockNodeKey(int blockNum, ObjectContainer container) {
		ClientCHK key = getBlockKey(blockNum, container);
		if(key != null) return key.getNodeCHK();
		else return null;
	}

	/**
	 * Double-check whether we need to remove a subsegment, and if so, remove it.
	 * We need to do the check because there is no point removing the subsegment until all
	 * its running requests have been removed (since request data structures will refer to it
	 * anyway), and all the requests on the cooldown queue for it have been removed. In either
	 * case we get duplicated structures in memory.
	 * @return True if we removed the subsegment.
	 */
	public synchronized boolean maybeRemoveSeg(SplitFileFetcherSubSegment segment, ObjectContainer container) {
		int retryCount = segment.retryCount;
		boolean dontRemove = true;
		for(int i=0;i<dataRetries.length;i++)
			if(dataRetries[i] == retryCount) {
				dontRemove = false;
				break;
			}
		for(int i=0;i<checkRetries.length;i++)
			if(checkRetries[i] == retryCount) {
				dontRemove = false;
				break;
			}
		if(isFinishing(container)) dontRemove = false;
		if(dontRemove) return false;
		if(logMINOR)
			Logger.minor(this, "Removing sub segment: "+segment+" for retry count "+retryCount);
		if(persistent) {
			container.activate(subSegments, 1);
		}
		for(int i=0;i<subSegments.size();i++) {
			if(segment.equals(subSegments.get(i))) {
				subSegments.remove(i);
				i--;
			}
		}
		if(persistent)
			container.store(subSegments);
		return true;
	}

	private void removeSubSegments(ObjectContainer container, ClientContext context, boolean finishing) {
		if(persistent)
			container.activate(subSegments, 1);
		SplitFileFetcherSubSegment[] deadSegs;
		synchronized(this) {
			deadSegs = subSegments.toArray(new SplitFileFetcherSubSegment[subSegments.size()]);
			subSegments.clear();
		}
		if(persistent && deadSegs.length > 0)
			container.store(this);
		for(int i=0;i<deadSegs.length;i++) {
			if(persistent)
				container.activate(deadSegs[i], 1);
			deadSegs[i].kill(container, context, true, false);
			context.getChkFetchScheduler().removeFromStarterQueue(deadSegs[i], container, true);
			if(persistent)
				container.deactivate(deadSegs[i], 1);
		}
		if(persistent && !finishing) {
			container.store(this);
			container.store(subSegments);
		}
	}

	public synchronized long getCooldownWakeup(int blockNum) {
		if(blockNum < dataKeys.length)
			return dataCooldownTimes[blockNum];
		else
			return checkCooldownTimes[blockNum - dataKeys.length];
	}

	/**
	 * @return True if the key was wanted, false otherwise. 
	 */
	public boolean requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context, SplitFileFetcherSubSegment dontDeactivate) {
		if(persistent)
			container.activate(this, 1);
		Vector<SplitFileFetcherSubSegment> v = null;
		boolean notFound = true;
		synchronized(this) {
		if(isFinishing(container)) return false;
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			ClientKey k = dataKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey(false).equals(key)) {
				if(dataCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for data block "+i+" as deadline has not passed yet on "+this+" remaining time: "+(dataCooldownTimes[i]-time)+"ms");
					return false;
				}
				int tries = dataRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries, container, false, dontDeactivate);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": data block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector<SplitFileFetcherSubSegment>();
				// We always schedule. FIXME: only schedule if sub.add() returns true???
				sub.add(i, container, context, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			ClientKey k = checkKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey(false).equals(key)) {
				if(checkCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for check block "+i+" as deadline has not passed yet on "+this+" remaining time: "+(checkCooldownTimes[i]-time)+"ms");
					return false;
				}
				int tries = checkRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries, container, false, dontDeactivate);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": check block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector<SplitFileFetcherSubSegment>();
				sub.add(i+dataKeys.length, container, context, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		}
		if(notFound) {
			Logger.error(this, "requeueAfterCooldown: Key not found!: "+key+" on "+this);
		}
		if(v != null) {
			for(int i=0;i<v.size();i++) {
				SplitFileFetcherSubSegment sub = v.get(i);
				if(persistent && sub != dontDeactivate)
					container.activate(sub, 1);
				RandomGrabArray rga = sub.getParentGrabArray();
				if(rga == null) {
					sub.reschedule(container, context);
				} else {
//					if(logMINOR) {
						if(persistent)
							container.activate(rga, 1);
						if(!rga.contains(sub, container)) {
							Logger.error(this, "Sub-segment has RGA but isn't registered to it!!: "+sub+" for "+rga);
							sub.reschedule(container, context);
						}
						if(persistent)
							container.deactivate(rga, 1);
//					}
				}
				if(persistent && sub != dontDeactivate)
					container.deactivate(sub, 1);
			}
		}
		return true;
	}

	public synchronized long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			ClientKey k = dataKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey(false).equals(key)) {
				return dataCooldownTimes[i];
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			ClientKey k = checkKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(checkKeys[i].getNodeKey(false).equals(key)) {
				return checkCooldownTimes[i];
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		return -1;
	}

	public synchronized int getBlockNumber(Key key, ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++) {
			ClientCHK k = dataKeys[i];
			if(k == null) continue;
			if(persistent)
				container.activate(k, 5);
			if(k.getRoutingKey() == null)
				throw new NullPointerException("Routing key is null yet key exists for data block "+i+" of "+this+(persistent?(" stored="+container.ext().isStored(k)+" active="+container.ext().isActive(k)) : ""));
			if(k.getNodeKey(false).equals(key)) return i;
			else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			ClientCHK k = checkKeys[i];
			if(k == null) continue;
			if(persistent)
				container.activate(k, 5);
			if(k.getRoutingKey() == null)
				throw new NullPointerException("Routing key is null yet key exists for check block "+i+" of "+this);
			if(k.getNodeKey(false).equals(key)) return dataKeys.length+i;
			else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		return -1;
	}

	public synchronized Integer[] getKeyNumbersAtRetryLevel(int retryCount) {
		Vector<Integer> v = new Vector<Integer>();
		for(int i=0;i<dataRetries.length;i++) {
			if(dataKeys[i] == null) continue;
			if(dataRetries[i] == retryCount)
				v.add(Integer.valueOf(i));
		}
		for(int i=0;i<checkRetries.length;i++) {
			if(checkKeys[i] == null) continue;
			if(checkRetries[i] == retryCount)
				v.add(Integer.valueOf(i+dataKeys.length));
		}
		return v.toArray(new Integer[v.size()]);
	}

	public synchronized void resetCooldownTimes(Integer[] blockNums) {
		for(int i=0;i<blockNums.length;i++) {
			int blockNo = blockNums[i].intValue();
			if(blockNo < dataCooldownTimes.length)
				dataCooldownTimes[blockNo] = -1;
			else
				checkCooldownTimes[blockNo - dataCooldownTimes.length] = -1;
		}
	}

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
		}
		if(persistent)
			container.activate(this, 1);
		this.fail(new FetchException(FetchException.INTERNAL_ERROR, "FEC failure: "+t, t), container, context, false);
	}

	public boolean haveBlock(int blockNo, ObjectContainer container) {
		if(blockNo < dataBuckets.length) {
			boolean wasActive = false;
			if(dataBuckets[blockNo] == null) return false;
			if(persistent) {
				wasActive = container.ext().isActive(dataBuckets[blockNo]);
				if(!wasActive)
					container.activate(dataBuckets[blockNo], 1);
			}
			boolean retval = dataBuckets[blockNo].hasData();
			if(persistent && !wasActive)
				container.deactivate(dataBuckets[blockNo], 1);
			return retval;
		} else {
			boolean wasActive = false;
			blockNo -= dataBuckets.length;
			if(checkBuckets[blockNo] == null) return false;
			if(persistent) {
				wasActive = container.ext().isActive(checkBuckets[blockNo]);
				if(!wasActive)
					container.activate(checkBuckets[blockNo], 1);
			}
			boolean retval = checkBuckets[blockNo].hasData();
			if(persistent && !wasActive)
				container.deactivate(checkBuckets[blockNo], 1);
			return retval;
		}
	}

	public short getPriorityClass(ObjectContainer container) {
		if(persistent)
			container.activate(parent, 1);
		return parent.priorityClass;
	}

	public SendableGet getRequest(Key key, ObjectContainer container) {
		int blockNum = this.getBlockNumber(key, container);
		if(blockNum < 0) return null;
		int retryCount = getBlockRetryCount(blockNum);
		return getSubSegment(retryCount, container, false, null);
	}

	public boolean isCancelled(ObjectContainer container) {
		return isFinishing(container);
	}

	public Key[] listKeys(ObjectContainer container) {
		Vector<Key> v = new Vector<Key>();
		synchronized(this) {
			for(int i=0;i<dataKeys.length;i++) {
				if(dataKeys[i] != null) {
					if(persistent)
						container.activate(dataKeys[i], 5);
					v.add(dataKeys[i].getNodeKey(true));
				}
			}
			for(int i=0;i<checkKeys.length;i++) {
				if(checkKeys[i] != null) {
					if(persistent)
						container.activate(checkKeys[i], 5);
					v.add(checkKeys[i].getNodeKey(true));
				}
			}
		}
		return v.toArray(new Key[v.size()]);
	}

	/**
	 * @return True if we fetched a block.
	 * Hold the lock for the whole duration of this method. If a transient request
	 * has two copies of onGotKey() run in parallel, we want only one of them to
	 * return true, otherwise SFFKL will remove the keys from the main bloom
	 * filter twice, resulting in collateral damage to other overlapping keys,
	 * and then "NOT IN BLOOM FILTER" errors, or worse, false negatives.
	 */
	public boolean onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		ClientCHKBlock cb = null;
		int blockNum;
		Bucket data = null;
		SplitFileFetcherSubSegment seg;
		short onSuccessResult = (short) -1;
		boolean killSeg = false;
		FetchException fatal = null;
		synchronized(this) {
			if(finished || startedDecode || fetcherFinished) {
				return false;
			}
			blockNum = this.getBlockNumber(key, container);
			if(blockNum < 0) return false;
			if(logMINOR)
				Logger.minor(this, "Found key for block "+blockNum+" on "+this+" in onGotKey() for "+key);
			ClientCHK ckey = this.getBlockKey(blockNum, container);
			int retryCount = getBlockRetryCount(blockNum);
			seg = this.getSubSegment(retryCount, container, true, null);
			if(persistent)
				container.activate(seg, 1);
			if(seg != null) {
				seg.removeBlockNum(blockNum, container, false);
				killSeg = seg.possiblyRemoveFromParent(container, context);
			}
			for(int i=0;i<subSegments.size();i++) {
				SplitFileFetcherSubSegment checkSeg = subSegments.get(i);
				if(checkSeg == seg) continue;
				if(persistent)
					container.activate(checkSeg, 1);
				if(checkSeg.removeBlockNum(blockNum, container, false))
					Logger.error(this, "Block number "+blockNum+" was registered to wrong subsegment "+checkSeg+" should be "+seg);
				if(persistent)
					container.deactivate(checkSeg, 1);
			}
			try {
				cb = new ClientCHKBlock((CHKBlock)block, ckey);
			} catch (CHKVerifyException e) {
				fatal = new FetchException(FetchException.BLOCK_DECODE_ERROR, e);
			}
			if(cb != null) {
				data = extract(cb, blockNum, container, context);
				if(data == null) {
					if(logMINOR)
						Logger.minor(this, "Extract failed");
					return false;
				} else {
					// This can be done safely inside the lock.
					if(parent instanceof ClientGetter)
						((ClientGetter)parent).addKeyToBinaryBlob(cb, container, context);
					if(!cb.isMetadata()) {
						// We MUST remove the keys before we exit the synchronized block,
						// thus ensuring that the next call will return FALSE, and the keys
						// will only be removed from the Bloom filter ONCE!
						onSuccessResult = onSuccessInner(data, blockNum, cb, container, context, seg);
					}
				}
			}
		}
		if(killSeg)
			seg.kill(container, context, true, true);
		if(persistent)
			container.deactivate(seg, 1);
		if(fatal != null) {
			this.onFatalFailure(fatal, blockNum, null, container, context);
			return false;
		} else if(data == null) {
			return false; // Extract failed
		} else { // cb != null
			if(!cb.isMetadata()) {
				if(onSuccessResult != (short) -1)
					finishOnSuccess(onSuccessResult, container, context);
				return true;
			} else {
				onFatalFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), blockNum, null, container, context);
				return true;
			}
		}
	}
	
	private int getBlockRetryCount(int blockNum) {
		if(blockNum < dataRetries.length)
			return dataRetries[blockNum];
		blockNum -= dataRetries.length;
		return checkRetries[blockNum];
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, int blockNum, ObjectContainer container, ClientContext context) {
		Bucket data;
		try {
			data = block.decode(context.getBucketFactory(persistent), (int)(Math.min(this.blockFetchContext.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(logMINOR)
				Logger.minor(this, "Decode failure: "+e1, e1);
			this.onFatalFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), blockNum, null, container, context);
			return null;
		} catch (TooBigException e) {
			this.onFatalFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), blockNum, null, container, context);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			this.onFatalFailure(new FetchException(FetchException.BUCKET_ERROR, e), blockNum, null, container, context);
			return null;
		}
		if(logMINOR)
			Logger.minor(this, data == null ? "Could not decode: null" : ("Decoded "+data.size()+" bytes"));
		return data;
	}


	public boolean persistent() {
		return persistent;
	}

	public void deactivateKeys(ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++)
			container.deactivate(dataKeys[i], 1);
		for(int i=0;i<checkKeys.length;i++)
			container.deactivate(checkKeys[i], 1);
	}

	public SplitFileFetcherSubSegment getSubSegmentFor(int blockNum, ObjectContainer container) {
		return getSubSegment(getBlockRetryCount(blockNum), container, false, null);
	}

	public void freeDecodedData(ObjectContainer container) {
		if(persistent)
			container.activate(decodedData, 1);
		decodedData.free();
		if(persistent)
			decodedData.removeFrom(container);
		decodedData = null;
		if(persistent)
			container.store(this);
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "removing "+this);
		if(decodedData != null)
			freeDecodedData(container);
		removeSubSegments(container, context, true);
		container.delete(subSegments);
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] != null) dataKeys[i].removeFrom(container);
			dataKeys[i] = null;
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] != null) checkKeys[i].removeFrom(container);
			checkKeys[i] = null;
		}
		for(int i=0;i<dataBuckets.length;i++) {
			MinimalSplitfileBlock block = dataBuckets[i];
			if(block == null) continue;
			if(block.data != null) {
				Logger.error(this, "Data block "+i+" still present in removeFrom()! on "+this);
				block.data.free();
			}
			block.removeFrom(container);
		}
		for(int i=0;i<checkBuckets.length;i++) {
			MinimalSplitfileBlock block = checkBuckets[i];
			if(block == null) continue;
			if(block.data != null) {
				Logger.error(this, "Check block "+i+" still present in removeFrom()! on "+this);
				block.data.free();
			}
			block.removeFrom(container);
		}
		container.activate(errors, 1);
		errors.removeFrom(container);
		if(failureException != null) {
			container.activate(failureException, 5);
			failureException.removeFrom(container);
		}
		container.delete(this);
	}

	public void fetcherFinished(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			fetcherFinished = true;
			if(!encoderFinished) {
				if(!startedDecode) {
					encoderFinished = true;
					container.store(this);
				} else {
					container.store(this);
					if(logMINOR) Logger.minor(this, "Fetcher finished but encoder not finished on "+this);
					return;
				}
			}
		}
		removeFrom(container, context);
	}
	
	private void encoderFinished(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			encoderFinished = true;
			if(!fetcherFinished) {
				container.store(this);
				if(logMINOR) Logger.minor(this, "Encoder finished but fetcher not finished on "+this);
				return;
			}
		}
		removeFrom(container, context);
	}
}
