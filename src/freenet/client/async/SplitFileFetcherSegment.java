/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
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
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.NodeCHK;
import freenet.keys.TooBigException;
import freenet.node.KeysFetchingLocally;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.MultiReaderBucket;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SplitFileFetcherSubSegment's, which are registered on the ClientRequestScheduler.
 */
public class SplitFileFetcherSegment implements FECCallback, HasCooldownTrackerItem {

	private static volatile boolean logMINOR;
	
	private static final boolean FORCE_CHECK_FEC_KEYS = true;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	final short splitfileType;
	SplitFileSegmentKeys keys;
	boolean[] foundKeys;
	// FIXME remove eventually, needed for back compat for now
	ClientCHK[] dataKeys;
	ClientCHK[] checkKeys;
	final MinimalSplitfileBlock[] dataBuckets;
	final MinimalSplitfileBlock[] checkBuckets;
	final int[] dataRetries;
	final int[] checkRetries;
	// FIXME remove eventually, needed for back compat for now
	final Vector<SplitFileFetcherSubSegment> subSegments;
	private SplitFileFetcherSegmentGet getter;
	final int minFetched;
	final SplitFileFetcher parentFetcher;
	final ClientRequester parent;
	final ArchiveContext archiveContext;
	final long maxBlockLength;
	/** Has the segment finished processing? Irreversible. */
	private volatile boolean finished;
	private boolean startedDecode;
	/** Bucket to store the data retrieved, after it has been decoded.
	 * FIXME remove eventually, needed for back compat for now. */
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
	final boolean pre1254;
	final byte[] forceCryptoKey;
	final byte cryptoAlgorithm;
	
	private int maxRetries;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;

	// After the fetcher has finished with the segment, *and* we have encoded and started healing inserts,
	// we can removeFrom(). Note that encodes are queued to the database.
	private boolean fetcherFinished = false;
	private boolean fetcherHalfFinished = false;
	private boolean encoderFinished = false;
	
	/** The number of cross-check blocks at the end of the data blocks. These count
	 * as data blocks for most purposes but they are not included in the final data.
	 * They are the extra redundancy created for SplitFileFetcherCrossSegment's. */
	final int crossCheckBlocks;
	/** The cross-segment for each data or cross-check block. */
	private final SplitFileFetcherCrossSegment[] crossSegmentsByBlock;

	// Only used in initial allocation
	private transient int crossDataBlocksAllocated;
	private transient int crossCheckBlocksAllocated;
	
	private final boolean realTimeFlag;
	
	private int cachedCooldownTries;
	private long cachedCooldownTime;
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	private transient FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, SplitFileSegmentKeys keys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext blockFetchContext, long maxTempLength, int recursionLevel, ClientRequester requester, int segNum, boolean ignoreLastDataBlock, boolean pre1254, int crossCheckBlocks, byte cryptoAlgorithm, byte[] forceCryptoKey, int maxRetries, boolean realTimeFlag) throws MetadataParseException, FetchException {
		this.crossCheckBlocks = crossCheckBlocks;
		this.keys = keys;
		this.realTimeFlag = realTimeFlag;
		int dataBlocks = keys.getDataBlocks();
		int checkBlocks = keys.getCheckBlocks();
		foundKeys = new boolean[dataBlocks + checkBlocks];
		this.crossSegmentsByBlock = new SplitFileFetcherCrossSegment[dataBlocks];
		this.segNum = segNum;
		this.hashCode = super.hashCode();
		this.persistent = fetcher.persistent;
		this.parentFetcher = fetcher;
		this.ignoreLastDataBlock = ignoreLastDataBlock;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		this.maxRetries = maxRetries;
		this.parent = requester;
		dataKeys = null;
		checkKeys = null;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataBlocks;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataBlocks;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		finished = false;
		decodedData = null;
		dataBuckets = new MinimalSplitfileBlock[dataBlocks];
		checkBuckets = new MinimalSplitfileBlock[checkBlocks];
		for(int i=0;i<dataBuckets.length;i++) {
			dataBuckets[i] = new MinimalSplitfileBlock(i);
		}
		for(int i=0;i<checkBuckets.length;i++)
			checkBuckets[i] = new MinimalSplitfileBlock(i+dataBuckets.length);
		if(maxRetries != -1) {
			dataRetries = new int[dataBlocks];
			checkRetries = new int[checkBlocks];
		} else {
			dataRetries = null;
			checkRetries = null;
		}
		subSegments = null;
		getter = new SplitFileFetcherSegmentGet(parent, this, realTimeFlag);
		maxBlockLength = maxTempLength;
		this.blockFetchContext = blockFetchContext;
		this.recursionLevel = 0;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parentFetcher+" : "+dataBuckets.length+" data blocks "+checkBuckets.length+" check blocks");
		this.pre1254 = pre1254;
		this.cryptoAlgorithm = cryptoAlgorithm;
		this.forceCryptoKey = forceCryptoKey;
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
		if(decodedData != null) {
			if(persistent)
				container.activate(decodedData, 1);
			return decodedData.size();
		} else
			return (this.dataBuckets.length - crossCheckBlocks) * CHKBlock.DATA_LENGTH;
	}

	/** Write the decoded segment's data to an OutputStream */
	public long writeDecodedDataTo(OutputStream os, long truncateLength, ObjectContainer container) throws IOException {
		if(logMINOR)
			Logger.minor(this, "Writing decoded data on "+this);
		if(decodedData != null) {
			if(persistent) container.activate(decodedData, Integer.MAX_VALUE);
			long len = decodedData.size();
			if((truncateLength >= 0) && (truncateLength < len))
				len = truncateLength;
			BucketTools.copyTo(decodedData, os, Math.min(truncateLength, decodedData.size()));
			return len;
		} else {
			long totalCopied = 0;
			byte[] buf = new byte[CHKBlock.DATA_LENGTH];
			for(int i=0;i<dataBuckets.length-crossCheckBlocks;i++) {
				if(logMINOR) Logger.minor(this, "Copying data from block "+i);
				SplitfileBlock status = dataBuckets[i];
				if(status == null) throw new NullPointerException();
				boolean blockActive = true;
				if(persistent) {
					blockActive = container.ext().isActive(status);
					if(!blockActive)
						container.activate(status, Integer.MAX_VALUE);
				}
				Bucket data = status.getData();
				if(data == null) 
					throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null in writeDecodedData on "+this+" status = "+status+" number "+status.getNumber()+" data "+status.getData()+" persistence = "+persistent+(persistent ? (" (block active = "+container.ext().isActive(status)+" block ID = "+container.ext().getID(status)+" seg active="+container.ext().isActive(this)+")"):""));
				if(persistent) container.activate(data, 1);
				long copy;
				if(truncateLength < 0)
					copy = Long.MAX_VALUE;
				else
					copy = truncateLength - totalCopied;
				if(copy < (CHKBlock.DATA_LENGTH))
					buf = new byte[(int)copy];
				InputStream is = data.getInputStream();
				try {
				DataInputStream dis = new DataInputStream(is);
				dis.readFully(buf);
				} finally {
				is.close();
				}
				os.write(buf);
				totalCopied += buf.length;
				if(!blockActive) container.deactivate(status, 1);
			}
			if(logMINOR) Logger.minor(this, "Copied data ("+totalCopied+")");
			return totalCopied;
		}
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

	private short onSuccessInner(Bucket data, int blockNo, ObjectContainer container, ClientContext context) {
		SplitFileFetcherCrossSegment crossSegment = null;
		short res = 0;
		synchronized(this) {
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
				if(persistent) data.removeFrom(container);
				return -1;
			}
			// We accept blocks after startedDecode. So the FEC code will drop the surplus blocks.
			// This is important for cross-segment encoding:
			// Cross-segment decodes a block. This triggers a decode here, and also an encode on the cross-segment.
			// If we ignore the block here, the cross-segment encode will fail because a block is missing.
			if(blockNo < dataBuckets.length) {
				wasDataBlock = true;
				if(haveFoundKey(blockNo, container)) {
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
					if(persistent) data.removeFrom(container);
					return -1;
				}
				if(persistent)
					container.activate(dataBuckets[blockNo], 1);
				Bucket existingBlock = dataBuckets[blockNo].trySetData(data);
				if(existingBlock != null) {
					if(logMINOR)
						Logger.minor(this, "Already have data for data block "+blockNo);
					if(existingBlock != data) {
						data.free();
						if(persistent) data.removeFrom(container);
					}
					return -1;
				}
				setFoundKey(blockNo, container, context);
				if(persistent) {
					data.storeTo(container);
					container.store(dataBuckets[blockNo]);
					container.store(this); // We could return -1, so we need to store(this) here
				}
				if(crossCheckBlocks != 0)
					crossSegment = crossSegmentsByBlock[blockNo];
			} else if(blockNo < checkBuckets.length + dataBuckets.length) {
				int checkNo = blockNo - dataBuckets.length;
				if(haveFoundKey(blockNo, container)) {
					if(!startedDecode) {
						if(logMINOR)
							Logger.minor(this, "Check block already finished: "+checkNo);
					}
					data.free();
					if(persistent) data.removeFrom(container);
					return -1;
				}
				if(persistent)
					container.activate(checkBuckets[checkNo], 1);
				Bucket existingBlock = checkBuckets[checkNo].trySetData(data);
				if(existingBlock != null) {
					if(logMINOR)
						Logger.minor(this, "Already have data for check block "+checkNo);
					if(existingBlock != data) {
						data.free();
						if(persistent) data.removeFrom(container);
					}
					return -1;
				}
				setFoundKey(blockNo, container, context);
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
				if(tooSmall && ((!ignoreLastDataBlock) || (blockNo != dataBuckets.length - 1))) {
					fail(new FetchException(FetchException.INVALID_METADATA, "Block too small in splitfile: block "+blockNo+" of "+dataBuckets.length+" data keys, "+checkBuckets.length+" check keys"), container, context, true);
					return -1;
				}
				if(!(ignoreLastDataBlock && blockNo == dataBuckets.length - 1 && tooSmall))
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
				boolean haveDataBlocks = fetchedDataBlocks == dataBuckets.length;
				decodeNow = (!startedDecode) && (fetchedBlocks >= minFetched || haveDataBlocks);
				if(decodeNow) {
					decodeNow = checkAndDecodeNow(container, blockNo, tooSmall, haveDataBlocks);
				}
				if(!decodeNow) {
					// Avoid hanging when we have n-1 check blocks, we succeed on the last data block,
					// we don't have the other data blocks, and we have nothing else fetching.
					allFailed = failedBlocks + fatallyFailedBlocks > (dataBuckets.length + checkBuckets.length - minFetched);
				}
			}
			dontNotify = !scheduled;
			if(dontNotify) res |= ON_SUCCESS_DONT_NOTIFY;
			if(allFailed) res |= ON_SUCCESS_ALL_FAILED;
			if(decodeNow) res |= ON_SUCCESS_DECODE_NOW;
		}
		if(persistent) container.store(this);
		if(crossSegment != null) {
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(crossSegment);
				if(!active) container.activate(crossSegment, 1);
			}
			crossSegment.onFetched(this, blockNo, container, context);
			if(!active) container.deactivate(crossSegment, 1);
		}
		return res;
	}
	
	private void setFoundKey(int blockNo, ObjectContainer container, ClientContext context) {
		if(keys == null) migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		synchronized(this) {
			if(foundKeys[blockNo]) return;
			foundKeys[blockNo] = true;
			// Don't remove from KeyListener if we have already removed this segment.
			if(startedDecode) return;
		}
		if(persistent) container.store(this);
		SplitFileFetcherKeyListener listener = parentFetcher.getListener();
		if(listener == null)
			Logger.error(this, "NO LISTENER FOR "+this, new Exception("error"));
		else
			listener.removeKey(keys.getNodeKey(blockNo, null, false), this, container, context);
	}

	private boolean haveFoundKey(int blockNo, ObjectContainer container) {
		if(keys == null) migrateToKeys(container);
		return foundKeys[blockNo];
	}

	private synchronized void migrateToKeys(ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Migrating keys on "+this);
		keys = new SplitFileSegmentKeys(dataKeys.length, checkBuckets.length, forceCryptoKey, getCryptoAlgorithm());
		foundKeys = new boolean[dataKeys.length + checkBuckets.length];
		for(int i=0;i<dataKeys.length;i++) {
			ClientCHK key = dataKeys[i];
			if(key == null) {
				foundKeys[i] = true;
			} else {
				if(persistent) container.activate(key, 5);
				keys.setKey(i, key);
				key.removeFrom(container);
			}
		}
		for(int i=0;i<checkBuckets.length;i++) {
			ClientCHK key = checkKeys[i];
			if(key == null) {
				foundKeys[i+dataKeys.length] = true;
			} else {
				if(persistent) container.activate(key, 5);
				keys.setKey(i+dataKeys.length, key);
				key.removeFrom(container);
			}
		}
		dataKeys = null;
		checkKeys = null;
		if(persistent) {
			container.store(keys);
			container.store(this);
		}
	}

	private synchronized boolean checkAndDecodeNow(ObjectContainer container, int blockNo, boolean tooSmall, boolean haveDataBlocks) {
		if(startedDecode) return false; // Caller checks anyway but worth checking.
		boolean decodeNow = true;
		// Double-check...
		// This is somewhat defensive, but these things have happened, and caused stalls.
		// And this may help recover from persistent damage caused by previous bugs ...
		int count = 0;
		boolean lastBlockTruncated = false;
		for(int i=0;i<dataBuckets.length;i++) {
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(dataBuckets[i]);
				if(!active) container.activate(dataBuckets[i], 1);
			}
			Bucket d = dataBuckets[i].getData();
			if(d != null) {
				count++;
				if(i == dataBuckets.length-1) {
					if(ignoreLastDataBlock) {
						if(blockNo == dataBuckets.length && tooSmall) {
							// Too small.
							lastBlockTruncated = true;
						} else if(blockNo == dataBuckets.length && !tooSmall) {
							// Not too small. Cool.
							//lastBlockTruncated = false;
						} else {
							boolean blockActive = true;
							if(persistent) {
								blockActive = container.ext().isActive(d);
								if(!blockActive) container.activate(d, 1);
							}
							lastBlockTruncated = d.size() < CHKBlock.DATA_LENGTH;
							if(!blockActive) container.deactivate(d, 1);
						}
//					} else {
//						lastBlockTruncated = false;
					}
				}
			}
			if(!active) container.deactivate(dataBuckets[i], 1);
		}
		if(haveDataBlocks && count < dataBuckets.length) {
			Logger.error(this, "haveDataBlocks is wrong: count is "+count);
		} else if(haveDataBlocks && count >= dataBuckets.length) {
			startedDecode = true;
			return true;
		}
		if(lastBlockTruncated) count--;
		for(int i=0;i<checkBuckets.length;i++) {
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(checkBuckets[i]);
				if(!active) container.activate(checkBuckets[i], 1);
			}
			if(checkBuckets[i].getData() != null) {
				count++;
			}
			if(!active) container.deactivate(checkBuckets[i], 1);
		}
		if(count < dataBuckets.length) {
			Logger.error(this, "Attempting to decode but only "+count+" of "+dataBuckets.length+" blocks available!", new Exception("error"));
			decodeNow = false;
			fetchedDataBlocks = count;
		} else {
			startedDecode = true;
			finishing = true;
		}
		return decodeNow;
	}

	private static final short ON_SUCCESS_DONT_NOTIFY = 1;
	private static final short ON_SUCCESS_ALL_FAILED = 2;
	private static final short ON_SUCCESS_DECODE_NOW = 4;
	
	/**
	 * 
	 * @param data Will be freed if not used.
	 * @param blockNo
	 * @param block
	 * @param container
	 * @param context
	 * @param sub
	 * @return
	 */
	public boolean onSuccess(Bucket data, int blockNo, ClientCHKBlock block, ObjectContainer container, ClientContext context, SplitFileFetcherSubSegment sub) {
		if(persistent)
			container.activate(this, 1);
		if(data == null) throw new NullPointerException();
		// FIXME RECONSTRUCT BLOCK FOR BINARY BLOB.
		// Also can serve as an integrity check - if the key generated is wrong something is busted.
		// Probably only worth the effort if we are actually adding to a binary blob???
//		if(block == null) {
//			if(logMINOR) Logger.minor(this, "Reconstructing block from cross-segment decode");
//			block = encode(data, blockNo);
//		}
		if(logMINOR) Logger.minor(this, "Fetched block "+blockNo+" in "+this+" data="+dataBuckets.length+" check="+checkBuckets.length);
		try {
			if(!maybeAddToBinaryBlob(data, block, blockNo, container, context, block == null ? "CROSS-SEGMENT FEC" : "UNKNOWN")) {
				if((ignoreLastDataBlock && blockNo == dataBuckets.length-1) || (ignoreLastDataBlock && fetchedDataBlocks == dataBuckets.length)) {
					// Ignore
				} else if(block == null) {
					// Cross-segment, just return false.
					Logger.error(this, "CROSS-SEGMENT DECODED/ENCODED BLOCK INVALID: "+blockNo, new Exception("error"));
					onFatalFailure(new FetchException(FetchException.INTERNAL_ERROR, "Invalid block from cross-segment decode"), blockNo, container, context);
					data.free();
					if(persistent) data.removeFrom(container);
					return false;
				} else {
					Logger.error(this, "DATA BLOCK INVALID: "+blockNo, new Exception("error"));
					onFatalFailure(new FetchException(FetchException.INTERNAL_ERROR, "Invalid block"), blockNo, container, context);
					data.free();
					if(persistent) data.removeFrom(container);
					return false;
				}
			}
		} catch (FetchException e) {
			fail(e, container, context, false);
			data.free();
			if(persistent) data.removeFrom(container);
			return false;
		}
		// No need to unregister key, because it will be cleared in tripPendingKey().
		short result = onSuccessInner(data, blockNo, container, context);
		if(result == (short)-1) return false;
		finishOnSuccess(result, container, context);
		return true;
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
		// Concurrency issue (transient only): Only one thread can call decode(), because 
		// only on one thread will checkAndDecodeNow return true. However, it is possible 
		// for another thread to fail the download and remove the blocks in the meantime.
		if(persistent)
			container.activate(this, 1);
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		// Determine this early on so it is before segmentFinished or encoderFinished - maximum chance of still having parentFetcher.
		createdBeforeRestart = createdBeforeRestart(container);
		
		if(persistent)
			container.store(this);
		
		// Activate buckets
		if(persistent) {
			for(int i=0;i<dataBuckets.length;i++) {
				container.activate(dataBuckets[i], 1);
			}
			for(int i=0;i<checkBuckets.length;i++)
				container.activate(checkBuckets[i], 1);
		}
		int data = 0;
		SplitFileFetcherSegmentGet oldGetter;
		synchronized(this) {
			if(finished || encoderFinished) return;
			for(int i=0;i<dataBuckets.length;i++) {
				if(dataBuckets[i].getData() != null) {
					data++;
				} else {
					// Use flag to indicate that it the block needed to be decoded.
					dataBuckets[i].flag = true;
					if(persistent) dataBuckets[i].storeTo(container);
				}
			}
			oldGetter = getter;
			getter = null;
		}
		if(oldGetter != null) {
			if(persistent) container.activate(oldGetter, 1);
			oldGetter.unregister(container, context, getPriorityClass(container));
			if(persistent) oldGetter.removeFrom(container);
			oldGetter = null;
			if(persistent) container.store(this);
		}
		if(data == dataBuckets.length) {
			if(logMINOR)
				Logger.minor(this, "Already decoded");
			if(persistent) {
				for(int i=0;i<dataBuckets.length;i++) {
					container.activate(dataBuckets[i].getData(), 1);
				}
			}
			synchronized(this) {
				startedDecode = true;
			}
			onDecodedSegment(container, context, null, null, null, dataBuckets, checkBuckets);
			return;
		}
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			FECQueue queue = context.fecQueue;
			int count = 0;
			synchronized(this) {
				if(finished || encoderFinished) return;
				// Double-check...
				for(int i=0;i<dataBuckets.length;i++) {
					Bucket d = dataBuckets[i].getData();
					if(d != null) {
						boolean valid = false;
						if(i == dataBuckets.length-1) {
							if(ignoreLastDataBlock) {
								boolean blockActive = true;
								if(persistent) {
									blockActive = container.ext().isActive(d);
									if(!blockActive) container.activate(d, 1);
								}
								if(d.size() >= CHKBlock.DATA_LENGTH)
									valid = true;
								if(!blockActive) container.deactivate(d, 1);
							} else {
								valid = true;
							}
						} else {
							valid = true;
						}
						if(valid)
							count++;
					}
				}
				for(int i=0;i<checkBuckets.length;i++) {
					if(checkBuckets[i].getData() != null)
						count++;
				}
			}
			if(count < dataBuckets.length) {
				Logger.error(this, "Attempting to decode but only "+count+" of "+dataBuckets.length+" blocks available!", new Exception("error"));
				// startedDecode and finishing are already set, so we can't recover.
				fail(new FetchException(FetchException.INTERNAL_ERROR, "Not enough blocks to decode but decoding anyway?!"), container, context, true);
				return;
			}
			if(persistent)
				container.activate(parent, 1);
			MinimalSplitfileBlock block = dataBuckets[dataBuckets.length-1];
			if(block == null) {
				synchronized(this) {
					if(!finished || encoderFinished)
						Logger.error(this, "Last block wrapper is null yet not finished?!");
					return;
				}
			}
			Bucket lastBlock = block.getData();
			if(lastBlock != null) {
				if(persistent)
					container.activate(lastBlock, 1);
				if(ignoreLastDataBlock && lastBlock.size() < CHKBlock.DATA_LENGTH) {
					lastBlock.free();
					if(persistent)
						lastBlock.removeFrom(container);
					dataBuckets[dataBuckets.length-1].clearData();
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
			synchronized(this) {
				if(finished || encoderFinished) return;
			}
			if(codec == null)
				codec = FECCodec.getCodec(splitfileType, dataBuckets.length, checkBuckets.length);
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
			synchronized(this) {
				startedDecode = true;
			}
			onDecodedSegment(container, context, null, null, null, null, null);
		}
	}
	
	@Override
	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(context, 1);
			container.activate(blockFetchContext, 1);
		}
		synchronized(this) {
			if(encoderFinished)
				Logger.error(this, "Decoded segment after encoder finished");
		}
		if(codec == null)
			codec = FECCodec.getCodec(splitfileType, dataBuckets.length, checkBuckets.length);
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
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
				else if(dataBuckets[i].getData() == null)
					Logger.error(this, "Data block "+i+" has null data!");
				else
					dataBuckets[i].getData().storeTo(container);
				container.store(dataBuckets[i]);
			}
		}
		if(crossCheckBlocks != 0) {
			for(int i=0;i<dataBuckets.length;i++) {
				if(persistent) container.activate(dataBuckets[i], 1); // onFetched might deactivate blocks.
				if(dataBuckets[i].flag) {
					// New block. Might allow a cross-segment decode.
					boolean active = true;
					if(persistent) {
						active = container.ext().isActive(crossSegmentsByBlock[i]);
						if(!active) container.activate(crossSegmentsByBlock[i], 1);
					}
					crossSegmentsByBlock[i].onFetched(this, i, container, context);
					if(!active) container.deactivate(crossSegmentsByBlock[i], 1);
				}
			}
		}
		
		int[] dataRetries = this.dataRetries;
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		if(getMaxRetries(container) == -1) {
			// Cooldown and retry counts entirely kept in RAM.
			dataRetries = tracker.dataRetries;
		}
		
		boolean allDecodedCorrectly = true;
		boolean allFromStore = !parent.sentToNetwork;
		for(int i=0;i<dataBuckets.length;i++) {
			if(persistent && crossCheckBlocks != 0) {
				// onFetched might deactivate blocks.
				container.activate(dataBuckets[i], 1);
			}
			Bucket data = dataBlockStatus[i].getData();
			if(data == null) 
				throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null in onDecodedSegment");
			boolean heal = dataBuckets[i].flag;
			if(allFromStore) heal = false;
			try {
				if(persistent && crossCheckBlocks != 0) {
					// onFetched might deactivate blocks.
					container.activate(data, Integer.MAX_VALUE);
				}
				if(!maybeAddToBinaryBlob(data, null, i, container, context, "FEC DECODE")) {
					if(ignoreLastDataBlock && i == dataBuckets.length-1) {
						// Padding issue: It was inserted un-padded and we decoded it padded, or similar situations.
						// Does not corrupt the result, or at least, corruption is undetectable if it's only on the last block.
						Logger.normal(this, "Last block padding issue on decode on "+this);
					} else {
						// Most likely the data was corrupt as inserted.
						Logger.error(this, "Data block "+i+" FAILED TO DECODE CORRECTLY");
						fail(new FetchException(FetchException.SPLITFILE_DECODE_ERROR), container, context, false);
						return;
					}
					// Disable healing.
					heal = false;
					allDecodedCorrectly = false;
				}
			} catch (FetchException e) {
				fail(e, container, context, false);
				return;
			}
			if(heal) {
				// 100% chance if we had to retry since startup, 5% chance otherwise.
				if(dataRetries[i] == 0) {
					int odds = createdBeforeRestart ? 10 : 20;
					if(context.fastWeakRandom.nextInt(odds) != 0)
						heal = false;
				}
			}
			if(heal) {
				Bucket wrapper = queueHeal(data, container, context);
				if(wrapper != data) {
					assert(!persistent);
					dataBuckets[i].replaceData(wrapper);
				}
			}
		}
		if(allDecodedCorrectly && logMINOR) Logger.minor(this, "All decoded correctly on "+this);
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
		boolean finishNow = splitfileType == Metadata.SPLITFILE_NONREDUNDANT || !isCollectingBinaryBlob();
		if(finishNow) {
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			synchronized(this) {
				finished = true;
			}
			if(persistent) container.store(this);
			if(persistent) container.activate(parentFetcher, 1);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			if(persistent) container.deactivate(parentFetcher, 1);
		}
		// Leave active before queueing

		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			if(persistent) {
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

		Bucket lastBlock = dataBuckets[dataBuckets.length-1].getData();
		if(lastBlock != null) {
			if(persistent)
				container.activate(lastBlock, 1);
			if(ignoreLastDataBlock && lastBlock.size() != CHKBlock.DATA_LENGTH) {
				if(!finishNow) {
					synchronized(this) {
						finished = true;
					}
					if(persistent) container.store(this);
					if(persistent) container.activate(parentFetcher, 1);
					parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
					if(persistent) container.deactivate(parentFetcher, 1);
				}
				if(persistent) {
					container.deactivate(parent, 1);
					container.deactivate(context, 1);
					encoderFinished(container, context);
				}
				return;
			}
		}
		
		// Encode any check blocks we don't have
		for(int i=0;i<checkBuckets.length;i++) {
			if(checkBuckets[i].getData() == null) {
				// Use flag to indicate that it the block needed to be decoded.
				checkBuckets[i].flag = true;
				if(persistent) checkBuckets[i].storeTo(container);
			}
		}

		try {
			synchronized(this) {
				if(encoderFinished) {
					Logger.error(this, "Encoder finished in onDecodedSegment at end??? on "+this);
					return; // Calling addToQueue now will NPE.
				}
			}
			codec.addToQueue(new FECJob(codec, context.fecQueue, dataBuckets, checkBuckets, 32768, context.getBucketFactory(persistent), this, false, parent.getPriorityClass(), persistent),
					context.fecQueue, container);
			if(persistent) {
				container.deactivate(parent, 1);
				container.deactivate(context, 1);
			}
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			onFailed(t, container, context);
			if(persistent)
				encoderFinished(container, context);
		}
	}

	// Set at decode time, before calling segmentFinished or encoderFinished.
	private boolean createdBeforeRestart;

	@Override
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
			if(encoderFinished)
				Logger.error(this, "Decoded segment after encoder finished");
			// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
			for(int i=0;i<dataBuckets.length;i++) {
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
					if(persistent) {
						if(!container.ext().isStored(dataBuckets[i]))
							Logger.error(this, "Splitfile block appears not to be stored");
						else if(!container.ext().isActive(dataBuckets[i]))
							Logger.error(this, "Splitfile block appears not to be active");
					}
					continue;
				}
				
			}
			int[] checkRetries = this.checkRetries;
			MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
			if(getMaxRetries(container) == -1) {
				// Cooldown and retry counts entirely kept in RAM.
				checkRetries = tracker.checkRetries;
			}
			boolean allEncodedCorrectly = true;
			boolean allFromStore = false;
			synchronized(this) {
				if(parent == null) {
					allFromStore = false;
					if(!fetcherFinished) {
						Logger.error(this, "Parent is null on "+this+" but fetcher is not finished");
					} else {
						// We don't know.
						allFromStore = false;
					}
				} else
					allFromStore = !parent.sentToNetwork;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				boolean heal = false;
				// Check buckets will already be active because the FEC codec
				// has been using them.
				if(checkBuckets[i] == null) {
					Logger.error(this, "Check bucket "+i+" is null in onEncodedSegment on "+this);
					allEncodedCorrectly = false;
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
					if(persistent) {
						if(!container.ext().isStored(dataBuckets[i]))
							Logger.error(this, "Splitfile block appears not to be stored");
						else if(!container.ext().isActive(dataBuckets[i]))
							Logger.error(this, "Splitfile block appears not to be active");
					}
					continue;
				}
				heal = checkBuckets[i].flag;
				if(allFromStore) heal = false;
				try {
					if(!maybeAddToBinaryBlob(data, null, i+dataBuckets.length, container, context, "FEC ENCODE")) {
						heal = false;
						if(!(ignoreLastDataBlock && fetchedDataBlocks == dataBuckets.length))
							Logger.error(this, "FAILED TO ENCODE CORRECTLY so not healing check block "+i);
						allEncodedCorrectly = false;
					}
				} catch (FetchException e) {
					fail(e, container, context, false);
					return;
				}
				if(heal) {
					// 100% chance if we had to retry since startup, 5% chance otherwise.
					if(checkRetries[i] == 0) {
						int odds = createdBeforeRestart ? 10 : 20;
						if(context.fastWeakRandom.nextInt(odds) != 0)
							heal = false;
					}
				}
				if(heal) {
					Bucket wrapper = queueHeal(data, container, context);
					if(wrapper != data) {
						assert(!persistent);
						wrapper.free();
					}
					data.free();
					if(persistent) data.removeFrom(container);
					checkBuckets[i].clearData();
				} else {
					data.free();
				}
				if(persistent)
					checkBuckets[i].removeFrom(container);
				checkBuckets[i] = null;
				setFoundKey(i+dataBuckets.length, container, context);
			}
			if(logMINOR) {
				if(allEncodedCorrectly) Logger.minor(this, "All encoded correctly on "+this);
				else Logger.minor(this, "Not encoded correctly on "+this);
			}
			finished = true;
			if(persistent && !fetcherFinished) {
				container.store(this);
			}
		}
		if(logMINOR) Logger.minor(this, "Checked blocks.");
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(!(splitfileType == Metadata.SPLITFILE_NONREDUNDANT || !isCollectingBinaryBlob())) {
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

	private boolean createdBeforeRestart(ObjectContainer container) {
		SplitFileFetcher f = parentFetcher;
		if(f == null) {
			Logger.error(this, "Created before restart returning false because parent fetcher already gone");
			return false;
		}
		boolean active = true;
		if(persistent) {
			active = container.ext().isActive(parentFetcher);
			if(!active) container.activate(f, 1);
		}
		SplitFileFetcherKeyListener listener = f.getListener();
		if(!active) container.deactivate(f, 1);
		if(listener == null) {
			Logger.error(this, "Created before restart return false because no listener");
			return false;
		}
		return listener.loadedOnStartup;
	}

	boolean isCollectingBinaryBlob() {
		if(parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private boolean maybeAddToBinaryBlob(Bucket data, ClientCHKBlock block, int blockNo, ObjectContainer container, ClientContext context, String dataSource) throws FetchException {
		if(FORCE_CHECK_FEC_KEYS || parent instanceof ClientGetter) {
			if(FORCE_CHECK_FEC_KEYS || ((ClientGetter)parent).collectingBinaryBlob()) {
				try {
					// Note: dontCompress is true. if false we need to know the codec it was compresssed to get a proper blob
					byte[] buf = BucketTools.toByteArray(data);
					if(!(buf.length == CHKBlock.DATA_LENGTH)) {
						// All new splitfile inserts insert only complete blocks even at the end.
						if((!ignoreLastDataBlock) || (blockNo != dataBuckets.length-1))
							Logger.error(this, "Block is too small: "+buf.length);
						return false;
					}
					if(block == null) {
						block = 
							ClientCHKBlock.encodeSplitfileBlock(buf, forceCryptoKey, getCryptoAlgorithm());
					}
					ClientCHK key = getBlockKey(blockNo, container);
					if(key != null) {
						if(!(key.equals(block.getClientKey()))) {
							if(ignoreLastDataBlock && blockNo == dataBuckets.length-1 && dataSource.equals("FEC DECODE")) {
								if(logMINOR) Logger.minor(this, "Last block wrong key, ignored because expected due to padding issues");
							} else if(ignoreLastDataBlock && fetchedDataBlocks == dataBuckets.length && dataSource.equals("FEC ENCODE")) {
								// We padded the last block. The inserter might have used a different padding algorithm.
								if(logMINOR) Logger.minor(this, "Wrong key, might be due to padding issues");
							} else {
								Logger.error(this, "INVALID KEY FROM "+dataSource+": Block "+blockNo+" (data "+dataBuckets.length+" check "+checkBuckets.length+" ignore last block="+ignoreLastDataBlock+") : key "+block.getClientKey().getURI()+" should be "+key.getURI(), new Exception("error"));
							}
							return false;
						} else {
							if(logMINOR) Logger.minor(this, "Verified key for block "+blockNo+" from "+dataSource);
						}
					} else {
						if((dataSource.equals("FEC ENCODE") || dataSource.equals("FEC DECODE")
								|| dataSource.equals("CROSS-SEGMENT FEC")) && haveBlock(blockNo, container)) {
							// Ignore. FIXME Probably we should not delete the keys until after the encode??? Back compatibility issues maybe though...
							if(logMINOR) Logger.minor(this, "Key is null for block "+blockNo+" when checking key / adding to binary blob, key source is "+dataSource, new Exception("error"));
						} else {
							Logger.error(this, "Key is null for block "+blockNo+" when checking key / adding to binary blob, key source is "+dataSource, new Exception("error"));
						}
					}
					if(parent instanceof ClientGetter) {
						((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
					}
					return true;
				} catch (CHKEncodeException e) {
					Logger.error(this, "Failed to encode (collecting binary blob) block "+blockNo+": "+e, e);
					throw new FetchException(FetchException.INTERNAL_ERROR, "Failed to encode for binary blob: "+e);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, "Failed to encode for binary blob: "+e);
				}
			}
		}
		return true; // Assume it is encoded correctly.
	}

	private byte getCryptoAlgorithm() {
		if(cryptoAlgorithm == 0) {
			// Very old splitfile? FIXME remove this?
			return Key.ALGO_AES_PCFB_256_SHA256;
		} else
			return cryptoAlgorithm;
	}

	/**
	 * Queue the data for a healing insert. If the data is persistent, we copy it; the caller must free the 
	 * original data when it is finished with it, the healing queue will free the copied data. If the data is 
	 * not persistent, we create a MultiReaderBucket wrapper, so that the data will be freed when both the caller
	 * and the healing queue are finished with it; the caller must accept the returned bucket, and free it when it
	 * is finished with it. 
	 */
	private Bucket queueHeal(Bucket data, ObjectContainer container, ClientContext context) {
		Bucket copy;
		if(persistent) {
			try {
				copy = context.tempBucketFactory.makeBucket(data.size());
				BucketTools.copy(data, copy);
			} catch (IOException e) {
				Logger.normal(this, "Failed to copy data for healing: "+e, e);
				return data;
			}
		} else {
			MultiReaderBucket wrapper = new MultiReaderBucket(data);
			copy = wrapper.getReaderBucket();
			data = wrapper.getReaderBucket();
		}
		if(logMINOR) Logger.minor(this, "Queueing healing insert for "+data+" on "+this);
		context.healingQueue.queue(copy, forceCryptoKey, getCryptoAlgorithm(), context);
		return data;
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal 
	 * @param container */
	public void onFatalFailure(FetchException e, int blockNo, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+blockNo+" on "+this+" : "+e, e);
		boolean allFailed;
		// Since we can't keep the key, we need to unregister for it at this point to avoid a memory leak
		synchronized(this) {
			if(isFinishing(container)) return; // this failure is now irrelevant, and cleanup will occur on the decoder thread
			if(haveFoundKey(blockNo, container)) {
				Logger.error(this, "Block already finished: "+blockNo);
				return;
			}
			setFoundKey(blockNo, container, context);
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
			allFailed = failedBlocks + fatallyFailedBlocks > (dataBuckets.length + checkBuckets.length - minFetched);
		}
		if(persistent)
			container.store(this);
		if(allFailed) {
			if(persistent) container.activate(errors, Integer.MAX_VALUE);
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors), container, context, false);
		}
	}
	/** A request has failed non-fatally, so the block may be retried.
	 * Caller must update errors.
	 * @param container */
	public void onNonFatalFailure(FetchException e, int blockNo, ObjectContainer container, ClientContext context) {
		onNonFatalFailure(e, blockNo, container, context, true);
	}
	
	private void onNonFatalFailure(FetchException e, int blockNo, ObjectContainer container, ClientContext context, boolean callStore) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false, realTimeFlag);
		if(onNonFatalFailure(e, blockNo, container, context, sched, maxTries, callStore)) {
			// At least one request was rescheduled, so we have requests to send.
			// Clear our cooldown cache entry and those of our parents.
			makeGetter(container, context);
			if(getter != null) {
				rescheduleGetter(container, context);
			}
		}
	}
	
	SplitFileFetcherSegmentGet rescheduleGetter(ObjectContainer container, ClientContext context) {
		SplitFileFetcherSegmentGet getter = makeGetter(container, context);
		if(getter == null) return null;
		boolean getterActive = true;
		if(persistent) {
			getterActive = container.ext().isActive(getter);
			if(!getterActive) container.activate(getter, 1);
		}
		getter.reschedule(container, context);
		getter.clearCooldown(container, context, true);
		if(!getterActive) container.deactivate(getter, 1);
		return getter;
	}

	public void onNonFatalFailure(FetchException[] failures, int[] blockNos, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false, realTimeFlag);
		boolean reschedule = false;
		for(int i=0;i<failures.length;i++) {
			if(onNonFatalFailure(failures[i], blockNos[i], container, context, sched, maxTries, false))
				reschedule = true;
		}
		if(persistent) container.store(this); // We don't call container.store(this) in each onNonFatalFailure because it takes much CPU time.
		if(reschedule) {
			// At least one request was rescheduled, so we have requests to send.
			// Clear our cooldown cache entry and those of our parents.
			makeGetter(container, context);
			if(getter != null) {
				rescheduleGetter(container, context);
			}
		}
	}

	static class MyCooldownTrackerItem implements CooldownTrackerItem {
		MyCooldownTrackerItem(int data, int check) {
			dataRetries = new int[data];
			checkRetries = new int[check];
			dataCooldownTimes = new long[data];
			checkCooldownTimes = new long[check];
		}
		final int[] dataRetries;
		final int[] checkRetries;
		final long[] dataCooldownTimes;
		final long[] checkCooldownTimes;
	}
	
	/**
	 * Caller must set(this) iff returns true.
	 * @return True if the getter should be rescheduled.
	 */
	private boolean onNonFatalFailure(FetchException e, int blockNo, ObjectContainer container, ClientContext context, RequestScheduler sched, int maxTries, boolean callStore) {
		if(logMINOR) Logger.minor(this, "Calling onNonFatalFailure for block "+blockNo+" on "+this);
		int tries;
		boolean failed = false;
		boolean cooldown = false;
		ClientCHK key;
		int[] dataRetries = this.dataRetries;
		int[] checkRetries = this.checkRetries;
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		long[] dataCooldownTimes = tracker.dataCooldownTimes;
		long[] checkCooldownTimes = tracker.checkCooldownTimes;
		if(maxTries == -1) {
			// Cooldown and retry counts entirely kept in RAM.
			dataRetries = tracker.dataRetries;
			checkRetries = tracker.checkRetries;
			callStore = false;
		}
		checkCachedCooldownData(container);
		synchronized(this) {
			if(isFinished(container)) return false;
			if(blockNo < dataBuckets.length) {
				key = this.getBlockKey(blockNo, container);
				tries = ++dataRetries[blockNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					if(cachedCooldownTries == 0 ||
							tries % cachedCooldownTries == 0) {
						long now = System.currentTimeMillis();
						if(dataCooldownTimes[blockNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" data block no "+blockNo, new Exception("error"));
						else {
							SplitFileFetcherSegmentGet getter = makeGetter(container, context);
							if(getter != null) {
								dataCooldownTimes[blockNo] = now + cachedCooldownTime;
								if(logMINOR) Logger.minor(this, "Putting data block "+blockNo+" into cooldown until "+(dataCooldownTimes[blockNo]-now));
							}
						}
						cooldown = true;
					}
				}
			} else {
				int checkNo = blockNo - dataBuckets.length;
				key = this.getBlockKey(blockNo, container);
				if(persistent)
					container.activate(key, 5);
				tries = ++checkRetries[checkNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					if(cachedCooldownTries == 0 ||
							tries % cachedCooldownTries == 0) {
						long now = System.currentTimeMillis();
						if(checkCooldownTimes[checkNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" check block no "+blockNo, new Exception("error"));
						else {
							SplitFileFetcherSegmentGet getter = makeGetter(container, context);
							if(getter != null) {
								checkCooldownTimes[checkNo] = now + cachedCooldownTime;
								if(logMINOR) Logger.minor(this, "Putting check block "+blockNo+" into cooldown until "+(checkCooldownTimes[checkNo]-now));
							}
						}
						cooldown = true;
					}
				}
			}
		}
		if(failed) {
			onFatalFailure(e, blockNo, container, context);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return false;
		}
		boolean mustSchedule = false;
		if(cooldown) {
			// Registered to cooldown queue
			if(logMINOR)
				Logger.minor(this, "Added to cooldown queue: "+key+" for "+this);
		} else {
			// If we are here we are going to retry
			mustSchedule = true;
			if(logMINOR)
				Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
		}
		if(persistent) {
			if(callStore) container.store(this);
			container.deactivate(key, 5);
		}
		return mustSchedule;
	}
	
	private void checkCachedCooldownData(ObjectContainer container) {
		// 0/0 is illegal, and it's also the default, so use it to indicate we haven't fetched them.
		if(!(cachedCooldownTime == 0 && cachedCooldownTries == 0)) {
			// Okay, we have already got them.
			return;
		}
		innerCheckCachedCooldownData(container);
	}
	
	private void innerCheckCachedCooldownData(ObjectContainer container) {
		boolean active = true;
		if(persistent) {
			active = container.ext().isActive(blockFetchContext);
			container.activate(blockFetchContext, 1);
		}
		cachedCooldownTries = blockFetchContext.getCooldownRetries();
		cachedCooldownTime = blockFetchContext.getCooldownTime();
		if(!active) container.deactivate(blockFetchContext, 1);
	}

	private MyCooldownTrackerItem makeCooldownTrackerItem(
			ObjectContainer container, ClientContext context) {
		return (MyCooldownTrackerItem) context.cooldownTracker.make(this, persistent, container);
	}

	/** Called when localRequestOnly is set and we check the datastore and find nothing.
	 * We should fail() unless we are already decoding. */
	void failCheckingDatastore(ObjectContainer container, ClientContext context) {
		fail(null, container, context, false, true);
	}
	
	void fail(FetchException e, ObjectContainer container, ClientContext context, boolean dontDeactivateParent) {
		fail(e, container, context, dontDeactivateParent, false);
	}
	
	private void fail(FetchException e, ObjectContainer container, ClientContext context, boolean dontDeactivateParent, boolean checkingStoreOnly) {
		if(logMINOR) Logger.minor(this, "Failing segment "+this, e);
		boolean alreadyDecoding = false;
		synchronized(this) {
			if(finished) return;
			if(startedDecode && checkingStoreOnly) return;
			if(checkingStoreOnly) e = new FetchException(FetchException.DATA_NOT_FOUND);
			finished = true;
			alreadyDecoding = startedDecode;
			this.failureException = e;
			// Failure in decode is possible.
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
			}
		}
		if(getter != null) {
			if(persistent) container.activate(getter, 1);
			getter.unregister(container, context, getPriorityClass(container));
			if(persistent) getter.removeFrom(container);
			getter = null;
			if(persistent) container.store(this);
		}
		encoderFinished(container, context);
		removeSubSegments(container, context, false);
		if(persistent) {
			container.store(this);
			container.activate(parentFetcher, 1);
		}
		if(!alreadyDecoding)
			parentFetcher.removeMyPendingKeys(this, container, context);
		parentFetcher.segmentFinished(this, container, context);
		if(persistent && !dontDeactivateParent)
			container.deactivate(parentFetcher, 1);
	}

	public SendableGet schedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
		}
		SplitFileFetcherSegmentGet get = makeGetter(container, context);
		synchronized(this) {
			scheduled = true;
		}
		if(persistent)
			container.store(this);
		if(persistent) container.activate(get, 1);
		return get;
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
		if(keys == null) migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		return keys.getKey(blockNum, foundKeys, persistent);
	}
	
	public NodeCHK getBlockNodeKey(int blockNum, ObjectContainer container) {
		ClientCHK key = getBlockKey(blockNum, container);
		if(key != null) return key.getNodeCHK();
		else return null;
	}

	private void removeSubSegments(ObjectContainer container, ClientContext context, boolean finishing) {
		if(subSegments == null) return;
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
			if(deadSegs[i] == null) {
				Logger.error(this, "Subsegment "+i+" of "+deadSegs.length+" on "+this+" is null!");
				continue;
			}
			if(persistent)
				container.activate(deadSegs[i], 1);
			deadSegs[i].kill(container, context, true, false);
			context.getChkFetchScheduler(realTimeFlag).removeFromStarterQueue(deadSegs[i], container, true);
			if(persistent)
				container.deactivate(deadSegs[i], 1);
		}
		if(persistent && !finishing) {
			container.store(this);
			container.store(subSegments);
		}
	}

	public synchronized long getCooldownWakeup(int blockNum, int maxTries, ObjectContainer container, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		long[] dataCooldownTimes = tracker.dataCooldownTimes;
		long[] checkCooldownTimes = tracker.checkCooldownTimes;
		if(blockNum < dataBuckets.length)
			return dataCooldownTimes[blockNum];
		else
			return checkCooldownTimes[blockNum - dataBuckets.length];
	}
	
	synchronized void setMaxCooldownWakeup(long until, int blockNum, int maxTries, ObjectContainer container, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		long[] dataCooldownTimes = tracker.dataCooldownTimes;
		long[] checkCooldownTimes = tracker.checkCooldownTimes;
		if(blockNum < dataBuckets.length)
			dataCooldownTimes[blockNum] = Math.max(dataCooldownTimes[blockNum], until);
		else
			checkCooldownTimes[blockNum - dataBuckets.length] = Math.max(checkCooldownTimes[blockNum - dataBuckets.length], until);
	}
	
	private int getRetries(int blockNum, ObjectContainer container, ClientContext context) {
		return getRetries(blockNum, getMaxRetries(container), container, context);
	}
	
	private int getRetries(int blockNum, int maxTries, ObjectContainer container, ClientContext context) {
		int[] dataRetries = this.dataRetries;
		int[] checkRetries = this.checkRetries;
		if(maxTries == -1) {
			// Cooldown and retry counts entirely kept in RAM.
			MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
			dataRetries = tracker.dataRetries;
			checkRetries = tracker.checkRetries;
		}
		if(blockNum < dataBuckets.length)
			return dataRetries[blockNum];
		else
			return checkRetries[blockNum - dataBuckets.length];
	}

	/**
	 * @return True if the key was wanted, false otherwise. 
	 */
	public boolean requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context, SplitFileFetcherSubSegment dontDeactivate) {
		if(persistent)
			container.activate(this, 1);
		boolean notFound = true;
		synchronized(this) {
		if(isFinishing(container)) return false;
		// FIXME need a more efficient way to get maxTries!
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int[] matches = keys.getBlockNumbers((NodeCHK)key, foundKeys);
		for(int i : matches) {
			ClientCHK k = keys.getKey(i, foundKeys, persistent);
			if(k.getNodeKey(false).equals(key)) {
				if(getCooldownWakeup(i, maxTries, container, context) > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for data block "+i+" as deadline has not passed yet on "+this+" remaining time: "+(getCooldownWakeup(i, maxTries, container, context)-time)+"ms");
					return false;
				}
				if(foundKeys[i]) continue;
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": block "+i+" on "+this+" : tries="+getRetries(i, container, context)+"/"+maxTries);
				
				notFound = false;
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		}
		if(notFound) {
			Logger.error(this, "requeueAfterCooldown: Key not found!: "+key+" on "+this);
		} else {
			rescheduleGetter(container, context);
		}
		return true;
	}

	public synchronized long getCooldownWakeupByKey(Key key, ObjectContainer container, ClientContext context) {
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int blockNo = keys.getBlockNumber((NodeCHK)key, foundKeys);
		if(blockNo == -1) return -1;
		return getCooldownWakeup(blockNo, getMaxRetries(container), container, context);
	}

	public synchronized int getBlockNumber(Key key, ObjectContainer container) {
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		return keys.getBlockNumber((NodeCHK)key, foundKeys);
	}

	public synchronized Integer[] getKeyNumbersAtRetryLevel(int retryCount, ObjectContainer container, ClientContext context) {
		ArrayList<Integer> v = new ArrayList<Integer>();
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int maxTries = getMaxRetries(container);
		int[] dataRetries = this.dataRetries;
		int[] checkRetries = this.checkRetries;
		if(maxTries == -1) {
			// Cooldown and retry counts entirely kept in RAM.
			MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
			dataRetries = tracker.dataRetries;
			checkRetries = tracker.checkRetries;
		}
		for(int i=0;i<dataRetries.length;i++) {
			if(foundKeys[i]) continue;
			if(dataRetries[i] == retryCount)
				v.add(Integer.valueOf(i));
		}
		for(int i=0;i<checkRetries.length;i++) {
			if(foundKeys[i+dataBuckets.length]) continue;
			if(checkRetries[i] == retryCount)
				v.add(Integer.valueOf(i+dataBuckets.length));
		}
		return v.toArray(new Integer[v.size()]);
	}

	@Override
	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
		}
		if(persistent)
			container.store(this);
		this.fail(new FetchException(FetchException.INTERNAL_ERROR, "FEC failure: "+t, t), container, context, false);
	}

	public synchronized boolean haveBlock(int blockNo, ObjectContainer container) {
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

	public boolean isCancelled(ObjectContainer container) {
		return isFinishing(container);
	}

	public Key[] listKeys(ObjectContainer container) {
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		return keys.listNodeKeys(foundKeys, persistent);
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
		short onSuccessResult = (short) -1;
		FetchException fatal = null;
		synchronized(this) {
			blockNum = this.getBlockNumber(key, container);
			if(blockNum < 0) {
				if(logMINOR) Logger.minor(this, "Rejecting block because not found");
				return false;
			}
			if(finished || startedDecode || fetcherFinished) {
				if(logMINOR) Logger.minor(this, "Rejecting block because "+(finished?"finished ":"")+(startedDecode?"started decode ":"")+(fetcherFinished?"fetcher finished ":""));
				return false; // The block was present but we didn't want it.
			}
			if(logMINOR)
				Logger.minor(this, "Found key for block "+blockNum+" on "+this+" in onGotKey() for "+key);
			ClientCHK ckey = this.getBlockKey(blockNum, container);
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
						onSuccessResult = onSuccessInner(data, blockNum, container, context);
					}
				}
			}
		}
		if(fatal != null) {
			if(persistent)
				container.activate(errors, 1);
			errors.inc(fatal.mode);
			if(persistent)
				errors.storeTo(container);
			this.onFatalFailure(fatal, blockNum, container, context);
			return false;
		} else if(data == null) {
			return false; // Extract failed
		} else { // cb != null
			if(!cb.isMetadata()) {
				if(onSuccessResult != (short) -1)
					finishOnSuccess(onSuccessResult, container, context);
				return true;
			} else {
				onFatalFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), blockNum, container, context);
				return true;
			}
		}
	}
	
	private synchronized MinimalSplitfileBlock getBlock(int blockNum) {
		if(blockNum < dataBuckets.length) {
			return dataBuckets[blockNum];
		}
		blockNum -= dataBuckets.length;
		return checkBuckets[blockNum];
	}
	
	public Bucket getBlockBucket(int blockNum, ObjectContainer container) {
		MinimalSplitfileBlock block = getBlock(blockNum);
		boolean active = true;
		if(block != null && persistent) {
			active = container.ext().isActive(block);
			if(!active) container.activate(block, 1);
		}
		if(block == null) {
			Logger.error(this, "Block is null: "+blockNum+" on "+this+" activated = "+container.ext().isActive(this)+" finished = "+finished+" encoder finished = "+encoderFinished+" fetcher finished = "+fetcherFinished);
			return null;
		}
		Bucket ret = block.getData();
		if(ret == null && logMINOR) Logger.minor(this, "Bucket is null: "+blockNum+" on "+this+" for "+block);
		if(!active)
			container.deactivate(block, 1);
		return ret;
	}
	
	public boolean hasBlockWrapper(int i) {
		return getBlock(i) != null;
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
			// All other callers to onFatalFailure increment the error counter in SplitFileFetcherSubSegment.
			// Therefore we must do so here.
			if(persistent)
				container.activate(errors, 1);
			errors.inc(FetchException.BLOCK_DECODE_ERROR);
			if(persistent)
				errors.storeTo(container);
			this.onFatalFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), blockNum, container, context);
			return null;
		} catch (TooBigException e) {
			if(persistent)
				container.activate(errors, 1);
			errors.inc(FetchException.TOO_BIG);
			if(persistent)
				errors.storeTo(container);
			this.onFatalFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), blockNum, container, context);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			if(persistent)
				container.activate(errors, 1);
			errors.inc(FetchException.BUCKET_ERROR);
			if(persistent)
				errors.storeTo(container);
			this.onFatalFailure(new FetchException(FetchException.BUCKET_ERROR, e), blockNum, container, context);
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
		container.deactivate(keys, 1);
	}

	public void freeDecodedData(ObjectContainer container, boolean noStore) {
		synchronized(this) {
			if(!encoderFinished) return;
			if(!fetcherHalfFinished) return;
		}
		if(logMINOR) Logger.minor(this, "Freeing decoded data on segment "+this);
		if(decodedData != null) {
			if(persistent)
				container.activate(decodedData, 1);
			decodedData.free();
			if(persistent)
				decodedData.removeFrom(container);
			decodedData = null;
		}
		for(int i=0;i<dataBuckets.length;i++) {
			MinimalSplitfileBlock block = dataBuckets[i];
			if(block == null) continue;
			if(persistent) container.activate(block, 1);
			Bucket data = block.getData();
			if(data != null) {
				// We only free the data blocks at the last minute.
				if(persistent) container.activate(data, 1);
				data.free();
			}
			if(persistent) block.removeFrom(container);
			dataBuckets[i] = null;
		}
		for(int i=0;i<checkBuckets.length;i++) {
			MinimalSplitfileBlock block = checkBuckets[i];
			if(block == null) continue;
			if(persistent) container.activate(block, 1);
			Bucket data = block.getData();
			if(data != null) {
				if(persistent) container.activate(data, 1);
				data.free();
			}
			if(persistent) block.removeFrom(container);
			checkBuckets[i] = null;
		}
		if(persistent && !noStore)
			container.store(this);
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(!finished) {
			Logger.error(this, "Removing "+this+" but not finished, fetcher finished "+fetcherFinished+" fetcher half finished "+fetcherHalfFinished+" encoder finished "+encoderFinished);
		}
		if(logMINOR) Logger.minor(this, "removing "+this);
		context.cooldownTracker.remove(this, true, container);
		freeDecodedData(container, true);
		removeSubSegments(container, context, true);
		if(subSegments != null) container.delete(subSegments);
		if(dataKeys != null) {
			for(int i=0;i<dataKeys.length;i++) {
				if(dataKeys[i] != null) dataKeys[i].removeFrom(container);
				dataKeys[i] = null;
			}
		}
		if(checkKeys != null) {
			for(int i=0;i<checkKeys.length;i++) {
				if(checkKeys[i] != null) checkKeys[i].removeFrom(container);
				checkKeys[i] = null;
			}
		}
		container.activate(errors, 1);
		errors.removeFrom(container);
		if(failureException != null) {
			container.activate(failureException, 5);
			failureException.removeFrom(container);
		}
		if(keys != null) {
			container.activate(keys, 1);
			keys.removeFrom(container);
		}
		if(getter != null) {
			container.activate(getter, 1);
			Logger.error(this, "Getter still exists: "+getter+" for "+this);
			// Unable to unregister because parent does not exist so we don't know the priority.
			getter.removeFrom(container);
		}
		container.delete(this);
	}

	/** Free the data blocks but only if the encoder has finished with them. */
	public void fetcherHalfFinished(ObjectContainer container) {
		boolean finish = false;
		synchronized(this) {
			if(fetcherHalfFinished) return;
			fetcherHalfFinished = true;
			finish = encoderFinished;
		}
		if(finish) {
			if(crossCheckBlocks == 0) 
				freeDecodedData(container, false);
			// Else wait for the whole splitfile to complete in fetcherFinished(), and then free decoded data in removeFrom().
		} else {
			if(logMINOR) Logger.minor(this, "Fetcher half-finished but fetcher not finished on "+this);
		}
		if(persistent) container.store(this);
		
	}
	
	public void fetcherFinished(ObjectContainer container, ClientContext context) {
		context.cooldownTracker.remove(this, persistent, container);
		synchronized(this) {
			if(fetcherFinished) return;
			fetcherFinished = true;
			fetcherHalfFinished = true;
			if(!encoderFinished) {
				if(!startedDecode) {
					if(logMINOR) Logger.minor(this, "Never started decode, completing immediately on "+this);
					encoderFinished = true;
					if(persistent) container.store(this);
				} else {
					if(persistent) container.store(this);
					if(logMINOR) Logger.minor(this, "Fetcher finished but encoder not finished on "+this);
					return;
				}
			}
		}
		if(persistent) removeFrom(container, context);
		else freeDecodedData(container, true);
	}
	
	private void encoderFinished(ObjectContainer container, ClientContext context) {
		context.cooldownTracker.remove(this, persistent, container);
		boolean finish = false;
		boolean half = false;
		synchronized(this) {
			encoderFinished = true;
			finish = fetcherFinished;
			half = fetcherHalfFinished;
		}
		if(finish) {
			if(persistent) removeFrom(container, context);
		} else if(half) {
			if(crossCheckBlocks == 0)
				freeDecodedData(container, false);
			// Else wait for the whole splitfile to complete in fetcherFinished(), and then free decoded data in removeFrom().
			if(persistent) container.store(this);
			if(logMINOR) Logger.minor(this, "Encoder finished but fetcher not finished on "+this);
		} else {
			if(persistent) container.store(this);
		}
	}
	
	public int allocateCrossDataBlock(SplitFileFetcherCrossSegment seg, Random random) {
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

	public int allocateCrossCheckBlock(SplitFileFetcherCrossSegment seg, Random random) {
		if(crossCheckBlocksAllocated == crossCheckBlocks) return -1;
		int x = dataBuckets.length - (1 + random.nextInt(crossCheckBlocks));
		for(int i=0;i<crossCheckBlocks;i++) {
			x++;
			if(x == dataBuckets.length) x = dataBuckets.length - crossCheckBlocks;
			if(crossSegmentsByBlock[x] == null) {
				crossSegmentsByBlock[x] = seg;
				crossCheckBlocksAllocated++;
				return x;
			}
		}
		throw new IllegalStateException("Unable to allocate cross check block even though have not used all slots up???");
	}
	
	public final int realDataBlocks() {
		return dataBuckets.length - crossCheckBlocks;
	}

	/**
	 * 
	 * @param fetching
	 * @param onlyLowestTries If true, only return blocks of the lowest currently valid 
	 * retry count. Otherwise return all blocks that we haven't either found or given up on.
	 * @param container
	 * @param context
	 * @return
	 */
	public ArrayList<Integer> validBlockNumbers(KeysFetchingLocally fetching, boolean onlyLowestTries,
			ObjectContainer container, ClientContext context) {
		long now = System.currentTimeMillis();
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int maxTries = getMaxRetries(container);
		synchronized(this) {
			int minRetries = Integer.MAX_VALUE;
			ArrayList<Integer> list = null;
			if(startedDecode || isFinishing(container)) return null;
			for(int i=0;i<dataBuckets.length+checkBuckets.length;i++) {
				if(foundKeys[i]) continue;
				if(getCooldownWakeup(i, maxTries, container, context) > now) continue;
				// Double check
				if(getBlockBucket(i, container) != null) continue;
				// Possible ...
				Key key = keys.getNodeKey(i, null, true);
				if(fetching.hasKey(key, getter, persistent, container)) continue;
				// Do not check RecentlyFailed here.
				// 1. We're synchronized, so it would be a bad idea here.
				// 2. It's way too heavyweight given we're not going to send most of the items added.
				// Check it in the caller.
				if(onlyLowestTries) {
					int retryCount = this.getRetries(i, container, context);
					if(retryCount > minRetries) {
						// Ignore
						continue;
					}
					if(retryCount < minRetries && list != null)
						list.clear();
				}
				if(list == null) list = new ArrayList<Integer>();
				list.add(i);
			}
			return list;
		}
	}
	
	public boolean checkRecentlyFailed(int blockNum, ObjectContainer container, ClientContext context, KeysFetchingLocally fetching, long now) {
		if(this.keys == null)
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		Key key = this.keys.getNodeKey(blockNum, null, true);
		long timeout = fetching.checkRecentlyFailed(key, realTimeFlag);
		if(timeout <= now) return false;
		int maxRetries = getMaxRetries(container);
		if(maxRetries == -1 || (maxRetries >= RequestScheduler.COOLDOWN_RETRIES)) {
			if(logMINOR) Logger.minor(this, "RecentlyFailed -> cooldown until "+TimeUtil.formatTime(timeout-now)+" on "+this);
			// Concurrency is fine here, it won't go away before the given time.
			setMaxCooldownWakeup(timeout, blockNum, this.getMaxRetries(container), container, context);
		} else {
			FetchException e = new FetchException(FetchException.RECENTLY_FAILED);
			incErrors(e, container);
			onNonFatalFailure(e, blockNum, container, context);
		}
		return true;
	}

	private void incErrors(FetchException e, ObjectContainer container) {
		if(persistent)
			container.activate(errors, 1);
    	errors.inc(e.getMode());
		if(persistent)
			errors.storeTo(container);
	}

	/** Separate method because we will need to create the Key anyway for checking against
	 * the KeysFetchingLocally, and we can reuse that in the created Block. Yes we don't 
	 * pass that in at the moment but we will in future. 
	 * future.
	 * @param request
	 * @param sched
	 * @param getter 
	 * @param keysFetching 
	 * @param container
	 * @param context
	 * @return
	 */
	public List<PersistentChosenBlock> makeBlocks(
			PersistentChosenRequest request, RequestScheduler sched,
			KeysFetchingLocally fetching, SplitFileFetcherSegmentGet getter, ObjectContainer container, ClientContext context) {
		long now = System.currentTimeMillis();
		ArrayList<PersistentChosenBlock> list = null;
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int maxTries = getMaxRetries(container);
		synchronized(this) {
			if(startedDecode || isFinishing(container)) return null;
			for(int i=0;i<dataBuckets.length+checkBuckets.length;i++) {
				if(foundKeys[i]) continue;
				if(getCooldownWakeup(i, maxTries, container, context) > now) continue;
				// Double check
				if(getBlockBucket(i, container) != null) continue;
				// Possible ...
				Key key = keys.getNodeKey(i, null, true);
				if(fetching.hasKey(key, getter, persistent, container)) continue;
				if(list == null) list = new ArrayList<PersistentChosenBlock>();
				ClientCHK ckey = keys.getKey(i, null, true); // FIXME Duplicates the routingKey field
				list.add(new PersistentChosenBlock(false, request, new SplitFileFetcherSegmentSendableRequestItem(i), key, ckey, sched));
			}
		}
		if(list == null) return null;
		for(Iterator<PersistentChosenBlock> i = list.iterator();i.hasNext();) {
			PersistentChosenBlock block = i.next();
			// We must do the actual check outside the lock.
			long l = fetching.checkRecentlyFailed(block.key, realTimeFlag);
			if(l < now) continue; // Okay
			i.remove();
			if(maxTries == -1 || (maxTries >= RequestScheduler.COOLDOWN_RETRIES)) {
				if(logMINOR) Logger.minor(this, "RecentlyFailed -> cooldown until "+TimeUtil.formatTime(l-now)+" on "+this);
				// Concurrency is fine here, it won't go away before the given time.
				setMaxCooldownWakeup(l, ((SplitFileFetcherSegmentSendableRequestItem)block.token).blockNum, maxTries, container, context);
			} else {
				FetchException e = new FetchException(FetchException.RECENTLY_FAILED);
				incErrors(e, container);
				onNonFatalFailure(e, ((SplitFileFetcherSegmentSendableRequestItem)(block.token)).blockNum, container, context);
			}
		}
		return list;
	}
	
	public long getCooldownTime(ObjectContainer container, ClientContext context, HasCooldownCacheItem parentRGA, long now) {
		if(keys == null) 
			migrateToKeys(container);
		else {
			if(persistent) container.activate(keys, 1);
		}
		int maxTries = getMaxRetries(container);
		KeysFetchingLocally fetching = context.getChkFetchScheduler(realTimeFlag).fetchingKeys();
		long cooldownWakeup = Long.MAX_VALUE;
		synchronized(this) {
			if(startedDecode || isFinishing(container)) return -1; // Remove
			for(int i=0;i<dataBuckets.length+checkBuckets.length;i++) {
				if(foundKeys[i]) continue;
				// Double check
				if(getBlockBucket(i, container) != null) {
					continue;
				}
				// Possible ...
				long wakeup = getCooldownWakeup(i, maxTries, container, context);
				if(wakeup > now) {
					if(wakeup < cooldownWakeup) cooldownWakeup = wakeup;
					continue;
				}
				Key key = keys.getNodeKey(i, null, true);
				if(fetching.hasKey(key, getter, persistent, container)) continue;
				
				return 0; // Stuff to send right now.
			}
		}
		return cooldownWakeup;
	}

	public long countAllKeys(ObjectContainer container, ClientContext context) {
		int count = 0;
		synchronized(this) {
			if(startedDecode || isFinishing(container)) return 0;
			for(int i=0;i<dataBuckets.length+checkBuckets.length;i++) {
				if(foundKeys[i]) continue;
				// Double check
				if(getBlockBucket(i, container) != null) continue;
				count++;
			}
			return count;
		}
	}

	public long countSendableKeys(ObjectContainer container, ClientContext context) {
		int count = 0;
		long now = System.currentTimeMillis();
		int maxTries = getMaxRetries(container);
		synchronized(this) {
			if(startedDecode || isFinishing(container)) return 0;
			for(int i=0;i<dataBuckets.length+checkBuckets.length;i++) {
				if(foundKeys[i]) continue;
				if(getCooldownWakeup(i, maxTries, container, context) > now) continue;
				// Double check
				if(getBlockBucket(i, container) != null) continue;
				count++;
			}
			return count;
		}
	}

	public synchronized SplitFileFetcherSegmentGet makeGetter(ObjectContainer container, ClientContext context) {
		if(finishing || startedDecode || finished) return null;
		if(getter == null) {
			boolean parentActive = true;
			if(persistent) {
				parentActive = container.ext().isActive(parent);
				if(!parentActive) container.activate(parent, 1);
			}
			getter = new SplitFileFetcherSegmentGet(parent, this, realTimeFlag);
			if(!parentActive) container.deactivate(parent, 1);
			System.out.println("Auto-migrated from subsegments to SegmentGet on "+this+" : "+getter);
			getter.storeTo(container);
			container.store(this);
			this.removeSubSegments(container, context, false);
			return getter;
		} else {
			return getter;
		}
	}

	@Override
	public CooldownTrackerItem makeCooldownTrackerItem() {
		return new MyCooldownTrackerItem(dataBuckets.length, checkBuckets.length);
	}

	public synchronized int getMaxRetries(ObjectContainer container) {
		if(maxRetries != 0) return maxRetries;
		return innerGetMaxRetries(container);
	}
	
	private synchronized int innerGetMaxRetries(ObjectContainer container) {
		boolean contextActive = true;
		if(persistent) {
			contextActive = container.ext().isActive(blockFetchContext);
			if(!contextActive) container.activate(blockFetchContext, 1);
		}
		maxRetries = blockFetchContext.maxSplitfileBlockRetries;
		if(persistent) {
			container.store(this);
			if(!contextActive) container.deactivate(blockFetchContext, 1);
		}
		return maxRetries;
	}

	/** Reread the cached cooldown values (and anything else) from the FetchContext
	 * after it changes. FIXME: Ideally this should be a generic mechanism, but
	 * that looks too complex without significant changes to data structures.
	 * For now it's just a hack to make changing the polling interval in USKs work.
	 * See https://bugs.freenetproject.org/view.php?id=4984
	 * @param container The database if this is a persistent request.
	 * @param context The context object.
	 */
	public void onChangedFetchContext(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
		}
		innerCheckCachedCooldownData(container);
		innerGetMaxRetries(container);
	}

}
