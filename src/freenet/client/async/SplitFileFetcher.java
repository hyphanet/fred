/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.keys.NodeCHK;
import freenet.node.SendableGet;
import freenet.support.BloomFilter;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.OOMHandler;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

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
	/** Preferred bucket to return data in */
	private final Bucket returnBucket;
	private boolean finished;
	private long token;
	final boolean persistent;
	private FetchException otherFailure;

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
			FetchContext newCtx, boolean deleteFetchContext, List<? extends Compressor> decompressors2, ClientMetadata clientMetadata,
			ArchiveContext actx, int recursionLevel, Bucket returnBucket, long token2, boolean topDontCompress, short topCompatibilityMode, ObjectContainer container, ClientContext context) throws FetchException, MetadataParseException {
		this.persistent = parent2.persistent();
		this.deleteFetchContext = deleteFetchContext;
		if(logMINOR)
			Logger.minor(this, "Persistence = "+persistent+" from "+parent2, new Exception("debug"));
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		this.hashCode = hash;
		this.finished = false;
		this.returnBucket = returnBucket;
		this.fetchContext = newCtx;
		if(newCtx == null)
			throw new NullPointerException();
		this.archiveContext = actx;
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
		ClientCHK[] splitfileDataBlocks = metadata.getSplitfileDataKeys();
		ClientCHK[] splitfileCheckBlocks = metadata.getSplitfileCheckKeys();
		if(persistent) {
			// Clear them here so they don't get deleted and we don't need to clone them.
			metadata.clearSplitfileKeys();
			container.store(metadata);
		}
		for(int i=0;i<splitfileDataBlocks.length;i++)
			if(splitfileDataBlocks[i] == null) throw new MetadataParseException("Null: data block "+i+" of "+splitfileDataBlocks.length);
		for(int i=0;i<splitfileCheckBlocks.length;i++)
			if(splitfileCheckBlocks[i] == null) throw new MetadataParseException("Null: check block "+i+" of "+splitfileCheckBlocks.length);
		long eventualLength = Math.max(overrideLength, metadata.uncompressedDataLength());
		boolean wasActive = true;
		if(persistent) {
			wasActive = container.ext().isActive(cb);
			if(!wasActive)
				container.activate(cb, 1);
		}
		cb.onExpectedSize(eventualLength, container, context);
		String mimeType = metadata.getMIMEType();
		if(mimeType != null)
			cb.onExpectedMIME(mimeType, container, context);
		if(metadata.uncompressedDataLength() > 0)
			cb.onFinalizedMetadata(container);
		if(!wasActive)
			container.deactivate(cb, 1);
		if(eventualLength > 0 && newCtx.maxOutputLength > 0 && eventualLength > newCtx.maxOutputLength)
			throw new FetchException(FetchException.TOO_BIG, eventualLength, true, clientMetadata.getMIMEType());

		this.token = token2;
		
		CompatibilityMode minCompatMode = CompatibilityMode.COMPAT_UNKNOWN;
		CompatibilityMode maxCompatMode = CompatibilityMode.COMPAT_UNKNOWN;

		int crossCheckBlocks = 0;
		
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// Don't need to do much - just fetch everything and piece it together.
			blocksPerSegment = -1;
			checkBlocksPerSegment = -1;
			segmentCount = 1;
			deductBlocksFromSegments = 0;
			if(splitfileCheckBlocks.length > 0) {
				Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks.length+" check blocks found!! : "+this);
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile type is non-redundant yet have "+splitfileCheckBlocks.length+" check blocks");
			}
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			byte[] params = metadata.splitfileParams();
			int checkBlocks;
			if(metadata.getParsedVersion() == 0) {
				if((params == null) || (params.length < 8))
					throw new MetadataParseException("No splitfile params");
				blocksPerSegment = Fields.bytesToInt(params, 0);
				checkBlocks = Fields.bytesToInt(params, 4);
				deductBlocksFromSegments = 0;
				int countDataBlocks = splitfileDataBlocks.length;
				int countCheckBlocks = splitfileCheckBlocks.length;
				if(countDataBlocks == countCheckBlocks) {
					// No extra check blocks, so before 1251.
					if(blocksPerSegment == 128) {
						// Is the last segment small enough that we can't have used even splitting?
						int segs = (int)Math.ceil(((double)countDataBlocks) / 128);
						int segSize = (int)Math.ceil(((double)countDataBlocks) / ((double)segs));
						if(segSize == 128) {
							// Could be either
							minCompatMode = CompatibilityMode.COMPAT_1250_EXACT;
							maxCompatMode = CompatibilityMode.COMPAT_1250;
						} else {
							minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1250_EXACT;
						}
					} else {
						minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1250;
					}
				} else {
					if(checkBlocks == 64) {
						// Very old 128/64 redundancy.
						minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_UNKNOWN;
					} else {
						// Extra block per segment in 1251.
						minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1251;
					}
				}
			} else {
				minCompatMode = maxCompatMode = CompatibilityMode.COMPAT_1255;
				if(params.length < 10)
					throw new MetadataParseException("Splitfile parameters too short for version 1");
				short paramsType = Fields.bytesToShort(params, 0);
				if(paramsType == Metadata.SPLITFILE_PARAMS_SIMPLE_SEGMENT || paramsType == Metadata.SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS || paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
					blocksPerSegment = Fields.bytesToInt(params, 2);
					checkBlocks = Fields.bytesToInt(params, 6);
				} else
					throw new MetadataParseException("Unknown splitfile params type "+paramsType);
				if(paramsType == Metadata.SPLITFILE_PARAMS_SEGMENT_DEDUCT_BLOCKS || paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
					deductBlocksFromSegments = Fields.bytesToInt(params, 10);
					if(paramsType == Metadata.SPLITFILE_PARAMS_CROSS_SEGMENT) {
						crossCheckBlocks = Fields.bytesToInt(params, 14);
					}
				} else
					deductBlocksFromSegments = 0;
			}
			
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
			cb.onSplitfileCompatibilityMode(minCompatMode, maxCompatMode, metadata.getCustomSplitfileKey(), dontCompress, true, topCompatibilityMode != 0, container, context);

			// FIXME remove this eventually. Will break compat with a few files inserted between 1135 and 1136.
			// Work around a bug around build 1135.
			// We were splitting as (128,255), but we were then setting the checkBlocksPerSegment to 64.
			// Detect this.
			if(checkBlocks == 64 && blocksPerSegment == 128 &&
					splitfileCheckBlocks.length == splitfileDataBlocks.length - (splitfileDataBlocks.length / 128)) {
				Logger.normal(this, "Activating 1135 wrong check blocks per segment workaround for "+this);
				checkBlocks = 127;
			}
			checkBlocksPerSegment = checkBlocks;

			if((blocksPerSegment > fetchContext.maxDataBlocksPerSegment)
					|| (checkBlocksPerSegment > fetchContext.maxCheckBlocksPerSegment))
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			segmentCount = (splitfileDataBlocks.length / (blocksPerSegment + crossCheckBlocks)) +
				(splitfileDataBlocks.length % (blocksPerSegment + crossCheckBlocks) == 0 ? 0 : 1);
				
			// Onion, 128/192.
			// Will be segmented.
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		this.maxTempLength = fetchContext.maxTempLength;
		if(logMINOR)
			Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+
					", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount+
					", data blocks: "+splitfileDataBlocks.length+", check blocks: "+splitfileCheckBlocks.length);
		segments = new SplitFileFetcherSegment[segmentCount]; // initially null on all entries

		this.crossCheckBlocks = crossCheckBlocks;
		
		long finalLength = 1L * (splitfileDataBlocks.length - segmentCount * crossCheckBlocks) * CHKBlock.DATA_LENGTH;
		if(finalLength > overrideLength) {
			if(finalLength - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+finalLength+" but length is "+finalLength);
			finalLength = overrideLength;
		}
		
		// Setup bloom parameters.
		if(persistent) {
			// FIXME: Should this be encrypted? It's protected to some degree by the salt...
			// Since it isn't encrypted, it's likely to be very sparse; we should name
			// it appropriately...
			try {
				mainBloomFile = context.persistentFG.makeRandomFile();
				altBloomFile = context.persistentFG.makeRandomFile();
			} catch (IOException e) {
				throw new FetchException(FetchException.BUCKET_ERROR, "Unable to create Bloom filter files", e);
			}
		} else {
			// Not persistent, keep purely in RAM.
			mainBloomFile = null;
			altBloomFile = null;
		}
		int mainElementsPerKey = DEFAULT_MAIN_BLOOM_ELEMENTS_PER_KEY;
		int origSize = splitfileDataBlocks.length + splitfileCheckBlocks.length;
		mainBloomK = (int) (mainElementsPerKey * 0.7);
		long elementsLong = origSize * mainElementsPerKey;
		// REDFLAG: SIZE LIMIT: 3.36TB limit!
		if(elementsLong > Integer.MAX_VALUE)
			throw new FetchException(FetchException.TOO_BIG, "Cannot fetch splitfiles with more than "+(Integer.MAX_VALUE/mainElementsPerKey)+" keys! (approx 3.3TB)");
		int mainSizeBits = (int)elementsLong; // counting filter
		if((mainSizeBits & 7) != 0)
			mainSizeBits += (8 - (mainSizeBits & 7));
		mainBloomFilterSizeBytes = mainSizeBits / 8 * 2; // counting filter
		double acceptableFalsePositives = ACCEPTABLE_BLOOM_FALSE_POSITIVES_ALL_SEGMENTS / segments.length;
		int perSegmentBitsPerKey = (int) Math.ceil(Math.log(acceptableFalsePositives) / Math.log(0.6185));
		int segBlocks = blocksPerSegment + checkBlocksPerSegment;
		if(segBlocks > origSize)
			segBlocks = origSize;
		int perSegmentSize = perSegmentBitsPerKey * segBlocks;
		if((perSegmentSize & 7) != 0)
			perSegmentSize += (8 - (perSegmentSize & 7));
		perSegmentBloomFilterSizeBytes = perSegmentSize / 8;
		perSegmentK = BloomFilter.optimialK(perSegmentSize, segBlocks);
		keyCount = origSize;
		// Now create it.
		if(logMINOR)
			Logger.minor(this, "Creating block filter for "+this+": keys="+(splitfileDataBlocks.length+splitfileCheckBlocks.length)+" main bloom size "+mainBloomFilterSizeBytes+" bytes, K="+mainBloomK+", filename="+mainBloomFile+" alt bloom filter: filename="+altBloomFile+" segments: "+segments.length+" each is "+perSegmentBloomFilterSizeBytes+" bytes k="+perSegmentK);
		try {
			tempListener = new SplitFileFetcherKeyListener(this, keyCount, mainBloomFile, altBloomFile, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, true);
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, "Unable to write Bloom filters for splitfile");
		}

		if(persistent)
			container.store(this);

		boolean pre1254 = !(minCompatMode == CompatibilityMode.COMPAT_CURRENT || minCompatMode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal());
		boolean pre1250 = (minCompatMode == CompatibilityMode.COMPAT_UNKNOWN || minCompatMode == CompatibilityMode.COMPAT_1250_EXACT);
		
		blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true, null);
		if(segmentCount == 1) {
			// splitfile* will be overwritten, this is bad
			// so copy them
			ClientCHK[] newSplitfileDataBlocks = new ClientCHK[splitfileDataBlocks.length];
			ClientCHK[] newSplitfileCheckBlocks = new ClientCHK[splitfileCheckBlocks.length];
			System.arraycopy(splitfileDataBlocks, 0, newSplitfileDataBlocks, 0, splitfileDataBlocks.length);
			if(splitfileCheckBlocks.length > 0)
				System.arraycopy(splitfileCheckBlocks, 0, newSplitfileCheckBlocks, 0, splitfileCheckBlocks.length);
			segments[0] = new SplitFileFetcherSegment(splitfileType, newSplitfileDataBlocks, newSplitfileCheckBlocks,
					this, archiveContext, blockFetchContext, maxTempLength, recursionLevel, parent, 0, pre1250, pre1254, crossCheckBlocks, metadata.getSplitfileCryptoAlgorithm(), metadata.getSplitfileCryptoKey());
			for(int i=0;i<newSplitfileDataBlocks.length;i++) {
				if(logMINOR) Logger.minor(this, "Added data block "+i+" : "+newSplitfileDataBlocks[i].getNodeKey(false));
				tempListener.addKey(newSplitfileDataBlocks[i].getNodeKey(true), 0, context);
			}
			for(int i=0;i<newSplitfileCheckBlocks.length;i++) {
				if(logMINOR) Logger.minor(this, "Added check block "+i+" : "+newSplitfileCheckBlocks[i].getNodeKey(false));
				tempListener.addKey(newSplitfileCheckBlocks[i].getNodeKey(true), 0, context);
			}
			if(persistent) {
				container.store(segments[0]);
				segments[0].deactivateKeys(container);
				container.deactivate(segments[0], 1);
			}
		} else {
			int dataBlocksPtr = 0;
			int checkBlocksPtr = 0;
			for(int i=0;i<segments.length;i++) {
				// Create a segment. Give it its keys.
				int copyDataBlocks = blocksPerSegment + crossCheckBlocks;
				int copyCheckBlocks = checkBlocksPerSegment;
				if(i == segments.length - 1) {
					// Always accept the remainder as the last segment, but do basic sanity checking.
					// In practice this can be affected by various things: 1) On old splitfiles before full even 
					// segment splitting with deductBlocksFromSegments (i.e. pre-1255), the last segment could be
					// significantly smaller than the rest; 2) On 1251-1253, with partial even segment splitting,
					// up to 131 data blocks per segment, cutting the check blocks if necessary, and with an extra
					// check block if possible, the last segment could have *more* check blocks than the rest. 
					copyDataBlocks = splitfileDataBlocks.length - dataBlocksPtr;
					copyCheckBlocks = splitfileCheckBlocks.length - checkBlocksPtr;
					if(copyCheckBlocks <= 0 || copyDataBlocks <= 0)
						throw new FetchException(FetchException.INVALID_METADATA, "Last segment has bogus block count: total data blocks "+splitfileDataBlocks.length+" total check blocks "+splitfileCheckBlocks.length+" segment size "+blocksPerSegment+" data "+checkBlocksPerSegment+" check "+crossCheckBlocks+" cross check blocks, deduct "+deductBlocksFromSegments+", segments "+segments.length);
					if((copyDataBlocks > fetchContext.maxDataBlocksPerSegment)
							|| (copyCheckBlocks > fetchContext.maxCheckBlocksPerSegment))
						throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
				} else if(segments.length - i <= deductBlocksFromSegments) {
					// Deduct one data block from each of the last deductBlocksFromSegments segments.
					// This ensures no segment is more than 1 block larger than any other.
					// We do not shrink the check blocks.
					copyDataBlocks--;
				}
				if(logMINOR) Logger.minor(this, "REQUESTING: Segment "+i+" of "+segments.length+" : "+copyDataBlocks+" data blocks "+copyCheckBlocks+" check blocks");
				ClientCHK[] dataBlocks = new ClientCHK[copyDataBlocks];
				ClientCHK[] checkBlocks = new ClientCHK[copyCheckBlocks];
				if(copyDataBlocks > 0)
					System.arraycopy(splitfileDataBlocks, dataBlocksPtr, dataBlocks, 0, copyDataBlocks);
				if(copyCheckBlocks > 0)
					System.arraycopy(splitfileCheckBlocks, checkBlocksPtr, checkBlocks, 0, copyCheckBlocks);
				segments[i] = new SplitFileFetcherSegment(splitfileType, dataBlocks, checkBlocks, this, archiveContext,
						blockFetchContext, maxTempLength, recursionLevel+1, parent, i, pre1250 && i == segments.length-1, pre1254, crossCheckBlocks, metadata.getSplitfileCryptoAlgorithm(), metadata.getSplitfileCryptoKey());
				for(int j=0;j<dataBlocks.length;j++)
					tempListener.addKey(dataBlocks[j].getNodeKey(true), i, context);
				for(int j=0;j<checkBlocks.length;j++)
					tempListener.addKey(checkBlocks[j].getNodeKey(true), i, context);
				if(persistent) {
					container.store(segments[i]);
					segments[i].deactivateKeys(container);
					for(int x=dataBlocksPtr;x<dataBlocksPtr+copyDataBlocks;x++)
						splitfileDataBlocks[x] = null;
					for(int x=checkBlocksPtr;x<checkBlocksPtr+copyCheckBlocks;x++)
						splitfileCheckBlocks[x] = null;
				}
				dataBlocksPtr += copyDataBlocks;
				checkBlocksPtr += copyCheckBlocks;
			}
			if(dataBlocksPtr != splitfileDataBlocks.length)
				throw new FetchException(FetchException.INVALID_METADATA, "Unable to allocate all data blocks to segments - buggy or malicious inserter");
			if(checkBlocksPtr != splitfileCheckBlocks.length)
				throw new FetchException(FetchException.INVALID_METADATA, "Unable to allocate all check blocks to segments - buggy or malicious inserter");
		}
		int totalCrossCheckBlocks = segments.length * crossCheckBlocks;
		parent.addMustSucceedBlocks(splitfileDataBlocks.length - totalCrossCheckBlocks, container);
		parent.addBlocks(splitfileCheckBlocks.length + totalCrossCheckBlocks, container);
		parent.notifyClients(container, context);
		
		if(crossCheckBlocks != 0) {
			Random random = new MersenneTwister(Metadata.getCrossSegmentSeed(metadata.getHashes(), metadata.getHashThisLayerOnly()));
			// Cross segment redundancy: Allocate the blocks.
			crossSegments = new SplitFileFetcherCrossSegment[segments.length];
			int segLen = blocksPerSegment;
			for(int i=0;i<crossSegments.length;i++) {
				System.out.println("Allocating blocks (on fetch) for cross segment "+i);
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
			tempListener.writeFilters();
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

	/** Return the final status of the fetch. Throws an exception, or returns a
	 * Bucket containing the fetched data.
	 * @throws FetchException If the fetch failed for some reason.
	 */
	private Bucket finalStatus(ObjectContainer container, ClientContext context) throws FetchException {
		long finalLength = 0;
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
			finalLength += sz;
			if(logMINOR)
				Logger.minor(this, "Segment "+i+" decoded length "+sz+" total length now "+finalLength+" for "+s.dataBuckets.length+" blocks which should be "+(s.dataBuckets.length * NodeCHK.BLOCK_SIZE)+" for "+this);
			// Healing is done by Segment
		}
		if(finalLength > overrideLength) {
			if(finalLength - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+finalLength+" but length is "+finalLength);
			finalLength = overrideLength;
		}

		long bytesWritten = 0;
		OutputStream os = null;
		Bucket output;
		if(persistent) {
			container.activate(decompressors, 5);
			if(returnBucket != null)
				container.activate(returnBucket, 5);
		}
		try {
			if((returnBucket != null) && decompressors.isEmpty()) {
				output = returnBucket;
			} else
				output = context.getBucketFactory(parent.persistent()).makeBucket(finalLength);
			os = output.getOutputStream();
			for(int i=0;i<segments.length;i++) {
				SplitFileFetcherSegment s = segments[i];
				long max = (finalLength < 0 ? 0 : (finalLength - bytesWritten));
				bytesWritten += s.writeDecodedDataTo(os, max, container);
				if(crossCheckBlocks == 0)
					s.fetcherHalfFinished(container);
				// Else we need to wait for the cross-segment fetchers and innerRemoveFrom()
			}
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, e);
		} finally {
			if(os != null) {
				try {
					os.close();
				} catch (IOException e) {
					// If it fails to close it may return corrupt data.
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				}
			}
		}
		if(finalLength != output.size()) {
			Logger.error(this, "Final length is supposed to be "+finalLength+" but only written "+output.size());
		}
		return output;
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
					Logger.error(this, "Was already finished! (segmentFinished("+segment+ ')', new Exception("debug"));
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
		context.getChkFetchScheduler().removePendingKeys(this, true);
		boolean cbWasActive = true;
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
			Bucket data = finalStatus(container, context);
			// Decompress
			if(persistent) {
				container.activate(decompressors, 5);
				container.activate(returnBucket, 5);
				cbWasActive = container.ext().isActive(cb);
				if(!cbWasActive)
					container.activate(cb, 1);
				container.activate(fetchContext, 1);
				if(fetchContext == null) {
					Logger.error(this, "Fetch context is null");
					if(!container.ext().isActive(fetchContext)) {
						Logger.error(this, "Fetch context is null and splitfile is not activated", new Exception("error"));
						container.activate(this, 1);
						container.activate(decompressors, 5);
						container.activate(returnBucket, 5);
						container.activate(fetchContext, 1);
					} else {
						Logger.error(this, "Fetch context is null and splitfile IS activated", new Exception("error"));
					}
				}
				container.activate(fetchContext, 1);
			}
			int count = 0;
			while(!decompressors.isEmpty()) {
				Compressor c = decompressors.remove(decompressors.size()-1);
				if(logMINOR)
					Logger.minor(this, "Decompressing with "+c);
				long maxLen = Math.max(fetchContext.maxTempLength, fetchContext.maxOutputLength);
				Bucket orig = data;
				try {
					Bucket out = returnBucket;
					if(!decompressors.isEmpty()) out = null;
					data = c.decompress(data, context.getBucketFactory(parent.persistent()), maxLen, maxLen * 4, out);
				} catch (IOException e) {
					if(e.getMessage().equals("Not in GZIP format") && count == 1) {
						Logger.error(this, "Attempting to decompress twice, failed, returning first round data: "+this);
						break;
					}
					cb.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), this, container, context);
					return;
				} catch (CompressionOutputSizeException e) {
					if(logMINOR)
						Logger.minor(this, "Too big: maxSize = "+fetchContext.maxOutputLength+" maxTempSize = "+fetchContext.maxTempLength);
					cb.onFailure(new FetchException(FetchException.TOO_BIG, e.estimatedSize, false /* FIXME */, clientMetadata.getMIMEType()), this, container, context);
					return;
				} finally {
					if(orig != data) {
						orig.free();
						if(persistent) orig.removeFrom(container);
					}
				}
				count++;
			}
			cb.onSuccess(new FetchResult(clientMetadata, data), this, container, context);
		} catch (FetchException e) {
			cb.onFailure(e, this, container, context);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("Failing above attempted fetch...");
			cb.onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), this, container, context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), this, container, context);
		}
		if(!cbWasActive)
			container.deactivate(cb, 1);
	}

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
		context.getChkFetchScheduler().register(this, getters, persistent, container, blocks, false);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		boolean persist = persistent;
		if(persist)
			container.activate(this, 1);
		for(int i=0;i<segments.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Cancelling segment "+i);
			if(segments == null && persist && !container.ext().isActive(this)) {
				// FIXME is this normal? If so just reactivate.
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

	public long getToken() {
		return token;
	}

	/**
	 * Make our SplitFileFetcherKeyListener. Returns the one we created in the
	 * constructor if possible, otherwise makes a new one. We must have already
	 * constructed one at some point, maybe before a restart.
	 * @throws FetchException
	 */
	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context) throws KeyListenerConstructionException {
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
				main = new File(mainBloomFile.getPath());
				alt = new File(altBloomFile.getPath());
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
					new SplitFileFetcherKeyListener(this, keyCount, main, alt, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, false);
			} catch (IOException e) {
				Logger.error(this, "Unable to read Bloom filter for "+this+" attempting to reconstruct...", e);
				main.delete();
				alt.delete();
				try {
					mainBloomFile = context.fg.makeRandomFile();
					altBloomFile = context.fg.makeRandomFile();
					if(persistent)
						container.store(this);
				} catch (IOException e1) {
					throw new KeyListenerConstructionException(new FetchException(FetchException.BUCKET_ERROR, "Unable to create Bloom filter files in reconstruction", e1));
				}

				try {
					tempListener =
						new SplitFileFetcherKeyListener(this, keyCount, mainBloomFile, altBloomFile, mainBloomFilterSizeBytes, mainBloomK, localSalt, segments.length, perSegmentBloomFilterSizeBytes, perSegmentK, persistent, true);
				} catch (IOException e1) {
					throw new KeyListenerConstructionException(new FetchException(FetchException.BUCKET_ERROR, "Unable to reconstruct Bloom filters: "+e1, e1));
				}
			}
			return tempListener;
		}
	}

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

	public void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context) {
		otherFailure = e.getFetchException();
		cancel(container, context);
	}

	private boolean toRemove = false;
	
	public void removeFrom(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			toRemove = true;
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
	
	public void innerRemoveFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "removeFrom() on "+this, new Exception("debug"));
		if(!container.ext().isStored(this)) {
			Logger.error(this, "Already removed??? on "+this, new Exception("error"));
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
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment segment = segments[i];
			segments[i] = null;
			container.activate(segment, 1);
			segment.fetcherFinished(container, context);
		}
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
		else if(mainBloomFile == null)
			Logger.error(this, "mainBloomFile is null on "+this);
		else
			if(logMINOR) Logger.minor(this, "Deleted main bloom file "+mainBloomFile);
		if(altBloomFile != null && !altBloomFile.delete() && altBloomFile.exists())
			Logger.error(this, "Unable to delete alt bloom file: "+altBloomFile+" for "+this);
		else if(altBloomFile == null)
			Logger.error(this, "altBloomFile is null on "+this);
		else
			if(logMINOR) Logger.minor(this, "Deleted alt bloom file "+altBloomFile);
		container.delete(mainBloomFile);
		container.delete(altBloomFile);
		container.delete(this);
	}

	public boolean objectCanUpdate(ObjectContainer container) {
		if(hashCode == 0) {
			Logger.error(this, "Trying to update with hash 0 => already deleted! active="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
			return false;
		}
		return true;
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(hashCode == 0) {
			Logger.error(this, "Trying to write with hash 0 => already deleted! active="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
			return false;
		}
		return true;
	}

	public void onFinishedCrossSegment(ObjectContainer container, ClientContext context, SplitFileFetcherCrossSegment seg) {
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
		if(!toRemove) return;
		if(allGone)
			innerRemoveFrom(container, context);
		else if(persistent)
			container.store(this);
	}


}
