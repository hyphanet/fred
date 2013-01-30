/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import freenet.support.math.MersenneTwister;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.CHKBlock;
import freenet.node.SendableGet;
import freenet.support.BinaryBloomFilter;
import freenet.support.BloomFilter;
import freenet.support.CountingBloomFilter;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.compress.Compressor;
import freenet.support.io.FileUtil;

/**
 * Fetch a splitfile, decompress it if need be, and return it to the GetCompletionCallback.
 * Most of the work is done by the segments, and we do not need a thread.
 */
public class SplitFileFetcher implements ClientGetState, HasKeyListener {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	final FetchContext fetchContext;
	final FetchContext blockFetchContext;
	final boolean deleteFetchContext;
	final ArchiveContext archiveContext;
	final List<? extends Compressor> decompressors;
	final ClientMetadata clientMetadata;
	final ClientRequester parent;
	final GetCompletionCallback cb;
	final int recursionLevel;
	/** The splitfile type. See the SPLITFILE_ constants on Metadata. */
	final short splitfileType;
	/** The segment length. -1 means not segmented and must get everything to decode. */
	final int blocksPerSegment;
	/** The segment length in check blocks. */
	final int checkBlocksPerSegment;
	final int deductBlocksFromSegments;
	/** Total number of segments */
	final int segmentCount;
	/** The detailed information on each segment */
	final SplitFileFetcherSegment[] segments;
	/** Maximum temporary length */
	final long maxTempLength;
	/** Have all segments finished? Access synchronized. */
	private boolean allSegmentsFinished;
	/** Override length. If this is positive, truncate the splitfile to this length. */
	private final long overrideLength;
	private boolean finished;
	private long token;
	final boolean persistent;
	private FetchException otherFailure;
	final boolean realTimeFlag;

	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.

	private final int hashCode;

	@Override
	public int hashCode() {
		return hashCode;
	}

	// Bloom filter stuff
	/** The main bloom filter, which includes every key in the segment, is stored
	 * in this file. It is a counting filter and is updated when a key is found. */
	File mainBloomFile;
	/** The per-segment bloom filters are kept in this (slightly larger) file,
	 * appended one after the next. */
	File altBloomFile;
	
	// The above are obsolete. We now store the bloom filter in the database.
	CountingBloomFilter cachedMainBloomFilter;
	BinaryBloomFilter[] cachedSegmentBloomFilters;
	
	/** Size of the main Bloom filter in bytes. */
	final int mainBloomFilterSizeBytes;
	/** Default mainBloomElementsPerKey. False positives is approx
	 * 0.6185^[this number], so 19 gives us 0.01% false positives, which should
	 * be acceptable even if there are thousands of splitfiles on the queue. */
	static final int DEFAULT_MAIN_BLOOM_ELEMENTS_PER_KEY = 19;
	/** Number of hashes for the main filter. */
	final int mainBloomK;
	/** What proportion of false positives is acceptable for the per-segment
	 * Bloom filters? This is divided by the number of segments, so it is (roughly)
	 * an overall probability of any false positive given that we reach the
	 * per-segment filters. IMHO 1 in 100 is adequate. */
	static final double ACCEPTABLE_BLOOM_FALSE_POSITIVES_ALL_SEGMENTS = 0.01;
	/** Size of per-segment bloom filter in bytes. This is calculated from the
	 * above constant and the number of segments, and rounded up. */
	final int perSegmentBloomFilterSizeBytes;
	/** Number of hashes for the per-segment bloom filters. */
	final int perSegmentK;
	private int keyCount;
	/** Salt used in the secondary Bloom filters if the primary matches.
	 * The primary Bloom filters use the already-salted saltedKey. */
	private final byte[] localSalt;
	/** Reference set on the first call to makeKeyListener().
	 * NOTE: db4o DOES NOT clear transient variables on deactivation.
	 * So as long as this is paged in (i.e. there is a reference to it, i.e. the
	 * KeyListener), it will remain valid, once it is set by the first call
	 * during resuming. */
	private transient SplitFileFetcherKeyListener tempListener;

	private final int crossCheckBlocks;
	private final SplitFileFetcherCrossSegment[] crossSegments;
	
	public SplitFileFetcher(Metadata metadata, GetCompletionCallback rcb, ClientRequester parent2,
			FetchContext newCtx, boolean deleteFetchContext, boolean realTimeFlag, List<? extends Compressor> decompressors2, ClientMetadata clientMetadata,
			ArchiveContext actx, int recursionLevel, long token2, boolean topDontCompress, short topCompatibilityMode, ObjectContainer container, ClientContext context) throws FetchException, MetadataParseException {
		this.persistent = parent2.persistent();
		this.realTimeFlag = realTimeFlag;
		this.deleteFetchContext = deleteFetchContext;
		if(logMINOR)
			Logger.minor(this, "Persistence = "+persistent+" from "+parent2, new Exception("debug"));
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		this.hashCode = hash;
		this.finished = false;
		this.fetchContext = newCtx;
		if(newCtx == null)
			throw new NullPointerException();
		this.archiveContext = actx;
		blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true, null);
		this.decompressors = persistent ? new ArrayList<Compressor>(decompressors2) : decompressors2;
		if(decompressors.size() > 1) {
			Logger.error(this, "Multiple decompressors: "+decompressors.size()+" - this is almost certainly a bug", new Exception("debug"));
		}
		this.clientMetadata = clientMetadata == null ? new ClientMetadata() : clientMetadata.clone(); // copy it as in SingleFileFetcher
		this.cb = rcb;
		this.recursionLevel = recursionLevel + 1;
		this.parent = parent2;
		localSalt = new byte[32];
		context.random.nextBytes(localSalt);
		if(parent2.isCancelled())
			throw new FetchException(FetchException.CANCELLED);
		overrideLength = metadata.dataLength();
		this.splitfileType = metadata.getSplitfileType();
		SplitFileSegmentKeys[] segmentKeys = metadata.grabSegmentKeys(container);
		if(persistent) {
			// Clear them here so they don't get deleted and we don't need to clone them.
			metadata.clearSplitfileKeys();
			container.store(metadata);
		}
		long eventualLength = Math.max(overrideLength, metadata.uncompressedDataLength());
		boolean wasActive = true;
		if(persistent) {
			wasActive = container.ext().isActive(cb);
			if(!wasActive)
				container.activate(cb, 1);
		}
		cb.onExpectedSize(eventualLength, container, context);
		if(metadata.uncompressedDataLength() > 0)
			cb.onFinalizedMetadata(container);
		if(!wasActive)
			container.deactivate(cb, 1);
		if(eventualLength > 0 && newCtx.maxOutputLength > 0 && eventualLength > newCtx.maxOutputLength)
			throw new FetchException(FetchException.TOO_BIG, eventualLength, true, clientMetadata.getMIMEType());

		this.token = token2;
		
		CompatibilityMode minCompatMode = metadata.getMinCompatMode();
		CompatibilityMode maxCompatMode = metadata.getMaxCompatMode();

		int crossCheckBlocks = metadata.getCrossCheckBlocks();
		
		blocksPerSegment = metadata.getDataBlocksPerSegment();
		checkBlocksPerSegment = metadata.getCheckBlocksPerSegment();
		
		int splitfileDataBlocks = 0;
		int splitfileCheckBlocks = 0;
		
		for(SplitFileSegmentKeys keys : segmentKeys) {
			splitfileDataBlocks += keys.getDataBlocks();
			splitfileCheckBlocks += keys.getCheckBlocks();
		}
		
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			if(splitfileCheckBlocks > 0) {
				Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks+" check blocks found!! : "+this);
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile type is non-redundant yet have "+splitfileCheckBlocks+" check blocks");
			}
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			
			boolean dontCompress = decompressors.isEmpty();
			if(topCompatibilityMode != 0) {
				// If we have top compatibility mode, then we can give a definitive answer immediately, with the splitfile key, with dontcompress, etc etc.
				if(minCompatMode == CompatibilityMode.COMPAT_UNKNOWN ||
						!(minCompatMode.ordinal() > topCompatibilityMode || maxCompatMode.ordinal() < topCompatibilityMode)) {
					minCompatMode = maxCompatMode = CompatibilityMode.values()[topCompatibilityMode];
					dontCompress = topDontCompress;
				} else
					throw new FetchException(FetchException.INVALID_METADATA, "Top compatibility mode is incompatible with detected compatibility mode");
			}
			// We assume we are the bottom layer. 
			// If the top-block stats are passed in then we can safely say the report is definitive.
			cb.onSplitfileCompatibilityMode(minCompatMode, maxCompatMode, metadata.getCustomSplitfileKey(), dontCompress, true, topCompatibilityMode != 0, container, context);

			if((blocksPerSegment > fetchContext.maxDataBlocksPerSegment)
					|| (checkBlocksPerSegment > fetchContext.maxCheckBlocksPerSegment))
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			
				
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		segmentCount = metadata.getSegmentCount();
		this.maxTempLength = fetchContext.maxTempLength;
		if(logMINOR)
			Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+
					", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount+
					", data blocks: "+splitfileDataBlocks+", check blocks: "+splitfileCheckBlocks);
		segments = new SplitFileFetcherSegment[segmentCount]; // initially null on all entries

		this.crossCheckBlocks = crossCheckBlocks;
		
		long finalLength = 1L * (splitfileDataBlocks - segmentCount * crossCheckBlocks) * CHKBlock.DATA_LENGTH;
		if(finalLength > overrideLength) {
			if(finalLength - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+finalLength+" but length is "+finalLength);
			finalLength = overrideLength;
		}
		
		mainBloomFile = null;
		altBloomFile = null;
		int mainElementsPerKey = DEFAULT_MAIN_BLOOM_ELEMENTS_PER_KEY;
		int origSize = splitfileDataBlocks + splitfileCheckBlocks;
		mainBloomK = (int) (mainElementsPerKey * 0.7);
		long elementsLong = origSize * mainElementsPerKey;
		// REDFLAG: SIZE LIMIT: 3.36TB limit!
		if(elementsLong > Integer.MAX_VALUE)
			throw new FetchException(FetchException.TOO_BIG, "Cannot fetch splitfiles with more than "+(Integer.MAX_VALUE/mainElementsPerKey)+" keys! (approx 3.3TB)");
		int mainSizeBits = (int)elementsLong; // counting filter
		mainSizeBits = (mainSizeBits + 7) & ~7; // round up to bytes
		mainBloomFilterSizeBytes = mainSizeBits / 8 * 2; // counting filter
		double acceptableFalsePositives = ACCEPTABLE_BLOOM_FALSE_POSITIVES_ALL_SEGMENTS / segments.length;
		int perSegmentBitsPerKey = (int) Math.ceil(Math.log(acceptableFalsePositives) / Math.log(0.6185));
		int segBlocks = blocksPerSegment + checkBlocksPerSegment;
		if(segBlocks > origSize)
			segBlocks = origSize;
		int perSegmentSize = perSegmentBitsPerKey * segBlocks;
		perSegmentSize = (perSegmentSize + 7) & ~7;
		perSegmentBloomFilterSizeBytes = perSegmentSize / 8;
		perSegmentK = BloomFilter.optimialK(perSegmentSize, segBlocks);
		keyCount = origSize;
		// Now create it.
		if(logMINOR)
			Logger.minor(this, "Creating block filter for "+this+": keys="+(splitfileDataBlocks+splitfileCheckBlocks)+" main bloom size "+mainBloomFilterSizeBytes+" bytes, K="+mainBloomK+", filename="+mainBloomFile+" alt bloom filter: filename="+altBloomFile+" segments: "+segments.length+" each is "+perSegmentBloomFilterSizeBytes+" bytes k="+perSegmentK);
		try {
			tempListener = new SplitFileFetcherKeyListener(this, keyCount, mainBloomFile, altBloomFile, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, true, null, null, container, false, realTimeFlag);
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, "Unable to write Bloom filters for splitfile");
		}

		if(persistent)
			container.store(this);

		boolean pre1254 = !(minCompatMode == CompatibilityMode.COMPAT_CURRENT || minCompatMode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal());
		boolean pre1250 = (minCompatMode == CompatibilityMode.COMPAT_UNKNOWN || minCompatMode == CompatibilityMode.COMPAT_1250_EXACT);
		
		int maxRetries = blockFetchContext.maxSplitfileBlockRetries;
		byte cryptoAlgorithm = metadata.getSplitfileCryptoAlgorithm();
		byte[] splitfileCryptoKey = metadata.getSplitfileCryptoKey();
		
		for(int i=0;i<segments.length;i++) {
			// splitfile* will be overwritten, this is bad
			// so copy them
			SplitFileSegmentKeys keys = segmentKeys[i];
			int dataBlocks = keys.getDataBlocks();
			int checkBlocks = keys.getCheckBlocks();
			if((dataBlocks > fetchContext.maxDataBlocksPerSegment)
					|| (checkBlocks > fetchContext.maxCheckBlocksPerSegment))
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			segments[i] = new SplitFileFetcherSegment(splitfileType, keys,
					this, archiveContext, blockFetchContext, maxTempLength, recursionLevel, parent, i, pre1250, pre1254, crossCheckBlocks, cryptoAlgorithm, splitfileCryptoKey, maxRetries, realTimeFlag);
			int data = keys.getDataBlocks();
			int check = keys.getCheckBlocks();
			for(int j=0;j<(data+check);j++) {
				tempListener.addKey(keys.getKey(j, null, false).getNodeKey(false), i, context);
			}
			if(persistent) {
				container.store(segments[i]);
				segments[i].deactivateKeys(container);
			}
		}
		int totalCrossCheckBlocks = segments.length * crossCheckBlocks;
		parent.addMustSucceedBlocks(splitfileDataBlocks - totalCrossCheckBlocks, container);
		parent.addBlocks(splitfileCheckBlocks + totalCrossCheckBlocks, container);
		parent.notifyClients(container, context);
		
		deductBlocksFromSegments = metadata.getDeductBlocksFromSegments();
		
		if(crossCheckBlocks != 0) {
			Random random = new MersenneTwister(Metadata.getCrossSegmentSeed(metadata.getHashes(), metadata.getHashThisLayerOnly()));
			// Cross segment redundancy: Allocate the blocks.
			crossSegments = new SplitFileFetcherCrossSegment[segments.length];
			int segLen = blocksPerSegment;
			for(int i=0;i<crossSegments.length;i++) {
				Logger.normal(this, "Allocating blocks (on fetch) for cross segment "+i);
				if(segments.length - i == deductBlocksFromSegments) {
					segLen--;
				}
				SplitFileFetcherCrossSegment seg = new SplitFileFetcherCrossSegment(persistent, segLen, crossCheckBlocks, parent, this, metadata.getSplitfileType());
				crossSegments[i] = seg;
				for(int j=0;j<segLen;j++) {
					// Allocate random data blocks
					allocateCrossDataBlock(seg, random);
				}
				for(int j=0;j<crossCheckBlocks;j++) {
					// Allocate check blocks
					allocateCrossCheckBlock(seg, random);
				}
				if(persistent) seg.storeTo(container);
			}
		} else {
			crossSegments = null;
		}
		
		if(persistent) {
			for(SplitFileFetcherSegment seg : segments) {
				if(crossCheckBlocks != 0)
					container.store(seg);
				container.deactivate(seg, 1);
			}
		}

		try {
			tempListener.writeFilters(container, "construction");
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, "Unable to write Bloom filters for splitfile");
		}
	}
	
	private void allocateCrossDataBlock(SplitFileFetcherCrossSegment segment, Random random) {
		int x = 0;
		for(int i=0;i<10;i++) {
			x = random.nextInt(segments.length);
			SplitFileFetcherSegment seg = segments[x];
			int blockNum = seg.allocateCrossDataBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		for(int i=0;i<segments.length;i++) {
			x++;
			if(x == segments.length) x = 0;
			SplitFileFetcherSegment seg = segments[x];
			int blockNum = seg.allocateCrossDataBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		throw new IllegalStateException("Unable to allocate cross data block!");
	}

	private void allocateCrossCheckBlock(SplitFileFetcherCrossSegment segment, Random random) {
		int x = 0;
		for(int i=0;i<10;i++) {
			x = random.nextInt(segments.length);
			SplitFileFetcherSegment seg = segments[x];
			int blockNum = seg.allocateCrossCheckBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		for(int i=0;i<segments.length;i++) {
			x++;
			if(x == segments.length) x = 0;
			SplitFileFetcherSegment seg = segments[x];
			int blockNum = seg.allocateCrossCheckBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		throw new IllegalStateException("Unable to allocate cross data block!");
	}

	/** Return the final status of the fetch. Throws an exception, or returns an
	 * InputStream from which the fetched data may be read.
	 * @throws FetchException If the fetch failed for some reason.
	 */
	private SplitFileStreamGenerator finalStatus(final ObjectContainer container, final ClientContext context) throws FetchException {
		long length = 0;
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment s = segments[i];
			if(persistent)
				container.activate(s, 1);
			if(!s.succeeded()) {
				throw new IllegalStateException("Not all finished");
			}
			s.throwError(container);
			// If still here, it succeeded
			long sz = s.decodedLength(container);
			length += sz;
			if(logMINOR)
				Logger.minor(this, "Segment "+i+" decoded length "+sz+" total length now "+length+" for "+s.dataBuckets.length+" blocks which should be "+(s.dataBuckets.length * CHKBlock.DATA_LENGTH));
			// Healing is done by Segment
		}
		if(length > overrideLength) {
			if(length - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+length+" but length is "+length);
			length = overrideLength;
		}
		SplitFileStreamGenerator streamGenerator = new SplitFileStreamGenerator(segments, length, crossCheckBlocks);
		return streamGenerator;
}

	public void segmentFinished(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		if(logMINOR) Logger.minor(this, "Finished segment: "+segment);
		boolean finish = false;
		synchronized(this) {
			boolean allDone = true;
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				if(!segments[i].succeeded()) {
					if(logMINOR) Logger.minor(this, "Segment "+segments[i]+" is not finished");
					allDone = false;
				}
			}
			if(allDone) {
				if(allSegmentsFinished) {
					if(logMINOR)
						// Race condition. No problem.
						Logger.minor(this, "Was already finished! (segmentFinished("+segment+ ')', new Exception("debug"));
				} else {
					allSegmentsFinished = true;
					finish = true;
				}
			} else {
				for(int i=0;i<segments.length;i++) {
					if(segments[i] == segment) continue;
					if(persistent)
						container.deactivate(segments[i], 1);
				}
			}
			notifyAll();
		}
		if(persistent) container.store(this);
		if(finish) finish(container, context);
	}

	private void finish(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(cb, 1);
		}
		context.getChkFetchScheduler(realTimeFlag).removePendingKeys(this, true);
		boolean cbWasActive = true;
		SplitFileStreamGenerator data = null;
		try {
			synchronized(this) {
				if(otherFailure != null) {
					throw otherFailure;
				}
				if(finished) {
					Logger.error(this, "Was already finished");
					return;
				}
				finished = true;
			}
			context.jobRunner.setCommitThisTransaction();
			if(persistent)
				container.store(this);
			data = finalStatus(container, context);
			cb.onSuccess(data, clientMetadata, decompressors, this, container, context);
		}
		catch (FetchException e) {
			cb.onFailure(e, this, container, context);
		} finally {
			if(!cbWasActive) container.deactivate(cb, 1);
		}
		if(crossCheckBlocks != 0 && !persistent) finishSegments(container, context);
	}

	@Override
	public void schedule(ObjectContainer container, ClientContext context) throws KeyListenerConstructionException {
		if(persistent)
			container.activate(this, 1);
		if(logMINOR) Logger.minor(this, "Scheduling "+this);
		SendableGet[] getters = new SendableGet[segments.length];
		for(int i=0;i<segments.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Scheduling segment "+i+" : "+segments[i]);
			if(persistent)
				container.activate(segments[i], 1);
			getters[i] = segments[i].schedule(container, context);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
		BlockSet blocks = fetchContext.blocks;
		context.getChkFetchScheduler(realTimeFlag).register(this, getters, persistent, container, blocks, false);
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		boolean persist = persistent;
		if(persist)
			container.activate(this, 1);
		for(int i=0;i<segments.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Cancelling segment "+i);
			/** FIXME this should not happen, but it is possible if the cancel() below deactivates the
			 * SplitFileFetcher. findbugs thinks it's impossible, of course... See the javadocs on
			 * the freenet.client package. */
			if(segments == null && persist && !container.ext().isActive(this)) {
				Logger.error(this, "Deactivated mid-cancel on "+this, new Exception("error"));
				container.activate(this, 1);
			}
			if(segments[i] == null) {
				synchronized(this) {
					if(finished) {
						// Not unusual, if some of the later segments are already finished when cancel() is called.
						if(logMINOR) Logger.minor(this, "Finished mid-cancel on "+this);
						return;
					}
				}
			}
			if(persist)
				container.activate(segments[i], 1);
			segments[i].cancel(container, context);
		}
	}
	
	public boolean realTimeFlag() {
		return realTimeFlag;
	}

	@Override
	public long getToken() {
		return token;
	}

	/**
	 * Make our SplitFileFetcherKeyListener. Returns the one we created in the
	 * constructor if possible, otherwise makes a new one. We must have already
	 * constructed one at some point, maybe before a restart.
	 * @throws FetchException
	 */
	@Override
	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context, boolean onStartup) throws KeyListenerConstructionException {
		synchronized(this) {
			if(finished) return null;
			if(tempListener != null) {
				// Recently constructed
				return tempListener;
			}
			File main;
			File alt;
			if(fetchContext == null) {
				Logger.error(this, "fetchContext deleted without splitfile being deleted!");
				return null;
			}
			if(persistent) {
				container.activate(mainBloomFile, 5);
				container.activate(altBloomFile, 5);
				if(mainBloomFile != null) {
					main = new File(mainBloomFile.getPath());
					container.delete(mainBloomFile);
					mainBloomFile = null;
					if(persistent) container.store(this);
				} else
					main = null;
				if(altBloomFile != null) {
					alt = new File(altBloomFile.getPath());
					container.delete(altBloomFile);
					altBloomFile = null;
					if(persistent) container.store(this);
				} else
					alt = null;
				container.deactivate(mainBloomFile, 1);
				container.deactivate(altBloomFile, 1);
			} else {
				main = null;
				alt = null;
			}
			try {
				if(logMINOR)
					Logger.minor(this, "Attempting to read Bloom filter for "+this+" main file="+main+" alt file="+alt);
				tempListener =
					new SplitFileFetcherKeyListener(this, keyCount, main, alt, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, false, cachedMainBloomFilter, cachedSegmentBloomFilters, container, onStartup, realTimeFlag);
				if(main != null) {
					try {
						FileUtil.secureDelete(main, context.fastWeakRandom);
					} catch (IOException e) {
						System.err.println("Failed to delete old bloom filter file: "+main+" - this may leak information about a download : "+e);
						e.printStackTrace();
					}
				}
				if(alt != null) {
					try {
						FileUtil.secureDelete(alt, context.fastWeakRandom);
					} catch (IOException e) {
						System.err.println("Failed to delete old segment filters file: "+alt+" - this may leak information about a download : "+e);
					}
				}
			} catch (IOException e) {
				Logger.error(this, "Unable to read Bloom filter for "+this+" attempting to reconstruct...", e);
				try {
					FileUtil.secureDelete(main, context.fastWeakRandom);
				} catch (IOException e2) {
					// Ignore
				}
				try {
					FileUtil.secureDelete(alt, context.fastWeakRandom);
				} catch (IOException e2) {
					// Ignore
				}
				mainBloomFile = null;
				altBloomFile = null;
				if(persistent)
					container.store(this);

				try {
					tempListener =
						new SplitFileFetcherKeyListener(this, keyCount, mainBloomFile, altBloomFile, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, true, cachedMainBloomFilter, cachedSegmentBloomFilters, container, onStartup, realTimeFlag);
				} catch (IOException e1) {
					throw new KeyListenerConstructionException(new FetchException(FetchException.BUCKET_ERROR, "Unable to reconstruct Bloom filters: "+e1, e1));
				}
			}
			return tempListener;
		}
	}

	@Override
	public synchronized boolean isCancelled(ObjectContainer container) {
		return finished;
	}

	public SplitFileFetcherSegment getSegment(int i) {
		return segments[i];
	}

	public void removeMyPendingKeys(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		keyCount = tempListener.killSegment(segment, container, context);
	}

	void setKeyCount(int keyCount2, ObjectContainer container) {
		this.keyCount = keyCount2;
		if(persistent)
			container.store(this);
	}

	public void onFailed(FetchException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			otherFailure = e;
		}
		cancel(container, context);
	}
	
	@Override
	public void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context) {
		onFailed(e.getFetchException(), container, context);
	}

	private boolean toRemove = false;
	// Leaks can happen if it is still in memory after removal.
	// It shouldn't be referred to by anything but it's good to detect such problems.
	private boolean removed = false;
	
	/** Remove from the database, but only if all the cross-segments have finished.
	 * If not, wait for them to report in. */
	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			toRemove = true;
			if(removed) return;
		}
		if(crossCheckBlocks > 0) {
			boolean allGone = true;
			for(int i=0;i<crossSegments.length;i++) {
				if(crossSegments[i] != null) {
					boolean active = true;
					if(persistent) {
						active = container.ext().isActive(crossSegments[i]);
						if(!active) container.activate(crossSegments[i], 1);
					}
					crossSegments[i].preRemove(container, context);
					if(!crossSegments[i].isFinished()) {
						allGone = false;
						if(logMINOR) Logger.minor(this, "Waiting for "+crossSegments[i]+" in removeFrom()");
					}
					if(!active) container.deactivate(crossSegments[i], 1);
				}
			}
			if(!allGone) {
				container.store(this);
				return;
			}
		}
		innerRemoveFrom(container, context);
	}
	
	/** Actually do the remove from the database. */
	public void innerRemoveFrom(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(removed) {
				Logger.error(this, "innerRemoveFrom() called twice", new Exception("error"));
				return;
			}
			removed = true;
		}
		if(logMINOR) Logger.minor(this, "removeFrom() on "+this, new Exception("debug"));
		if(!container.ext().isStored(this)) {
			return;
		}
		container.activate(blockFetchContext, 1);
		blockFetchContext.removeFrom(container);
		if(deleteFetchContext) {
			container.activate(fetchContext, 1);
			fetchContext.removeFrom(container);
		}
		container.activate(clientMetadata, 1);
		clientMetadata.removeFrom(container);
		container.activate(decompressors, 1);
		container.delete(decompressors);
		finishSegments(container, context);
		if(crossCheckBlocks != 0) {
			for(int i=0;i<crossSegments.length;i++) {
				SplitFileFetcherCrossSegment segment = crossSegments[i];
				crossSegments[i] = null;
				container.activate(segment, 1);
				segment.removeFrom(container, context);
			}
		}
		container.activate(mainBloomFile, 5);
		container.activate(altBloomFile, 5);
		if(mainBloomFile != null && !mainBloomFile.delete() && mainBloomFile.exists())
			Logger.error(this, "Unable to delete main bloom file: "+mainBloomFile+" for "+this);
		if(altBloomFile != null && !altBloomFile.delete() && altBloomFile.exists())
			Logger.error(this, "Unable to delete alt bloom file: "+altBloomFile+" for "+this);
		container.delete(mainBloomFile);
		container.delete(altBloomFile);
		container.activate(cachedMainBloomFilter, Integer.MAX_VALUE);
		cachedMainBloomFilter.removeFrom(container);
		for(int i=0;i<cachedSegmentBloomFilters.length;i++) {
			container.activate(cachedSegmentBloomFilters[i], Integer.MAX_VALUE);
			cachedSegmentBloomFilters[i].removeFrom(container);
		}
		container.delete(this);
	}

	/** Call fetcherFinished() on all the segments. Necessary when we have cross-segment
	 * redundancy, because we cannot free the data blocks until the cross-segment encodes
	 * have finished.
	 * @param container
	 * @param context
	 */
	private void finishSegments(ObjectContainer container, ClientContext context) {
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment segment = segments[i];
			segments[i] = null;
			if(persistent) container.activate(segment, 1);
			if(segment != null)
				segment.fetcherFinished(container, context);
		}
	}

	public boolean objectCanUpdate(ObjectContainer container) {
		if(hashCode == 0) {
			Logger.error(this, "Trying to update with hash 0 => already deleted! active="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
			return false;
		}
		synchronized(this) {
			if(removed) {
				Logger.error(this, "Trying to write but already removed", new Exception("error"));
				return false;
			}
		}
		return true;
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(hashCode == 0) {
			Logger.error(this, "Trying to write with hash 0 => already deleted! active="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
			return false;
		}
		synchronized(this) {
			if(removed) {
				Logger.error(this, "Trying to write but already removed", new Exception("error"));
				return false;
			}
		}
		return true;
	}

	/** A cross-segment has completed. When all the cross-segments have completed, and 
	 * removeFrom() has been called, we call innerRemoveFrom() to finish removing the 
	 * fetcher from the database. If the splitfile is not persistent, we still need to 
	 * call finishSegments() on each. 
	 * @return True if we finished the fetcher (and removed the segment from the database
	 * or called finishSegments()). */
	public boolean onFinishedCrossSegment(ObjectContainer container, ClientContext context, SplitFileFetcherCrossSegment seg) {
		boolean allGone = true;
		for(int i=0;i<crossSegments.length;i++) {
			if(crossSegments[i] != null) {
				boolean active = true;
				if(persistent) {
					active = container.ext().isActive(crossSegments[i]);
					if(!active) container.activate(crossSegments[i], 1);
				}
				if(!crossSegments[i].isFinished()) {
					allGone = false;
					if(logMINOR) Logger.minor(this, "Waiting for "+crossSegments[i]);
				}
				if(!active) container.deactivate(crossSegments[i], 1);
				if(!allGone) break;
			}
		}
		synchronized(this) {
			if(persistent && !toRemove) {
				// Waiting for removeFrom().
				return false;
			}
		}
		if(allGone) {
			if(persistent)
				innerRemoveFrom(container, context);
			else
				finishSegments(container, context);
			return true;
		} else if(persistent)
			container.store(this);
		return false;
	}

	public void setCachedMainFilter(CountingBloomFilter filter) {
		this.cachedMainBloomFilter = filter;
	}

	public void setCachedSegFilters(BinaryBloomFilter[] segmentFilters) {
		this.cachedSegmentBloomFilters = segmentFilters;
	}

	public SplitFileFetcherKeyListener getListener() {
		return tempListener;
	}


}
